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
class HistoricalFeedbackAssistActivityTest {
    private lateinit var app: TraceLifeApplication
    private var controller: ActivityController<HistoricalFeedbackAssistActivity>? = null
    private val activity get() = controller!!.get()
    private val provider = SyntheticProvider()
    @Before fun setUp() { app = RuntimeEnvironment.getApplication() as TraceLifeApplication; app.io.submit {}.get(20, TimeUnit.SECONDS) }
    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `page confirmation affects only visible safe suggestions and rotating does not dispatch or confirm the next page`() {
        val run = seed(9)
        open(run)
        assertTrue(texts().any { it.contains("已辅助比较 9 / 9 条") })
        click("historyAssistAcceptPage")
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain()
        assertEquals(8, app.analysis.task(run.id).state.historicalFeedbackResolutions.size)
        click("historyAssistNext")
        controller!!.recreate(); drain()
        assertTrue(texts().any { it == "确认本页 1 条无冲突建议" })
        assertEquals(2, provider.comparisonCalls)
        assertEquals(8, app.analysis.task(run.id).state.historicalFeedbackResolutions.size)
    }

    @Test fun `conflict is highlighted separately and cannot be accepted by the page action`() {
        val run = seed(2, conflict = true)
        open(run)
        assertTrue(texts().any { it == "需要处理：仍存在冲突" })
        assertTrue(texts().any { it == "确认本页 1 条无冲突建议" })
        click("historyAssistFilter")
        assertNull(activity.window.decorView.findViewWithTag<View>("historyAssistAcceptPage"))
        assertTrue(texts().any { it.contains("合成比较：依旧存在同义冲突") })
        controller!!.recreate(); drain()
        assertNull(activity.window.decorView.findViewWithTag<View>("historyAssistAcceptPage"))
        assertTrue(app.analysis.task(run.id).state.historicalFeedbackResolutions.isEmpty())
        assertEquals(1, provider.comparisonCalls)
    }

    private fun seed(count: Int, conflict: Boolean = false): ReflectionRun = app.io.submit<ReflectionRun> {
        val today = LocalDate.now()
        val old = today.minusDays(14)
        app.repository.createRecord(RecordDraft("合成上期学习记录", Occurrence(old), setOf("学习")), "assist-old")
        app.analysis.start(AnalysisRequest("合成上期", AnalysisQuery(old, old, "Asia/Shanghai"), focusTopics = setOf("学习")), "assist-old-run")
        provider.count = count
        app.analysis.synthesize("assist-old-run", "old", listOf(EvidenceSelection("assist-old", "body")), provider)
        repeat(count) {
            app.analysisFeedback.respond("assist-old-run", app.analysis.claimRef("assist-old-run", "old", it),
                app.analysis.task("assist-old-run").stored.version, ClaimFeedbackDecision.CORRECTED, "合成个人纠正 $it：只是一种尝试。")
        }
        app.repository.createRecord(RecordDraft("合成本期学习记录", Occurrence(today), setOf("学习")), "assist-new")
        provider.count = 1
        val run = app.reflectionWorkflow.process(app.reflectionWorkflow.start(AnalysisQuery(today, today, "Asia/Shanghai")).id, provider).run
        if (conflict) provider.conflictId = app.analysis.task(run.id).state.feedbackContext!!.references.first().id
        HistoricalFeedbackAssist(app.analysis).process(run.id, (run.findings.single().target as ReflectionTarget.Claim).ref, provider)
        app.reflectionWorkflow.inspect(run.id)
    }.get(20, TimeUnit.SECONDS)

    private fun open(run: ReflectionRun) {
        controller = Robolectric.buildActivity(HistoricalFeedbackAssistActivity::class.java,
            HistoricalFeedbackAssistActivity.intent(app, run.id, run.findings.single().target)).setup()
        drain()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun texts() = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun click(tag: String) { assertTrue(requireNotNull(activity.window.decorView.findViewWithTag<View>(tag)).performClick()); drain() }
    private fun drain() { repeat(4) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() } }

    private class SyntheticProvider : AnalysisProvider, HistoricalFeedbackScanProvider {
        override val id = "synthetic-history-assist-ui"
        override val contextWindowTokens = 100_000
        var count = 1
        var conflictId: String? = null
        var comparisonCalls = 0
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            val evidence = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return BatchProposal(List(count) { ProposedClaim("合成待验证描述 $it", listOf(evidence), ClaimKind.POSSIBLE_INTERPRETATION) })
        }
        override fun prepareHistoricalFeedbackScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun scanHistoricalFeedbackPrepared(request: PreparedModelRequest): HistoricalFeedbackScanCompletion {
            comparisonCalls++
            val values = JSONObject(request.original.payloadJson).getJSONArray("historicalComparisons")
            return HistoricalFeedbackScanCompletion(HistoricalFeedbackScan((0 until values.length()).map {
                val id = FeedbackContextProtocol.feedbackIdFromControlKey(values.getJSONObject(it).getString("controlKey"))
                HistoricalFeedbackSuggestion(id, if (id == conflictId) HistoricalFeedbackAssessment.CONFLICT else HistoricalFeedbackAssessment.ALIGNED,
                    if (id == conflictId) "合成比较：依旧存在同义冲突" else "合成比较：已保留限制条件")
            }))
        }
    }
}
