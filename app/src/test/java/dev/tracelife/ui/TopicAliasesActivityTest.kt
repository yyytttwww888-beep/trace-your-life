package dev.tracelife.ui

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.widget.EditText
import dev.tracelife.TraceLifeApplication
import dev.tracelife.knowledge.TopicAliases
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TopicAliasesActivityTest {
    private lateinit var app: TraceLifeApplication
    private var controller: ActivityController<TopicAliasesActivity>? = null
    private val activity get() = requireNotNull(controller).get()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        app.io.submit {}.get(20, TimeUnit.SECONDS)
    }
    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `form survives recreation and explicit local save creates navigation only`() {
        controller = Robolectric.buildActivity(TopicAliasesActivity::class.java).setup(); drain()
        click("newTopicAlias")
        field("aliasCanonical").setText("学习方法")
        field("aliasTerms").setText("学习策略\n实践方法")
        controller!!.recreate(); drain()
        assertEquals("学习方法", field("aliasCanonical").text.toString())
        assertEquals("学习策略\n实践方法", field("aliasTerms").text.toString())
        assertTrue(TopicAliases(app.repository).page().items.isEmpty())
        click("saveTopicAlias")
        assertEquals(setOf("学习方法", "学习策略", "实践方法"), TopicAliases(app.repository).expand(setOf("学习策略")))
        assertTrue(app.repository.analysisRunIndex().isEmpty())
        assertFalse(app.incrementalOrganizer.policy().enabled)
    }

    @Test fun `archive confirmation removes rule from active navigation without deleting history`() {
        val rule = app.io.submit<dev.tracelife.knowledge.TopicAliasRule> {
            TopicAliases(app.repository).save("工作", setOf("项目实践"))
        }.get(20, TimeUnit.SECONDS)
        controller = Robolectric.buildActivity(TopicAliasesActivity::class.java).setup(); drain()
        click("archiveAlias:${rule.id}")
        assertFalse(TopicAliases(app.repository).get(rule.id)!!.archived)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain()
        assertTrue(TopicAliases(app.repository).get(rule.id)!!.archived)
        assertEquals(setOf("项目实践"), TopicAliases(app.repository).expand(setOf("项目实践")))
        assertEquals(2, app.repository.history(rule.id).size)
        assertTrue(app.repository.analysisRunIndex().isEmpty())
    }

    @Test fun `malformed configuration exposes repair and original history without blocking management`() {
        val original = app.io.submit<dev.tracelife.model.RecordRevision> {
            val ref = dev.tracelife.knowledge.TopicAliasTemplate.obtain(app.repository)
            app.repository.createRecord(dev.tracelife.model.RecordDraft("合成旧配置原文", template = ref))
        }.get(20, TimeUnit.SECONDS)
        controller = Robolectric.buildActivity(TopicAliasesActivity::class.java).setup(); drain()
        assertNotNull(activity.window.decorView.findViewWithTag<View>("sourceAliasProblem:${original.id}"))
        click("repairAlias:${original.id}")
        field("aliasCanonical").setText("学习方法")
        field("aliasTerms").setText("学习策略")
        click("saveTopicAlias")
        assertEquals(setOf("学习方法", "学习策略"), TopicAliases(app.repository).expand(setOf("学习策略")))
        assertEquals(original.draft, app.repository.record(original.id, 1)!!.draft)
        assertEquals(original.draft.body, app.repository.record(original.id)!!.draft.body)
        assertTrue(TopicAliases(app.repository).page().problems.isEmpty())
        assertTrue(app.repository.analysisRunIndex().isEmpty())
    }

    private fun field(tag: String): EditText = activity.window.decorView.findViewWithTag(tag)
    private fun click(tag: String) { activity.window.decorView.findViewWithTag<View>(tag).performClick(); drain() }
    private fun drain() { repeat(3) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() } }
}
