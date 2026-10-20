package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import dev.tracelife.TraceLifeApplication
import dev.tracelife.analysis.AnalysisProvider
import dev.tracelife.analysis.BatchProposal
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ModelRequest
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.analysis.ReflectionRun
import dev.tracelife.analysis.ReflectionTarget
import dev.tracelife.analysis.HistoricalFeedbackChoice
import dev.tracelife.analysis.SemanticReviewProvider
import dev.tracelife.analysis.PreparedModelRequest
import dev.tracelife.analysis.ReviewCompletion
import dev.tracelife.analysis.ClaimReview
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.MainActivity
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
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** UI journeys backed by real persisted synthetic analysis. No real provider or key is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReflectionActivityTest {
    private lateinit var app: TraceLifeApplication
    private var controller: ActivityController<ReflectionActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val today get() = LocalDate.now()
    private val scope get() = AnalysisQuery(today.minusDays(6), today, ZoneId.systemDefault().id)
    private val provider = SyntheticProvider()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        app.io.submit {}.get(20, TimeUnit.SECONDS)
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `entry offers a direct analysis action and restores custom dates without creating a task`() {
        open()
        assertEquals(1, view<Spinner>("reflectionRange").selectedItemPosition)
        assertEquals("开始分析", view<TextView>("startReflection").text.toString())
        assertTrue(texts().any { it.contains("还没有分析过") })
        view<Spinner>("reflectionRange").setSelection(3)
        drain()
        view<EditText>("reflectionFrom").setText("2026-10-01")
        view<EditText>("reflectionThrough").setText("2026-10-09")
        requireNotNull(controller).recreate()
        drain()
        assertEquals("2026-10-01", view<EditText>("reflectionFrom").text.toString())
        assertEquals("2026-10-09", view<EditText>("reflectionThrough").text.toString())
        assertEquals(3, view<Spinner>("reflectionRange").selectedItemPosition)
        click("startReflection")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.findViewById<TextView>(android.R.id.message).text.contains("DeepSeek"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        drain()
        assertTrue(app.reflectionWorkflow.history().isEmpty())
        assertEquals(0, provider.calls)
    }

    @Test fun `record navigation can prefill a period without silently starting analysis`() {
        open(Intent(app, ReflectionActivity::class.java)
            .putExtra(ReflectionActivity.EXTRA_FROM, "2026-10-02")
            .putExtra(ReflectionActivity.EXTRA_THROUGH, "2026-10-04"))
        assertEquals(3, view<Spinner>("reflectionRange").selectedItemPosition)
        assertEquals("2026-10-02", view<EditText>("reflectionFrom").text.toString())
        assertEquals("2026-10-04", view<EditText>("reflectionThrough").text.toString())
        assertTrue(app.reflectionWorkflow.history().isEmpty())
    }

    @Test fun `findings expose categories original evidence and persisted rejection across recreation`() {
        val run = seedAnalyzedRun()
        val calls = provider.calls
        open(Intent(app, ReflectionActivity::class.java).putExtra(ReflectionActivity.EXTRA_RUN_ID, run.id))
        assertTrue(texts().containsAll(listOf("值得留下的经验", "值得关注", "可以尝试的改进")))
        val findingTag = descendants(activity.window.decorView).mapNotNull { it.tag as? String }
            .first { it.startsWith("reflectionFinding:") }
        click(findingTag)
        assertTrue(texts().any { it == "待核对" })
        click("reflectionEvidence")
        val evidenceTag = descendants(activity.window.decorView).mapNotNull { it.tag as? String }
            .first { it.startsWith("reflectionOriginal:") }
        click(evidenceTag)
        assertTrue(texts().any { it.contains("合成资料：先做练习，再查资料") })
        requireNotNull(controller).recreate()
        drain()
        assertTrue(texts().any { it.contains("值得留下：") })
        assertNotNull(activity.window.decorView.findViewWithTag<View>("keepReflectionFinding"))
        click("rejectReflectionFinding")
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain()
        assertTrue(texts().any { it == "已否定" })
        assertNull(activity.window.decorView.findViewWithTag<View>("keepReflectionFinding"))
        requireNotNull(controller).recreate()
        drain()
        assertTrue(texts().any { it == "已否定" })
        assertEquals(ClaimFeedbackDecision.REJECTED,
            app.reflectionWorkflow.inspect(run.id).findings.first().feedback?.feedback?.decision)
        assertEquals(calls, provider.calls)
        assertEquals(calls, app.analysis.task(run.id).state.calls.size)
    }

    @Test fun `history survives another activity instance and source changes are visible on return`() {
        val run = seedAnalyzedRun()
        open()
        assertNotNull(activity.window.decorView.findViewWithTag<View>("reflectionRun:${run.id}"))
        click("reflectionRun:${run.id}")
        requireNotNull(controller).pause().stop()
        app.io.submit { app.repository.createRecord(RecordDraft("合成资料：新补充经历", Occurrence(today))) }.get(20, TimeUnit.SECONDS)
        requireNotNull(controller).start().resume().visible()
        drain()
        assertTrue(texts().any { it == "来源已变化" })
        val tag = descendants(activity.window.decorView).mapNotNull { it.tag as? String }
            .first { it.startsWith("reflectionFinding:") }
        click(tag)
        assertNull(activity.window.decorView.findViewWithTag<View>("keepReflectionFinding"))
        assertTrue(texts().any { it.contains("不能继续保存") })
    }

    @Test fun `empty available text is visible and does not pretend analysis found personal problems`() {
        val run = app.io.submit<ReflectionRun> { app.reflectionWorkflow.start(scope) }.get(20, TimeUnit.SECONDS)
        open(Intent(app, ReflectionActivity::class.java).putExtra(ReflectionActivity.EXTRA_RUN_ID, run.id))
        assertTrue(texts().any { it == "没有可分析的文字" })
        assertTrue(texts().any { it.contains("没有可读取的文字") })
        assertNull(activity.window.decorView.findViewWithTag<View>("continueReflection"))
        assertEquals(0, app.analysis.task(run.id).state.calls.size)
    }

    @Test fun `partial batch findings cannot be confirmed saved or rejected before integration`() {
        val run = app.io.submit<ReflectionRun> {
            repeat(5) { index ->
                app.repository.createRecord(RecordDraft("合成资料：第 $index 次练习的情况", Occurrence(today)))
            }
            val created = app.reflectionWorkflow.start(scope)
            app.reflectionWorkflow.process(created.id, provider, maxRequests = 1).run
        }.get(20, TimeUnit.SECONDS)
        assertFalse(run.canReviewFindings)
        open(Intent(app, ReflectionActivity::class.java).putExtra(ReflectionActivity.EXTRA_RUN_ID, run.id))
        val tag = descendants(activity.window.decorView).mapNotNull { it.tag as? String }
            .first { it.startsWith("reflectionFinding:") }
        click(tag)
        assertTrue(texts().any { it.contains("部分记录中的发现") })
        assertNull(activity.window.decorView.findViewWithTag<View>("keepReflectionFinding"))
        assertNull(activity.window.decorView.findViewWithTag<View>("rejectReflectionFinding"))
        assertNotNull(activity.window.decorView.findViewWithTag<View>("reflectionEvidence"))
        assertEquals(1, provider.calls)
    }

    @Test fun `older analysis pages remain reachable after recreation without hiding history`() {
        val runs = app.io.submit<List<ReflectionRun>> {
            List(21) { app.reflectionWorkflow.start(scope) }
        }.get(20, TimeUnit.SECONDS)
        open()
        fun visibleRunTags() = descendants(activity.window.decorView).mapNotNull { it.tag as? String }
            .filter { it.startsWith("reflectionRun:") }.toSet()
        val newest = visibleRunTags()
        assertEquals(20, newest.size)
        click("reflectionHistoryNext")
        val earlier = visibleRunTags()
        assertEquals(1, earlier.size)
        assertTrue(newest.intersect(earlier).isEmpty())
        assertEquals(runs.map { "reflectionRun:${it.id}" }.toSet(), newest + earlier)
        requireNotNull(controller).recreate()
        drain()
        assertEquals(earlier, visibleRunTags())
        click("reflectionHistoryPrevious")
        assertEquals(newest, visibleRunTags())
        assertEquals(0, provider.calls)
    }

    @Test fun `source changes retain saved finding navigation and the home saved count`() {
        val run = seedAnalyzedRun()
        val knowledge = app.io.submit<dev.tracelife.storage.StoredKnowledgeRevision> {
            val finding = run.findings.first()
            app.reflectionWorkflow.respond(run.id, finding.target, run.taskVersion, ClaimFeedbackDecision.CONFIRMED)
            val claim = (finding.target as ReflectionTarget.Claim).ref
            app.analysis.reviewClaim(run.id, "synthetic-review", claim, provider = object : SemanticReviewProvider {
                override val id = "synthetic-reviewer"
                override val contextWindowTokens = 100_000
                override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
                override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                    val evidenceId = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                    return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(evidenceId)))
                }
            })
            val saved = app.knowledge.publish(run.id, claim, "synthetic-review", KnowledgeCategory.INSIGHT,
                app.analysis.task(run.id).stored.version)
            val original = app.repository.listRecords().items.single()
            app.repository.updateRecord(original.id, original.revision, original.draft.copy(body = "合成更正：这次成功还有其他原因"))
            saved
        }.get(20, TimeUnit.SECONDS)
        open(Intent(app, ReflectionActivity::class.java).putExtra(ReflectionActivity.EXTRA_RUN_ID, run.id))
        assertTrue(texts().contains("已保存 · 待更新"))
        val findingTag = descendants(activity.window.decorView).mapNotNull { it.tag as? String }.first { it.startsWith("reflectionFinding:") }
        click(findingTag)
        click("openSavedFinding")
        assertEquals(knowledge.id, shadowOf(activity).nextStartedActivity.getStringExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_ID))
        assertNull(activity.window.decorView.findViewWithTag<View>("keepReflectionFinding"))
        val home = Robolectric.buildActivity(MainActivity::class.java).setup()
        drain()
        val wording = descendants(home.get().window.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertTrue(wording.contains("1 项已保存"))
        assertTrue(wording.contains("其中 1 项待更新"))
        home.pause().stop().destroy()
    }

    @Test fun `archive and restore keep findings readable and never dispatch a provider`() {
        val run = seedAnalyzedRun()
        val calls = provider.calls
        open(Intent(app, ReflectionActivity::class.java).putExtra(ReflectionActivity.EXTRA_RUN_ID, run.id))
        click("archiveReflection")
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain()
        assertTrue(texts().any { it.startsWith("已归档 ·") })
        assertNotNull(app.reflectionWorkflow.inspect(run.id).archivedAt)
        requireNotNull(controller).recreate()
        drain()
        assertNotNull(activity.window.decorView.findViewWithTag<View>("unarchiveReflection"))
        click("unarchiveReflection")
        assertNull(app.reflectionWorkflow.inspect(run.id).archivedAt)
        assertEquals(calls, provider.calls)
        assertEquals(run.findings.map { it.text }, app.reflectionWorkflow.inspect(run.id).findings.map { it.text })
    }

    @Test fun `correction appears in next analysis and conflicts cannot expose save action`() {
        val old = seedAnalyzedRun()
        open(Intent(app, ReflectionActivity::class.java).putExtra(ReflectionActivity.EXTRA_RUN_ID, old.id))
        val oldTarget = old.findings.first().target as ReflectionTarget.Claim
        click("reflectionFinding:claim:${oldTarget.ref.batchKey}:${oldTarget.ref.claimIndex}:${oldTarget.ref.claimSha256}")
        click("correctReflectionFinding")
        val correctionDialog = ShadowAlertDialog.getLatestAlertDialog()
        correctionDialog.window!!.decorView.findViewWithTag<EditText>("reflectionCorrection").setText("合成纠正：任务更简单也可能解释启动更快，不能一概而论。")
        correctionDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain()
        val corrected = app.reflectionWorkflow.inspect(old.id).findings.first()
        assertEquals(ClaimFeedbackDecision.CORRECTED, corrected.feedback!!.feedback.decision)
        controller?.pause()?.stop()?.destroy(); controller = null
        val next = app.io.submit<ReflectionRun> {
            val created = app.reflectionWorkflow.start(scope)
            app.reflectionWorkflow.process(created.id, provider).run
        }.get(20, TimeUnit.SECONDS)
        open(Intent(app, ReflectionActivity::class.java).putExtra(ReflectionActivity.EXTRA_RUN_ID, next.id))
        val target = next.findings.first().target as ReflectionTarget.Claim
        click("reflectionFinding:claim:${target.ref.batchKey}:${target.ref.claimIndex}:${target.ref.claimSha256}")
        assertNull(activity.window.decorView.findViewWithTag<View>("keepReflectionFinding"))
        click("historicalFeedback")
        assertTrue(texts().any { it.contains("任务更简单") })
        val item = app.reflectionWorkflow.historicalFeedbackPage(next.id, target).items.single()
        click("resolveHistory:${item.content.reference.id}")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.window!!.decorView.findViewWithTag<Spinner>("historicalFeedbackChoice").setSelection(HistoricalFeedbackChoice.CONFLICT.ordinal)
        dialog.window!!.decorView.findViewWithTag<EditText>("historicalFeedbackReason").setText("合成对照：新判断仍然缺少任务难度的限制。")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain()
        assertTrue(texts().any { it.contains("仍与我的意见冲突") })
        click("historicalFeedbackDone")
        assertNull(activity.window.decorView.findViewWithTag<View>("keepReflectionFinding"))
        assertEquals(1, app.reflectionWorkflow.inspect(next.id).findings.first().historicalFeedback.conflicts)
    }

    private fun seedAnalyzedRun(): ReflectionRun = app.io.submit<ReflectionRun> {
        app.repository.createRecord(RecordDraft("合成资料：先做练习，再查资料，有一项问题还没解决。", Occurrence(today)))
        val run = app.reflectionWorkflow.start(scope)
        app.reflectionWorkflow.process(run.id, provider).run
    }.get(20, TimeUnit.SECONDS)

    private fun open(intent: Intent? = null) {
        controller = if (intent == null) Robolectric.buildActivity(ReflectionActivity::class.java).setup()
        else Robolectric.buildActivity(ReflectionActivity::class.java, intent).setup()
        drain()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun texts(): List<String> = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun click(tag: String) { assertTrue(view<View>(tag).performClick()); drain() }
    private fun <T : View> view(tag: String): T = requireNotNull(activity.window.decorView.findViewWithTag<T>(tag)) { "Missing view $tag" }
    private fun drain() {
        repeat(4) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() }
    }

    private class SyntheticProvider : AnalysisProvider {
        override val id = "synthetic-reflection-ui"
        override val contextWindowTokens = 128_000
        var calls = 0
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            calls++
            val sources = JSONObject(request.payloadJson).getJSONArray("evidence")
            val evidence = (0 until sources.length()).map { sources.getJSONObject(it).getString("id") }
            return BatchProposal(listOf(
                ProposedClaim("值得留下：合成资料中先做练习再查资料的做法值得回看。", evidence, ClaimKind.POSSIBLE_INTERPRETATION),
                ProposedClaim("值得关注：合成资料中还有尚未解决的具体问题。", evidence, ClaimKind.POSSIBLE_INTERPRETATION),
                ProposedClaim("可以尝试：合成资料可补记一次练习后的实际结果。", evidence, ClaimKind.POSSIBLE_INTERPRETATION),
            ))
        }
    }
}
