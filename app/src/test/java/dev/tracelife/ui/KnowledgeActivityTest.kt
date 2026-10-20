package dev.tracelife.ui

import android.os.Looper
import android.widget.EditText
import dev.tracelife.MainActivity
import dev.tracelife.actions.*
import dev.tracelife.knowledge.*
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
        app.io.submit { app.repository.createRecord(RecordDraft(source, Occurrence(date), setOf("学习")), "source") }.get(20, TimeUnit.SECONDS)
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

    @Test fun `reusable interpretations appear in experience shelf without changing evidence category`() {
        val experience = publish(KnowledgeCategory.INSIGHT, "值得留下：合成资料中，把练习缩小再开始可能适合简单任务。")
        val other = publish(KnowledgeCategory.INSIGHT, "值得关注：这次资料还不足以说明原因。")
        launch()
        selectCategory("经验与知识")
        assertTrue(text().contains(experience.claim.text))
        assertFalse(text().contains(other.claim.text))
        assertEquals(KnowledgeCategory.INSIGHT, app.knowledge.get(experience.id)!!.value.category)
        assertEquals(ClaimKind.POSSIBLE_INTERPRETATION, app.knowledge.get(experience.id)!!.value.claim.kind)
    }

    @Test fun `topic connects saved understanding to exact original records and survives recreation`() {
        val saved = publish(KnowledgeCategory.INSIGHT, "值得留下：合成资料中，先做一个可完成的步骤。")
        launch()
        click("knowledgeTopic:学习")
        assertTrue(text().contains("1 条经历 · 1 条已保存"))
        assertTrue(text().contains(source))
        click("topicRecord:source")
        val original = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, original.component!!.className)
        assertEquals("source", original.getStringExtra(MainActivity.EXTRA_RECORD_ID))
        requireNotNull(controller).recreate()
        drain()
        assertNotNull(activity.window.decorView.findViewWithTag<View>("topicRecord:source"))
        click("knowledge:${saved.id}")
        click("knowledgeEvidence")
        click("openEvidenceRecord:source")
        assertEquals(1, shadowOf(activity).nextStartedActivity.getIntExtra(MainActivity.EXTRA_RECORD_REVISION, -1))
    }

    @Test fun `adopted action keeps true origin and returns its review to the saved knowledge`() {
        val saved = publish(KnowledgeCategory.INSIGHT, "值得留下：合成资料中，先做一个可完成的步骤。")
        launch()
        click("knowledge:${saved.id}")
        click("actionFromKnowledge")
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(saved.id, intent.getStringExtra(ActionsActivity.EXTRA_ORIGIN_ID))
        val actionController = Robolectric.buildActivity(ActionsActivity::class.java, intent).setup()
        drain()
        fun field(label: String): EditText = descendants(actionController.get().window.decorView)
            .filterIsInstance<TextView>().filter { it !is EditText && it.text.toString() == label }
            .mapNotNull { text -> (text.parent as? ViewGroup)?.let { it.getChildAt(it.indexOfChild(text) + 1) as? EditText } }.single()
        field("想尝试什么").setText("合成：先做最小练习")
        field("什么时候做").setText("下次开始学习时")
        field("准备怎么做").setText("先写一个可运行的步骤")
        field("怎样看出效果").setText("记下是否动手和任务难度")
        field("回看日期").setText(LocalDate.now().plusDays(7).toString())
        actionController.recreate()
        drain()
        assertEquals("先写一个可运行的步骤", field("准备怎么做").text.toString())
        actionController.get().window.decorView.findViewWithTag<View>("saveAction").performClick()
        drain()
        val action = requireNotNull(app.actions.get(app.actions.list().items.single().id))
        assertEquals(ActionOrigin(saved.id, saved.revision), action.value.draft.origin)
        app.io.submit {
            app.actions.review(action.value.id, 1, "synthetic-review", ActionReview(ActionExecution.EXECUTED,
                "合成回看：实际完成了一个小练习，但任务比较容易", ActionDecision.CONTINUE, LocalDate.now()),
                nextReviewOn = LocalDate.now().plusDays(14))
        }.get(20, TimeUnit.SECONDS)
        actionController.pause().stop().destroy()
        requireNotNull(controller).pause().stop().start().resume().visible()
        drain()
        assertTrue(text().contains("实际完成了一个小练习"))
        click("knowledgeAction:${action.value.id}")
        assertEquals(action.value.id, shadowOf(activity).nextStartedActivity.getStringExtra(ActionsActivity.EXTRA_ACTION_ID))
    }

    @Test fun `experience pagination counts only matching statements and rejects changed page snapshots`() {
        val retained = publish(KnowledgeCategory.INSIGHT, "值得留下：合成做法甲。")
        publish(KnowledgeCategory.INSIGHT, "值得关注：不是经验的合成观察。")
        val second = publish(KnowledgeCategory.KNOWLEDGE_NOTE, "合成做法乙。")
        val journey = KnowledgeJourney(app.repository)
        val first = journey.page(KnowledgeFilter(includeInvalid = true), KnowledgeShelf.EXPERIENCE, limit = 1)
        val next = journey.page(first.filter, first.shelf, first.nextOffset!!, first.limit, first)
        assertEquals(2, first.total)
        assertEquals(setOf(retained.id, second.id), (first.entries + next.entries).map { it.value.id }.toSet())
        assertNull(next.nextOffset)
        app.repository.updateRecord("source", 1, RecordDraft("合成修订", Occurrence(date), setOf("学习")))
        assertThrows(dev.tracelife.transfer.StaleSnapshotException::class.java) {
            journey.page(first.filter, first.shelf, first.nextOffset!!, first.limit, first)
        }
    }

    private fun launch() {
        controller = Robolectric.buildActivity(KnowledgeActivity::class.java).setup()
        drain()
    }

    private fun publish(category: KnowledgeCategory, wording: String): StoredKnowledgeRevision = app.io.submit<StoredKnowledgeRevision> {
        val runId = "synthetic-${category.name.lowercase()}-${java.util.UUID.randomUUID()}"
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
        app.knowledge.publish(runId, claim, "review", category, app.analysis.task(runId).stored.version, topics = setOf("学习"))
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
