package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PeriodReductionReviewProcessorTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var processor: PeriodReductionReviewProcessor
    private val database = "synthetic-period-review-${UUID.randomUUID()}.db"
    private val day = LocalDate.parse("2026-10-19")
    private val provider = SyntheticProvider()

    @Before fun setup() { context = RuntimeEnvironment.getApplication(); open() }
    @After fun teardown() { repository.close(); context.deleteDatabase(database) }

    @Test fun `bounded passes restart without rescanning and final rereads every related original`() {
        val target = seed(5)
        provider.classify = { if (it.getString("recordId") == "note-4") ReductionRelevance.IRRELEVANT else ReductionRelevance.SUPPORT }
        val first = processor.process("run", target, provider, provider, maxRequests = 1, batchSize = 2)
        assertEquals(ReductionReviewStatus.PASS_LIMIT_REACHED, first.status)
        assertEquals(2, first.progress.scannedFragments)
        repository.close(); open()
        val second = processor.process("run", target, provider, provider, maxRequests = 2, batchSize = 2)
        assertEquals(ReductionReviewStatus.PASS_LIMIT_REACHED, second.status)
        assertEquals(ReductionReviewStatus.FINAL_READY, second.progress.status)
        assertEquals(5, second.progress.scannedFragments)
        assertEquals(1, second.progress.irrelevantFragments)
        val done = processor.process("run", target, provider, provider)
        assertEquals(ReductionReviewStatus.SUPPORTED, done.status)
        assertEquals(1, done.modelRequestsThisPass)
        assertEquals(4, done.progress.finalRereadFragments)
        assertTrue(done.progress.allOriginalsScanned)
        assertFalse(done.progress.counterevidenceSearchExhaustive)
        val rawFinal = evidence(provider.reviews.single())
        assertEquals(setOf("note-0", "note-1", "note-2", "note-3"), rawFinal.map { it.getString("recordId") }.toSet())
        assertTrue(rawFinal.all { it.getString("text").contains("明确合成原文") })
        assertEquals(0, processor.process("run", target, provider, provider).modelRequestsThisPass)
        assertEquals(5, provider.scans.flatMap(::evidence).map { it.getString("id") }.distinct().size)
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    @Test fun `scan budget shrinks before dispatch without dropping pending sources`() {
        val target = seed(7)
        provider.scanTokensPerReceipt = 2_000
        val done = processor.process("run", target, provider, provider, maxRequests = 10)
        assertEquals(ReductionReviewStatus.SUPPORTED, done.status)
        assertEquals(listOf(4, 3), provider.scans.map { evidence(it).size })
        assertEquals(3, done.modelRequestsThisPass)
        assertEquals(7, done.progress.scannedFragments)
    }

    @Test fun `single unfit scan and unfit final preserve progress and consume no hidden request`() {
        val target = seed(2)
        provider.scanTokensPerReceipt = 100_000
        val stopped = processor.process("run", target, provider, provider)
        assertEquals(ReductionReviewStatus.BUDGET_EXHAUSTED, stopped.status)
        assertEquals(0, stopped.modelRequestsThisPass)
        provider.scanTokensPerReceipt = 10
        provider.finalTokens = 100_000
        val final = processor.process("run", target, provider, provider)
        assertEquals(ReductionReviewStatus.BUDGET_EXHAUSTED, final.status)
        assertEquals(1, final.modelRequestsThisPass)
        assertEquals(2, final.progress.scannedFragments)
        assertTrue(provider.reviews.isEmpty())
    }

    @Test fun `all irrelevant or missing context stops before final call`() {
        val target = seed(3)
        provider.classify = { ReductionRelevance.NEEDS_CONTEXT }
        val contextMissing = processor.process("run", target, provider, provider)
        assertEquals(ReductionReviewStatus.CONTEXT_REQUIRED, contextMissing.status)
        assertEquals(3, contextMissing.progress.contextRequiredFragments)
        assertTrue(provider.reviews.isEmpty())
        AnalysisFeedback(repository).respondReduction("run", target, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        provider.classify = { ReductionRelevance.IRRELEVANT }
        val irrelevant = processor.process("run", target, provider, provider)
        assertEquals(ReductionReviewStatus.NO_RELEVANT_EVIDENCE, irrelevant.status)
        assertEquals(1, irrelevant.modelRequestsThisPass)
        assertEquals(3, irrelevant.progress.irrelevantFragments)
        assertTrue(provider.reviews.isEmpty())
    }

    @Test fun `more than thirty two related originals are preserved instead of truncated`() {
        val target = seed(33)
        val result = processor.process("run", target, provider, provider, maxRequests = 10)
        assertEquals(ReductionReviewStatus.RELATED_EVIDENCE_LIMIT, result.status)
        assertEquals(33, result.progress.scannedFragments)
        assertEquals(33, result.progress.relatedFragments)
        assertTrue(provider.reviews.isEmpty())
    }

    @Test fun `final unsupported is visible and not automatically retried`() {
        val target = seed(2)
        provider.verdict = ReviewVerdict.UNSUPPORTED
        val result = processor.process("run", target, provider, provider)
        assertEquals(ReductionReviewStatus.NOT_SUPPORTED, result.status)
        assertEquals(ReviewVerdict.UNSUPPORTED, result.progress.verdict)
        assertEquals(0, processor.process("run", target, provider, provider).modelRequestsThisPass)
    }

    @Test fun `scan counterevidence cannot be overwritten by final supported verdict`() {
        val target = seed(2)
        provider.classify = { ReductionRelevance.COUNTEREVIDENCE }
        val result = processor.process("run", target, provider, provider)
        assertEquals(ReductionReviewStatus.RETRY_NEEDED, result.status)
        assertEquals(2, result.modelRequestsThisPass)
        assertNull(analysis.currentReductionReview("run", target))
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    @Test fun `failed dispatch stops pass and explicit continuation retries only pending receipt`() {
        val target = seed(3)
        provider.failures = 1
        val stopped = processor.process("run", target, provider, provider, batchSize = 1)
        assertEquals(ReductionReviewStatus.RETRY_NEEDED, stopped.status)
        assertEquals(1, stopped.modelRequestsThisPass)
        assertEquals(0, stopped.progress.scannedFragments)
        val done = processor.process("run", target, provider, provider, maxRequests = 5, batchSize = 1)
        assertEquals(ReductionReviewStatus.SUPPORTED, done.status)
        assertEquals(4, done.modelRequestsThisPass)
        val firstReceipt = evidence(provider.scans.first()).single().getString("id")
        assertEquals(firstReceipt, evidence(provider.scans[1]).single().getString("id"))
    }

    @Test fun `confirmation after scanning invalidates earlier scans and uses new stable keys`() {
        val target = seed(3)
        processor.process("run", target, provider, provider, maxRequests = 1, batchSize = 1)
        AnalysisFeedback(repository).respondReduction("run", target, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        val changed = processor.inspect("run", target)
        assertEquals(0, changed.scannedFragments)
        assertEquals(1, changed.feedbackVersion)
        val done = processor.process("run", target, provider, provider)
        assertEquals(ReductionReviewStatus.SUPPORTED, done.status)
        assertEquals(3, done.progress.scannedFragments)
    }

    @Test fun `cancel leaves resumable scan progress without issuing final`() {
        val target = seed(3)
        var cancel = false
        provider.callback = { cancel = true }
        val result = processor.process("run", target, provider, provider, batchSize = 1, cancelled = { cancel })
        assertEquals(ReductionReviewStatus.CANCELLED, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertEquals(1, result.progress.scannedFragments)
        assertTrue(provider.reviews.isEmpty())
    }

    @Test fun `new synthesis leaf invalidates the fixed complete root`() {
        val target = seed(2)
        analysis.synthesize("run", "late-leaf", listOf(EvidenceSelection("note-0", "body")), provider)
        val result = processor.process("run", target, provider, provider)
        assertEquals(ReductionReviewStatus.DEPENDENCY_BLOCKED, result.status)
        assertEquals(0, result.modelRequestsThisPass)
    }

    @Test fun `source or task deletion during dispatch cannot publish a stale outcome`() {
        val target = seed(2)
        provider.callback = { repository.updateRecord("note-0", 1, RecordDraft("合成修改", Occurrence(day))) }
        val stale = processor.process("run", target, provider, provider)
        assertEquals(ReductionReviewStatus.STALE, stale.status)
        assertEquals(1, stale.modelRequestsThisPass)
        analysis.delete("run", analysis.task("run").stored.version)
        assertEquals(ReductionReviewStatus.TASK_UNAVAILABLE, processor.inspect("run", target).status)
    }

    private fun open() {
        repository = LifeRepository(context, databaseName = database)
        analysis = ControlledAnalysis(repository)
        processor = PeriodReductionReviewProcessor(analysis)
    }
    private fun seed(count: Int): ReductionStatementRef {
        repeat(count) { repository.createRecord(RecordDraft("明确合成原文：记录$it，不是真实个人信息。", Occurrence(day)), "note-$it") }
        analysis.start(AnalysisRequest("合成周期判断", AnalysisQuery(day, day, "Asia/Shanghai")), "run")
        repeat(count) { analysis.synthesize("run", "leaf-$it", listOf(EvidenceSelection("note-$it", "body")), provider) }
        val root = requireNotNull(PeriodReductionProcessor(analysis).process("run", provider, maxRequests = 100).progress.root)
        return analysis.reductionStatementRef("run", root, 0)
    }
    private fun evidence(request: ModelRequest): List<JSONObject> = JSONObject(request.payloadJson).getJSONArray("evidence").let { rows ->
        (0 until rows.length()).map(rows::getJSONObject)
    }
    private inner class SyntheticProvider : AnalysisProvider, SemanticReductionScanProvider, SemanticReviewProvider {
        override val id = "synthetic-period-review-v1"
        override val contextWindowTokens = 128_000
        val scans = mutableListOf<ModelRequest>()
        val reviews = mutableListOf<ModelRequest>()
        var scanTokensPerReceipt = 10
        var finalTokens = 10
        var failures = 0
        var callback: (() -> Unit)? = null
        var classify: (JSONObject) -> ReductionRelevance = { ReductionRelevance.SUPPORT }
        var verdict = ReviewVerdict.SUPPORTED
        override fun countInputTokens(request: ModelRequest) = 10
        override fun complete(request: ModelRequest): BatchProposal {
            val json = JSONObject(request.payloadJson)
            val refs = json.getJSONArray(if (json.getString("purpose") == "REDUCTION") "derivedInputs" else "evidence")
            return BatchProposal(listOf(ProposedClaim("合成记录提到具体练习，须保留自述限制。",
                (0 until refs.length()).map { refs.getJSONObject(it).getString("id") }, ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson,
            evidence(request).size * scanTokensPerReceipt)
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
            scans += request.original
            if (failures-- > 0) error("synthetic provider failure")
            callback?.invoke()
            return ReductionScanCompletion(ReductionScanResult(evidence(request.original).map {
                ReductionFinding(it.getString("id"), classify(it), "合成分类依据")
            }, listOf("合成自述限制")))
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, finalTokens)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            reviews += request.original
            callback?.invoke()
            return ReviewCompletion(ClaimReview(verdict,
                if (verdict == ReviewVerdict.SUPPORTED) evidence(request.original).map { it.getString("id") } else emptyList(),
                unknowns = listOf("合成局限")))
        }
    }
}
