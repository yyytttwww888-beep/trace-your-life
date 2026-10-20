package dev.tracelife.ui

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.tracelife.TraceLifeApplication
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import org.json.JSONObject
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FeedbackNotesActivityTest {
    private lateinit var app: TraceLifeApplication
    private var controller: ActivityController<FeedbackNotesActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val today get() = LocalDate.now()
    private var requests = 0
    private val machineText = "值得留下：合成机器文字仅作为候选，不能复制到独立说明。"
    private val provider = object : AnalysisProvider {
        override val id = "synthetic-notes-provider"
        override val contextWindowTokens = 128_000
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        override fun complete(request: ModelRequest): BatchProposal {
            requests++
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return BatchProposal(listOf(ProposedClaim(machineText, listOf(id), ClaimKind.POSSIBLE_INTERPRETATION)))
        }
    }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        app.io.submit {}.get(20, TimeUnit.SECONDS)
    }
    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `independent comparison notes remain readable after sources are deleted and only explicit delete removes them`() {
        val (run, priorFeedback) = seed()
        app.io.submit { app.repository.deleteRecord("old-source", 1) }.get(20, TimeUnit.SECONDS)
        assertNull(app.repository.analysisRun(run.id))
        val before = requests
        open()
        click("comparisonRun:${run.id}")
        assertTrue(text().contains("合成第一次独立理由"))
        assertTrue(text().contains("合成第二次独立理由"))
        assertTrue(text().contains("当时的分析已删除"))
        assertFalse(text().contains(machineText))
        requireNotNull(controller).recreate(); drain()
        assertTrue(text().contains("合成第二次独立理由"))
        click("comparisonNotesManagement")
        click("deleteComparisonNotes")
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); drain()
        assertEquals(2, app.repository.historicalComparisons(run.id).size)
        click("deleteComparisonNotes")
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain()
        assertTrue(text().contains("还没有独立对照说明"))
        assertTrue(app.repository.historicalComparisons(run.id).isEmpty())
        assertNotNull(app.repository.claimFeedback(priorFeedback))
        assertEquals(before, requests)
    }

    @Test fun `a changed set of notes cannot be deleted from an older page`() {
        val (run, priorFeedback) = seed()
        open(); click("comparisonRun:${run.id}")
        app.io.submit {
            app.reflectionWorkflow.resolveHistoricalFeedback(run.id, run.findings.single().target, priorFeedback,
                app.reflectionWorkflow.inspect(run.id).taskVersion, HistoricalFeedbackChoice.RESPECTED,
                "合成第三次独立理由，旧页面不能一起删掉", expectedResolutionVersion = 2)
        }.get(20, TimeUnit.SECONDS)
        click("comparisonNotesManagement"); click("deleteComparisonNotes")
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain()
        val failure = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(failure.findViewById<TextView>(android.R.id.message).text.toString().contains("变化"))
        assertEquals(3, app.repository.historicalComparisons(run.id).size)
        assertNotNull(app.repository.analysisRun(run.id))
    }

    private fun seed(): Pair<ReflectionRun, String> = app.io.submit<Pair<ReflectionRun, String>> {
        app.repository.createRecord(RecordDraft("合成上期：任务容易，不能证明方法的效果。", Occurrence(today.minusDays(14)), setOf("学习")), "old-source")
        app.repository.createRecord(RecordDraft("合成本期：我尝试了一次新的练习。", Occurrence(today), setOf("学习")), "new-source")
        val old = app.reflectionWorkflow.process(app.reflectionWorkflow.start(AnalysisQuery(today.minusDays(14), today.minusDays(14), "Asia/Shanghai")).id, provider).run
        val rejected = app.reflectionWorkflow.respond(old.id, old.findings.single().target, old.taskVersion,
            ClaimFeedbackDecision.REJECTED, userText = "合成独立用户反馈：不能确定方法效果")
        val feedbackId = rejected.findings.single().feedback!!.feedback.id
        val current = app.reflectionWorkflow.process(app.reflectionWorkflow.start(AnalysisQuery(today, today, "Asia/Shanghai")).id, provider).run
        val first = app.reflectionWorkflow.resolveHistoricalFeedback(current.id, current.findings.single().target,
            feedbackId, current.taskVersion, HistoricalFeedbackChoice.RESPECTED, "合成第一次独立理由")
        app.reflectionWorkflow.resolveHistoricalFeedback(current.id, current.findings.single().target,
            feedbackId, first.taskVersion, HistoricalFeedbackChoice.CONFLICT, "合成第二次独立理由", expectedResolutionVersion = 1)
        current to feedbackId
    }.get(20, TimeUnit.SECONDS)

    private fun open() { controller = Robolectric.buildActivity(FeedbackNotesActivity::class.java).setup(); drain() }
    private fun click(tag: String) { assertTrue(activity.window.decorView.findViewWithTag<View>(tag).performClick()); drain() }
    private fun text() = descendants(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.joinToString("\n") { it.text.toString() }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun drain() { repeat(4) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() } }
}
