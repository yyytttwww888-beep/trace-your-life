package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import dev.tracelife.TraceLifeApplication
import dev.tracelife.actions.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActionReflectionActivityTest {
    private lateinit var app: TraceLifeApplication
    private lateinit var workflow: ActionReflectionWorkflow
    private var controller: ActivityController<ActionReflectionActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val today get() = LocalDate.now()
    private val provider = SyntheticActionReflectionProvider()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        app.io.submit {}.get(20, TimeUnit.SECONDS)
        workflow = ActionReflectionWorkflow(app.repository, app.analysis, app.analysisFeedback, app.knowledge)
    }
    @After fun teardown() {
        controller?.pause()?.stop()?.destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `range is preserved across rotation and cancelled start never creates requests`() {
        open(Intent(app, ActionReflectionActivity::class.java)
            .putExtra(ActionReflectionActivity.EXTRA_FROM, "2026-10-01")
            .putExtra(ActionReflectionActivity.EXTRA_THROUGH, "2026-10-14")
            .putExtra(ActionReflectionActivity.EXTRA_ZONE_ID, "UTC")
            .putExtra(ActionReflectionActivity.EXTRA_INCLUDE_ARCHIVED, true)
            .putStringArrayListExtra(ActionReflectionActivity.EXTRA_RECORD_IDS, arrayListOf("synthetic-selected")))
        requireNotNull(controller).recreate(); drain()
        assertEquals("2026-10-01", view<EditText>("actionReflectionFrom").text.toString())
        assertEquals("2026-10-14", view<EditText>("actionReflectionThrough").text.toString())
        assertEquals("UTC", view<EditText>("actionReflectionZone").text.toString())
        assertTrue(texts().any { it.contains("只使用原范围指定的 1 条记录") })
        click("startActionReflection")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.findViewById<TextView>(android.R.id.message).text.contains("DeepSeek"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); drain()
        assertTrue(workflow.history().isEmpty())
        assertEquals(0, provider.calls)
    }

    @Test fun `plan review and next plan stay distinct and unknown execution is never failure`() {
        val run = seed(withReview = true)
        val calls = provider.calls
        open(Intent(app, ActionReflectionActivity::class.java).putExtra(ActionReflectionActivity.EXTRA_RUN_ID, run.task.stored.id))
        click("actionReflectionMaterials")
        val receipt = app.analysis.actionPage(run.task.stored.id).entries.single { it.kind == ActionEvidenceKind.USER_REVIEW }
        click("actionReflectionMaterial:${receipt.id}")
        assertTrue(texts().containsAll(listOf("这次回看针对的旧计划", "执行回看", "之后选择的新计划", "执行情况仍未知")))
        assertTrue(texts().contains("合成原计划：先做完整练习"))
        assertTrue(texts().contains("合成新计划：先尝试五分钟"))
        assertTrue(texts().any { it.contains("合成观察：是否完成尚不清楚") })
        assertEquals(calls, provider.calls)
    }

    @Test fun `finding opens original context and rejection survives recreation without requests`() {
        val run = seed()
        val calls = provider.calls
        open(Intent(app, ActionReflectionActivity::class.java).putExtra(ActionReflectionActivity.EXTRA_RUN_ID, run.task.stored.id))
        click("actionReflectionFinding:0")
        click("actionReflectionSources")
        val record = workflow.sources(run.task.stored.id, run.findings.single().target).records.single()
        click("actionReflectionOriginal:${record.id}")
        assertTrue(texts().any { it.contains("合成日常记录：打开练习") })
        requireNotNull(controller).recreate(); drain()
        assertNotNull(activity.window.decorView.findViewWithTag<View>("keepActionReflectionFinding"))
        click("rejectActionReflectionFinding")
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain()
        assertTrue(texts().contains("已否定"))
        assertNull(activity.window.decorView.findViewWithTag<View>("keepActionReflectionFinding"))
        requireNotNull(controller).recreate(); drain()
        assertTrue(texts().contains("已否定"))
        assertEquals(ClaimFeedbackDecision.REJECTED, workflow.inspect(run.task.stored.id).findings.single().feedback!!.feedback.decision)
        assertEquals(calls, provider.calls)
    }

    @Test fun `partial batches cannot expose confirmation and archived results remain read only`() {
        val run = seed(requests = 1, inputs = 1)
        open(Intent(app, ActionReflectionActivity::class.java).putExtra(ActionReflectionActivity.EXTRA_RUN_ID, run.task.stored.id))
        click("actionReflectionFinding:0")
        assertNull(activity.window.decorView.findViewWithTag<View>("keepActionReflectionFinding"))
        assertNull(activity.window.decorView.findViewWithTag<View>("rejectActionReflectionFinding"))
        assertTrue(texts().any { it.contains("部分发现不能代替完整周期判断") })
        controller?.pause()?.stop()?.destroy(); controller = null
        app.io.submit { app.repository.archiveAnalysisRun(run.task.stored.id, run.task.stored.version) }.get(20, TimeUnit.SECONDS)
        open(Intent(app, ActionReflectionActivity::class.java).putExtra(ActionReflectionActivity.EXTRA_RUN_ID, run.task.stored.id))
        assertTrue(texts().contains("已归档"))
        assertNull(activity.window.decorView.findViewWithTag<View>("continueActionReflection"))
        assertEquals(1, provider.calls)
    }

    @Test fun `saved result links to knowledge and a later action review blocks old publication`() {
        val run = seed()
        val saved = app.io.submit<ActionReflectionRun> {
            workflow.respond(run.task.stored.id, run.findings.single().target, run.task.stored.version, ClaimFeedbackDecision.CONFIRMED)
            workflow.reviewAndSave(run.task.stored.id, run.findings.single().target, provider, provider).run
        }.get(20, TimeUnit.SECONDS)
        open(Intent(app, ActionReflectionActivity::class.java).putExtra(ActionReflectionActivity.EXTRA_RUN_ID, run.task.stored.id))
        click("actionReflectionFinding:0")
        click("actionReflectionSaved")
        assertEquals(saved.findings.single().saved!!.value.id,
            shadowOf(activity).nextStartedActivity.getStringExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_ID))
        app.io.submit {
            app.actions.review("synthetic-action", 1, "later-review", ActionReview(ActionExecution.NOT_EXECUTED,
                "合成补记：本次没有执行", ActionDecision.DEFER, today), today.plusDays(7))
        }.get(20, TimeUnit.SECONDS)
        requireNotNull(controller).pause().stop().start().resume().visible(); drain()
        assertNull(activity.window.decorView.findViewWithTag<View>("keepActionReflectionFinding"))
        assertTrue(texts().any { it.contains("已保存 · 待更新") })
    }

    private fun seed(withReview: Boolean = false, requests: Int = 3, inputs: Int = 16): ActionReflectionRun = app.io.submit<ActionReflectionRun> {
        val draft = ActionDraft("合成学习尝试", "学习开始", "合成原计划：先做完整练习", "记录实际做了什么", today.plusDays(7), ZoneId.systemDefault().id)
        app.actions.create(draft, "synthetic-action")
        if (withReview) app.actions.review("synthetic-action", 1, "synthetic-review", ActionReview(ActionExecution.UNKNOWN,
            "合成观察：是否完成尚不清楚", ActionDecision.ADJUST, today),
            adjustedDraft = draft.copy(behavior = "合成新计划：先尝试五分钟"))
        app.repository.createRecord(RecordDraft("合成日常记录：打开练习，但还没有写下完成情况。", Occurrence(today)))
        val run = workflow.start(AnalysisQuery(today.minusDays(6), today, ZoneId.systemDefault().id))
        workflow.process(run.task.stored.id, provider, requests, inputs).run
    }.get(20, TimeUnit.SECONDS)

    private fun open(intent: Intent) { controller = Robolectric.buildActivity(ActionReflectionActivity::class.java, intent).setup(); drain() }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun texts() = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun <T : View> view(tag: String): T = requireNotNull(activity.window.decorView.findViewWithTag<T>(tag)) { "Missing $tag" }
    private fun click(tag: String) { assertTrue(view<View>(tag).performClick()); drain() }
    private fun drain() { repeat(5) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() } }
}
