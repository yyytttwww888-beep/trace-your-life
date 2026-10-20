package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.LifeRepository
import dev.tracelife.transfer.StaleSnapshotException
import dev.tracelife.transfer.PublishedKnowledgeExport
import dev.tracelife.transfer.ReadingExportOptions
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.util.UUID

/** Synthetic follow-up chains; no network, credentials, or personal records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationContextTest {
    private val database = "synthetic-conversation-context-${UUID.randomUUID()}.db"
    private lateinit var android: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private val day = LocalDate.parse("2026-10-20")
    private val before = AnalysisQuery(day.minusMonths(3), day.minusDays(1), "Asia/Shanghai")
    private val today = AnalysisQuery(day, day, "Asia/Shanghai")
    private val provider = SyntheticProvider()

    @Before fun setup() {
        android = RuntimeEnvironment.getApplication()
        repository = LifeRepository(android, databaseName = database)
        analysis = ControlledAnalysis(repository)
        repository.createRecord(RecordDraft("合成：过去采用先阅读再练习的方法。", Occurrence(day.minusDays(10)), setOf("学习")), "past")
        repository.createRecord(RecordDraft("合成：今天先练习，再查缺失的知识。", Occurrence(day), setOf("学习")), "current")
    }
    @After fun close() { repository.close(); android.deleteDatabase(database) }

    @Test fun `selected answer framing survives restart and retains legacy envelopes`() {
        generate("parent", before, "past", claimCount = 2)
        val context = context("parent", 1)
        generate("child", today, "current", context)
        val payload = JSONObject(provider.requests.last().payloadJson)
        val previous = payload.getJSONObject("conversationContext")
        assertFalse(previous.getBoolean("originalEvidence"))
        assertEquals(1, previous.getJSONArray("selectedAnswers").length())
        assertEquals("合成判断 1", previous.getJSONArray("selectedAnswers").getJSONObject(0).getString("text"))
        assertFalse(previous.toString().contains("合成判断 0"))
        assertEquals(14, JSONObject(analysis.task("child").stored.payload).getInt("formatVersion"))
        assertEquals(12, JSONObject(analysis.task("parent").stored.payload).getInt("formatVersion"))
        assertFalse(JSONObject(analysis.task("parent").stored.payload).getJSONObject("request").has("conversationContext"))
        repository.close()
        repository = LifeRepository(android, databaseName = database)
        analysis = ControlledAnalysis(repository)
        assertEquals(context, analysis.task("child").state.request.conversationContext)
        assertEquals(AnalysisStatus.NEEDS_REVIEW, analysis.task("child").status)
    }

    @Test fun `context bytes are included before any model request can run`() {
        generate("parent", before, "past")
        val context = context("parent")
        analysis.start(AnalysisRequest("合成追问", today, AnalysisLimits(maxInputBytes = 512), conversationContext = context), "child")
        val count = provider.requests.size
        assertThrows(AnalysisBudgetException::class.java) { analysis.synthesize("child", "answer", listOf(EvidenceSelection("current", "body")), provider) }
        assertEquals(count, provider.requests.size)
        assertTrue(analysis.task("child").state.calls.isEmpty())
    }

    @Test fun `derived context cannot be cited as original evidence`() {
        generate("parent", before, "past")
        analysis.start(AnalysisRequest("合成追问", today, conversationContext = context("parent")), "child")
        provider.extraCitation = "previous-answer:1"
        assertThrows(AnalysisValidationException::class.java) { analysis.synthesize("child", "answer", listOf(EvidenceSelection("current", "body")), provider) }
        assertEquals(CallStatus.FAILED, analysis.task("child").state.calls.single().status)
    }

    @Test fun `changing an earlier source invalidates a later scope and saved knowledge`() {
        generate("parent", before, "past")
        generate("child", today, "current", context("parent"))
        val saved = publish("child")
        assertEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository).get(saved)!!.evidenceState)
        repository.updateRecord("past", 1, RecordDraft("合成：更正为过去没有进行练习。", Occurrence(day.minusDays(10)), setOf("学习")))
        assertEquals(AnalysisStatus.STALE, analysis.task("child").status)
        assertEquals(KnowledgeEvidenceState.FEEDBACK_CHANGED, KnowledgeLibrary(repository).get(saved)!!.evidenceState)
        assertThrows(StaleSnapshotException::class.java) { analysis.route("child") }
    }

    @Test fun `late result after parent correction is rejected rather than saved as success`() {
        generate("parent", before, "past")
        val context = context("parent")
        analysis.start(AnalysisRequest("合成追问", today, conversationContext = context), "child")
        provider.before = { correct("parent") }
        assertThrows(StaleSnapshotException::class.java) { analysis.synthesize("child", "answer", listOf(EvidenceSelection("current", "body")), provider) }
        assertTrue(analysis.task("child").state.calls.none { it.status == CallStatus.SUCCEEDED })
        assertEquals(AnalysisStatus.STALE, analysis.task("child").status)
    }

    @Test fun `parent correction propagates through followup ancestry`() {
        generate("parent", before, "past")
        generate("child", today, "current", context("parent"))
        generate("grandchild", today, "current", context("child"))
        correct("parent")
        assertEquals(AnalysisStatus.STALE, analysis.task("child").status)
        assertEquals(AnalysisStatus.STALE, analysis.task("grandchild").status)
        assertThrows(StaleSnapshotException::class.java) { context("parent") }
    }

    @Test fun `deleting parent clears descendant machine text and knowledge`() {
        generate("parent", before, "past")
        generate("child", today, "current", context("parent"))
        val saved = publish("child")
        generate("grandchild", today, "current", context("child"))
        analysis.delete("parent", analysis.task("parent").stored.version)
        assertNull(repository.analysisRun("parent"))
        assertNull(repository.analysisRun("child"))
        assertNull(repository.analysisRun("grandchild"))
        assertNull(repository.knowledgeRevision(saved))
    }

    @Test fun `deleting feedback clears every machine task that copied the affected answer`() {
        generate("parent", before, "past")
        val ref = analysis.claimRef("parent", "answer", 0)
        val feedback = AnalysisFeedback(repository).respond("parent", ref, analysis.task("parent").stored.version, ClaimFeedbackDecision.CONFIRMED)
        generate("child", today, "current", context("parent"))
        generate("grandchild", today, "current", context("child"))
        AnalysisFeedback(repository).delete(feedback.feedback.id, feedback.feedback.version)
        assertNull(repository.analysisRun("child"))
        assertNull(repository.analysisRun("grandchild"))
        assertNotNull(repository.analysisRun("parent"))
    }

    @Test fun `only eight previous tasks can be traversed without a fresh question`() {
        generate("turn0", before, "past")
        for (index in 1..8) generate("turn$index", today, "current", context("turn${index - 1}"))
        assertEquals(AnalysisStatus.NEEDS_REVIEW, analysis.task("turn8").status)
        assertThrows(StaleSnapshotException::class.java) { context("turn8") }
    }

    @Test fun `archive blocks new followups without invalidating already registered children or knowledge`() {
        generate("parent", before, "past")
        generate("child", today, "current", context("parent"))
        val saved = publish("child")
        val archived = repository.archiveAnalysisRun("parent", analysis.task("parent").stored.version)
        assertEquals(AnalysisStatus.NEEDS_REVIEW, analysis.task("child").status)
        assertEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository).get(saved)!!.evidenceState)
        assertThrows(StaleSnapshotException::class.java) { context("parent") }
        repository.unarchiveAnalysisRun("parent", archived.version)
        assertEquals(AnalysisStatus.NEEDS_REVIEW, analysis.task("child").status)
    }

    @Test fun `inventing selected text or reference is rejected even when caller recomputes the context hash`() {
        generate("parent", before, "past")
        val context = context("parent")
        val forged = ConversationContextProtocol.withHash(context.copy(targets = context.targets.map { it.copy(text = "合成伪造：一贯优秀") }))
        assertThrows(StaleSnapshotException::class.java) { analysis.start(AnalysisRequest("合成追问", today, conversationContext = forged), "forged") }
        assertNull(repository.analysisRun("forged"))
    }

    @Test fun `reduction followup preserves ancestor feedback fences`() {
        analysis.start(AnalysisRequest("合成跨期比较", before), "parent")
        analysis.synthesize("parent", "a", listOf(EvidenceSelection("past", "body", 0)), provider)
        // Distinct original slices form distinct leaves, both retained in the parent graph.
        analysis.synthesize("parent", "b", listOf(EvidenceSelection("past", "body", 5)), provider)
        val nodes = analysis.task("parent").state.calls.map(ReductionGraph::nodeRef)
        analysis.reduce("parent", "merged", nodes, provider)
        val target = ReflectionTarget.Reduction(analysis.reductionStatementRef("parent", analysis.reductionNode("parent", "merged").ref, 0))
        val context = ConversationContextCatalog(repository).plan("parent", listOf(target))
        assertTrue(context.targets.single().feedbackTokens.size > 1)
        generate("child", today, "current", context)
        val ref = analysis.claimRef("parent", "a", 0)
        AnalysisFeedback(repository).respond("parent", ref, analysis.task("parent").stored.version, ClaimFeedbackDecision.CORRECTED, "合成：原先理解不准确")
        assertEquals(AnalysisStatus.STALE, analysis.task("child").status)
    }

    @Test fun `reading export excludes a child when only the earlier parent source changed`() {
        generate("parent", before, "past")
        generate("child", today, "current", context("parent"))
        publish("child")
        val work = File(android.cacheDir, "synthetic-context-export-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            fun exported(directory: String): Int {
                val snapshot = repository.captureSnapshot(File(work, directory).apply { mkdirs() })
                val heads = snapshot.records.groupBy { it.id }.values.map { it.maxBy { value -> value.revision } }
                return PublishedKnowledgeExport.select(snapshot, heads, heads, ReadingExportOptions()).entries.size
            }
            assertEquals(1, exported("before"))
            repository.updateRecord("past", 1, RecordDraft("合成更正：没有发生练习。", Occurrence(day.minusDays(10)), setOf("学习")))
            assertEquals(0, exported("after"))
        } finally { work.deleteRecursively() }
    }

    @Test fun `restoring an old snapshot cannot resurrect answers derived from a deleted parent`() {
        generate("parent", before, "past")
        generate("child", today, "current", context("parent"))
        generate("grandchild", today, "current", context("child"))
        val work = File(android.cacheDir, "synthetic-context-restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val attachments = File(work, "attachments").apply { mkdirs() }
            val snapshot = repository.captureSnapshot(attachments)
            analysis.delete("parent", analysis.task("parent").stored.version)
            repository.restoreSnapshot(snapshot, attachments, repository.currentGeneration(), replaceExisting = true)
            assertNull(repository.analysisRun("parent"))
            assertNull(repository.analysisRun("child"))
            assertNull(repository.analysisRun("grandchild"))
        } finally { work.deleteRecursively() }
    }

    @Test fun `a new semantic review of the selected parent invalidates the frozen followup context`() {
        generate("parent", before, "past")
        generate("child", today, "current", context("parent"))
        analysis.reviewClaim("parent", "review", analysis.claimRef("parent", "answer", 0), provider = provider)
        assertEquals(AnalysisStatus.STALE, analysis.task("child").status)
    }

    @Test fun `changing selected parent historical comparisons invalidates its dependent followup`() {
        generate("older", before, "past")
        correct("older")
        val topics = FeedbackContextCatalog(repository).routeTopics(repository.analysisSourceStamp(), before)
        analysis.start(AnalysisRequest("合成：重新对照历史意见", before, focusTopics = topics, inheritHistoricalFeedback = true), "parent")
        analysis.synthesize("parent", "answer", listOf(EvidenceSelection("past", "body")), provider)
        val parentTarget = analysis.claimRef("parent", "answer", 0)
        val opinion = analysis.task("parent").state.feedbackContext!!.references.single()
        analysis.resolveHistoricalFeedback("parent", parentTarget, opinion.id, analysis.task("parent").stored.version,
            HistoricalFeedbackChoice.RESPECTED, "合成：只讨论方法变化，没有声称效果")
        generate("child", today, "current", context("parent"))
        analysis.resolveHistoricalFeedback("parent", parentTarget, opinion.id, analysis.task("parent").stored.version,
            HistoricalFeedbackChoice.CONFLICT, "合成：仍未保留历史限制", 1)
        assertEquals(AnalysisStatus.STALE, analysis.task("child").status)
        val work = File(android.cacheDir, "synthetic-stale-context-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val snapshot = repository.captureSnapshot(File(work, "attachments").apply { mkdirs() })
            assertEquals(3, snapshot.analysisRuns.size)
            assertFalse(ConversationContextProtocol.snapshotCurrent(analysis.task("child").state.request.conversationContext!!,
                snapshot.analysisRuns.associateBy { it.id }, snapshot.claimFeedback))
        } finally { work.deleteRecursively() }
    }

    private fun generate(id: String, scope: AnalysisQuery, source: String, context: ConversationContext? = null, claimCount: Int = 1) {
        provider.claimCount = claimCount
        analysis.start(AnalysisRequest("合成问题：学习方法有哪些变化", scope, conversationContext = context), id)
        analysis.synthesize(id, "answer", listOf(EvidenceSelection(source, "body")), provider)
    }
    private fun context(id: String, index: Int = 0): ConversationContext = ConversationContextCatalog(repository)
        .plan(id, listOf(ReflectionTarget.Claim(analysis.claimRef(id, "answer", index))))
    private fun correct(id: String) {
        val ref = analysis.claimRef(id, "answer", 0)
        AnalysisFeedback(repository).respond(id, ref, analysis.task(id).stored.version, ClaimFeedbackDecision.CORRECTED, "合成用户纠正：尚不足以比较方法效果")
    }
    private fun publish(id: String): String {
        val ref = analysis.claimRef(id, "answer", 0)
        AnalysisFeedback(repository).respond(id, ref, analysis.task(id).stored.version, ClaimFeedbackDecision.CONFIRMED)
        analysis.reviewClaim(id, "review", ref, provider = provider)
        assertTrue(JSONObject(provider.requests.last().payloadJson).has("conversationContext"))
        return KnowledgeLibrary(repository).publish(id, ref, "review", KnowledgeCategory.INSIGHT, analysis.task(id).stored.version).id
    }
    private class SyntheticProvider : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-conversation-provider"
        override val contextWindowTokens = 128_000
        var claimCount = 1
        var before: () -> Unit = {}
        var extraCitation: String? = null
        val requests = mutableListOf<ModelRequest>()
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request; before()
            val root = JSONObject(request.payloadJson)
            val evidence = root.getJSONArray(if (root.getString("purpose") == "REDUCTION") "derivedInputs" else "evidence")
            return BatchProposal(List(claimCount) { index -> ProposedClaim("合成判断 $index", (0 until evidence.length()).map {
                evidence.getJSONObject(it).getString("id") } + listOfNotNull(extraCitation), ClaimKind.POSSIBLE_INTERPRETATION) })
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            requests += request.original
            val evidence = JSONObject(request.original.payloadJson).getJSONArray("evidence")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, (0 until evidence.length()).map { evidence.getJSONObject(it).getString("id") }))
        }
    }
}
