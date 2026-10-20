package dev.tracelife.ui

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import dev.tracelife.TraceLifeApplication
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.templates.ActivityLogTemplate
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
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActivityEntryActivityTest {
    private lateinit var app: TraceLifeApplication
    private var controller: ActivityController<ActivityEntryActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    @Before fun setup() { app = RuntimeEnvironment.getApplication() as TraceLifeApplication }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `optional values survive recreation and save as an ordinary dated source`() {
        open()
        input("date", "2026-10-12")
        input("body", "合成资料：对照例子练习")
        input("minutes", "35")
        input("practices", "0")
        input("outcome", "合成资料：得到一个可运行例子")
        controller!!.recreate(); drain()
        assertEquals("35", view<EditText>("activity:minutes").text.toString())
        assertEquals(1, view<Spinner>("activityDomain").selectedItemPosition)
        click("saveActivity")
        val saved = app.repository.listRecords().items.single()
        assertEquals(LocalDate.parse("2026-10-12"), saved.draft.occurrence.startDate)
        assertEquals("0", saved.draft.answers[ActivityLogTemplate.PRACTICES])
        assertFalse(saved.draft.answers.containsKey(ActivityLogTemplate.CONTEXT))
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertTrue(activity.isFinishing)
    }

    @Test fun `invalid duration keeps draft and does not create a record or template`() {
        open(); input("body", "合成：等待修正时长"); input("minutes", "1441")
        val before = app.repository.listTemplates().size
        click("saveActivity")
        assertEquals(0, app.repository.listRecords().total)
        assertEquals(before, app.repository.listTemplates().size)
        assertEquals("1441", view<EditText>("activity:minutes").text.toString())
        assertFalse(activity.isFinishing)
    }

    @Test fun `editing preserves source identity existing topics and revision history`() {
        val original = app.io.submit<dev.tracelife.model.RecordRevision> {
            val ref = ActivityLogTemplate.obtain(app.repository)
            app.repository.createRecord(RecordDraft("合成：原活动", Occurrence(LocalDate.now()), setOf("项目甲", "学习"), ref,
                mapOf(ActivityLogTemplate.DOMAIN to "学习", ActivityLogTemplate.MINUTES to "20")), "activity-edit")
        }.get(20, TimeUnit.SECONDS)
        open(original.id)
        input("minutes", "40"); controller!!.recreate(); drain(); click("saveActivity")
        val saved = app.repository.record(original.id)!!
        assertEquals(2, saved.revision)
        assertEquals(setOf("项目甲", "学习"), saved.draft.topics)
        assertEquals("40", saved.draft.answers[ActivityLogTemplate.MINUTES])
        assertEquals("20", app.repository.record(original.id, 1)!!.draft.answers[ActivityLogTemplate.MINUTES])
    }

    private fun open(id: String? = null) {
        val intent = Intent(app, ActivityEntryActivity::class.java).apply { id?.let { putExtra(ActivityEntryActivity.RECORD_ID, it) } }
        controller = Robolectric.buildActivity(ActivityEntryActivity::class.java, intent).setup(); drain()
    }
    private fun input(name: String, value: String) { view<EditText>("activity:$name").setText(value) }
    private fun click(tag: String) { view<View>(tag).performClick(); drain() }
    private fun <T : View> view(tag: String): T = requireNotNull(activity.window.decorView.findViewWithTag(tag))
    private fun drain() { repeat(4) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() } }
}
