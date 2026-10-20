package dev.tracelife.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.TextView
import dev.tracelife.TraceLifeApplication
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.StoredKnowledgeRevision
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
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Synthetic publications through real analysis, feedback and SQLite; no network or key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KnowledgeActivityTest {
    private lateinit var app: TraceLifeApplication
    private var controller: ActivityController<KnowledgeActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val date = LocalDate.parse("2026-10-20")
    private val source = "合成原文🌱：我把练习分成三个小步骤，完成后比较容易继续。"

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        app.io.submit { app.repository.createRecord(RecordDraft(source, Occurrence(date)), "source") }.get(20, TimeUnit.SECONDS)
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `saved findings show wording first and experience and observations remain reachable with original evidence`() {
        val insight = publish(KnowledgeCategory.INSIGHT, "合成发现：这次把练习拆小可能降低了开始的阻力。")
        val note = publish(KnowledgeCategory.KNOWLEDGE_NOTE, "合成经验：把学习练习分成三个可以分别完成的步骤。")
        val observation = publish(KnowledgeCategory.OBSERVATION, "合成观察：用户完成了三个小步骤。")
        launch()

        assertTrue(text().contains(insight.claim.text))
        assertFalse(text().contains(observation.claim.text))
        assertFalse(text().contains("未分类"))
        assertTrue(text().contains("当前条目 3 条"))
        selectCategory("经验与知识")
        assertTrue(text().contains(note.claim.text))
        selectCategory("记录观察")
        assertTrue(text().contains(observation.claim.text))
        click("knowledge:${observation.id}")
        click("knowledgeEvidence")
        assertFalse(text().contains(source))
        click("readEvidence:${observation.evidence.single().id}")
        assertTrue(text().contains(source))
    }

    @Test fun `source edits expose pending entries and prevent displaying their old evidence as current`() {
        val insight = publish(KnowledgeCategory.INSIGHT, "合成发现：拆小步骤可能帮助我开始。")
        app.io.submit {
            app.repository.updateRecord("source", 1, RecordDraft("合成修订：还有其他原因，原来的判断需要核对。", Occurrence(date)))
        }.get(20, TimeUnit.SECONDS)
        launch()

        assertTrue(text().contains("待更新或过期 1 条"))
        assertFalse(text().contains(insight.claim.text))
        click("showPendingKnowledge")
        assertTrue(text().contains(insight.claim.text))
        assertTrue(text().contains("来源变化 · 待更新"))
        click("knowledge:${insight.id}")
        assertTrue(text().contains("暂不作为当前结论"))
        click("knowledgeEvidence")
        click("readEvidence:${insight.evidence.single().id}")
        assertFalse(text().contains(source))
        assertTrue(text().contains("请重新分析后查看当前原文"))
    }

    @Test fun `empty library provides a direct entry to analysis without presenting saved records as insights`() {
        launch()
        assertTrue(text().contains("这里还没有已保存的发现"))
        click("analyzeRecords")
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(ReflectionActivity::class.java.name, intent.component?.className)
    }

    @Test fun `source removal after opening detail prevents evidence retrieval`() {
        val insight = publish(KnowledgeCategory.INSIGHT, "合成发现：这次尝试仍需要观察。")
        launch()
        click("knowledge:${insight.id}")
        click("knowledgeEvidence")
        app.io.submit { app.repository.deleteRecord("source", 1) }.get(20, TimeUnit.SECONDS)
        click("readEvidence:${insight.evidence.single().id}")
        assertFalse(text().contains(source))
        assertTrue(text().contains("依据已发生变化、过期或不可用"))
    }

    private fun launch() {
        controller = Robolectric.buildActivity(KnowledgeActivity::class.java).setup()
        drain()
    }

    private fun publish(category: KnowledgeCategory, wording: String): StoredKnowledgeRevision = app.io.submit<StoredKnowledgeRevision> {
        val runId = "synthetic-${category.name.lowercase()}"
        val kind = if (category == KnowledgeCategory.INSIGHT) ClaimKind.POSSIBLE_INTERPRETATION else ClaimKind.REPORTED_EXPERIENCE
        app.analysis.start(AnalysisRequest("合成记录的整理", AnalysisQuery(date, date, "Asia/Shanghai")), runId)
        app.analysis.runBatch(runId, "extract", listOf(EvidenceSelection("source", "body")), object : AnalysisProvider {
            override val id = "synthetic-extractor"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
            override fun complete(request: ModelRequest): BatchProposal {
                val evidenceId = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim(wording, listOf(evidenceId), kind)))
            }
        })
        val claim = app.analysis.claimRef(runId, "extract", 0)
        if (category == KnowledgeCategory.INSIGHT) app.analysisFeedback.respond(runId, claim,
            app.analysis.task(runId).stored.version, ClaimFeedbackDecision.CONFIRMED)
        app.analysis.reviewClaim(runId, "review", claim, provider = object : SemanticReviewProvider {
            override val id = "synthetic-reviewer"
            override val contextWindowTokens = 100_000
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, request.payloadJson.toByteArray().size)
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                val evidenceId = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(evidenceId)))
            }
        })
        app.knowledge.publish(runId, claim, "review", category, app.analysis.task(runId).stored.version)
    }.get(20, TimeUnit.SECONDS)

    private fun selectCategory(label: String) {
        val spinner = view<Spinner>("knowledgeCategory")
        spinner.setSelection((0 until spinner.count).single { spinner.getItemAtPosition(it) == label })
        drain()
    }

    private fun click(tag: String) {
        assertTrue("Missing action $tag", view<View>(tag).performClick())
        drain()
    }

    private fun <T : View> view(tag: String): T = requireNotNull(activity.window.decorView.findViewWithTag<T>(tag))

    private fun text() = descendants(activity.window.decorView).filterIsInstance<TextView>()
        .filter { it.isShown }.joinToString("\n") { it.text.toString() }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun drain() {
        repeat(4) {
            app.io.submit {}.get(20, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
