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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PeriodReductionProcessorTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var processor: PeriodReductionProcessor
    private lateinit var feedback: AnalysisFeedback
    private val database = "synthetic-period-reduction-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val day = LocalDate.parse("2026-10-19")
    private val scope = AnalysisQuery(day.minusDays(6), day, "Asia/Shanghai")
    private val provider = SyntheticProvider()

    @Before fun setUp() { context = RuntimeEnvironment.getApplication(); open() }
    @After fun tearDown() { repository.close(); context.deleteDatabase(database) }

    @Test fun `twenty one leaves form a bounded resumable tree including singleton tails`() {
        seed(21)
        val before = processor.inspect("run")
        assertEquals(ReductionProcessingStatus.READY, before.status)
        assertEquals(21, before.leafCount)
        val first = processor.process("run", provider)
        assertEquals(ReductionProcessingStatus.PASS_LIMIT_REACHED, first.status)
        assertEquals(3, first.modelRequestsThisPass)
        assertEquals(3, first.completedNodesThisPass)
        assertNull(first.progress.root)
        repository.close(); open()
        val done = processor.process("run", provider, maxRequests = 100)
        assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, done.status)
        assertEquals(5, done.modelRequestsThisPass)
        assertEquals(8, provider.reductions.size)
        assertTrue(provider.reductions.all { inputs(it).size in 2..4 })
        assertEquals(21, done.progress.rootLeafCount)
        val root = analysis.reductionNode("run", done.progress.root!!.batchKey)
        assertEquals(done.progress.leafRefs.toSet(), root.leafRefs.toSet())
        assertEquals(21, root.originalEvidence.size)
        assertEquals(0L, done.progress.coverage!!.remainingTextFragments)
        assertFalse(done.progress.originalEvidenceVerified)
        assertFalse(done.progress.counterevidenceSearchExhaustive)
        assertTrue(provider.reductions.none { "合成原文标记" in it.payloadJson })
        val version = analysis.task("run").stored.version
        assertEquals(done.progress, processor.inspect("run"))
        assertEquals(0, processor.process("run", provider).modelRequestsThisPass)
        assertEquals(version, analysis.task("run").stored.version)
    }

    @Test fun `synthesis must cover original text before any derived request`() {
        records(3)
        start()
        synthesize(0)
        val result = processor.process("run", provider)
        assertEquals(ReductionProcessingStatus.NEEDS_INPUT_PROCESSING, result.status)
        assertEquals(2L, result.progress.coverage!!.remainingTextFragments)
        assertEquals(1, result.progress.leafCount)
        assertEquals(0, result.modelRequestsThisPass)
        assertNull(result.progress.root)
    }

    @Test fun `empty synthesis proposals and empty reduction outputs remain covered leaves`() {
        provider.emptySynthesis = true
        provider.emptyReduction = true
        seed(5)
        val result = processor.process("run", provider)
        assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, result.status)
        assertEquals(2, result.modelRequestsThisPass)
        assertEquals(5, result.progress.leafCount)
        assertEquals(5, result.progress.rootLeafCount)
        val root = analysis.reductionNode("run", result.progress.root!!.batchKey)
        assertTrue(root.digest.statements.isEmpty())
        assertEquals(listOf("合成：没有可概括的判断，仍保留资料覆盖。"), root.digest.unknowns)
        assertTrue(provider.reductions.first().payloadJson.contains("没有足够内容形成解释"))
    }

    @Test fun `repeated original citations are counted once without collapsing distinct leaves`() {
        seed(2)
        synthesize(0, "additional-leaf")
        val result = processor.process("run", provider)
        assertEquals(3, result.progress.leafCount)
        assertEquals(2, result.progress.originalEvidenceFragments)
        assertEquals(result.progress.coverage!!.textCodePoints, result.progress.coverage.completedTextCodePoints)
        assertEquals(2, analysis.reductionNode("run", result.progress.root!!.batchKey).originalEvidence.size)
    }

    @Test fun `a prior root cannot claim a newly successful leaf and its group is reused`() {
        seed(2)
        val original = processor.process("run", provider).progress
        synthesize(0, "late-leaf")
        val expanded = processor.inspect("run")
        assertEquals(ReductionProcessingStatus.READY, expanded.status)
        assertEquals(3, expanded.leafCount)
        assertNotEquals(original.leafManifestSha256, expanded.leafManifestSha256)
        assertNull(expanded.root)
        val done = processor.process("run", provider)
        assertEquals(1, done.modelRequestsThisPass)
        assertEquals(3, done.progress.rootLeafCount)
        assertNotEquals(original.root, done.progress.root)
        val root = analysis.reductionNode("run", done.progress.root!!.batchKey)
        assertTrue(root.inputs.any { it.node == original.root })
    }

    @Test fun `changing fan in reuses successful contiguous groups without overlapping ancestors`() {
        seed(8)
        val first = processor.process("run", provider, maxRequests = 1, fanIn = 2)
        val saved = first.progress.nodes.single().ref
        val done = processor.process("run", provider, maxRequests = 100, fanIn = 4)
        assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, done.status)
        assertEquals(3, done.modelRequestsThisPass)
        assertTrue(analysis.reductionNode("run", done.progress.root!!.batchKey).inputs.any { it.node == saved })
        assertEquals(8, done.progress.rootLeafCount)
        assertEquals(0, processor.process("run", provider, fanIn = 2).modelRequestsThisPass)
    }

    @Test fun `prepared budget shrinks four inputs to two and resumes the smaller group`() {
        provider.tokensPerInput = 100
        seed(4, AnalysisLimits(inputTokenLimit = 250))
        val first = processor.process("run", provider, maxRequests = 1)
        assertEquals(ReductionProcessingStatus.PASS_LIMIT_REACHED, first.status)
        assertEquals(1, first.modelRequestsThisPass)
        assertEquals(2, inputs(provider.reductions.single()).size)
        assertEquals(5, analysis.task("run").state.calls.size)
        val done = processor.process("run", provider)
        assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, done.status)
        assertEquals(2, done.modelRequestsThisPass)
        assertTrue(provider.reductions.all { inputs(it).size == 2 })
        assertEquals(4, done.progress.rootLeafCount)
    }

    @Test fun `two inputs exceeding budget never dispatch or create partial coverage`() {
        provider.tokensPerInput = 100
        seed(4, AnalysisLimits(inputTokenLimit = 150))
        val version = analysis.task("run").stored.version
        val result = processor.process("run", provider)
        assertEquals(ReductionProcessingStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(0, result.modelRequestsThisPass)
        assertEquals(0, result.completedNodesThisPass)
        assertEquals(4, result.progress.leafCount)
        assertNull(result.progress.root)
        assertEquals(version, analysis.task("run").stored.version)
    }

    @Test fun `cumulative budget includes previous synthesis calls and never creates another task`() {
        seed(5, AnalysisLimits(maxCalls = 6))
        val result = processor.process("run", provider)
        assertEquals(ReductionProcessingStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertEquals(6, analysis.task("run").state.calls.size)
        assertEquals(1, analysis.tasks().size)
        assertNull(result.progress.root)
        assertEquals(0, processor.process("run", provider).modelRequestsThisPass)
    }

    @Test fun `a failed request stops this pass and an explicit retry preserves its identity`() {
        seed(5)
        provider.failures = 1
        val failed = processor.process("run", provider, maxRequests = 100)
        assertEquals(ReductionProcessingStatus.RETRY_NEEDED, failed.status)
        assertEquals(1, failed.modelRequestsThisPass)
        assertEquals(0, failed.completedNodesThisPass)
        assertEquals(ReductionProcessingStatus.RETRY_NEEDED, processor.inspect("run").status)
        val done = processor.process("run", provider)
        assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, done.status)
        val calls = analysis.task("run").state.calls.filter { it.purpose == CallPurpose.REDUCTION }
        assertEquals(listOf(1, 2), calls.take(2).map { it.attempt })
        assertEquals(calls[0].batchKey, calls[1].batchKey)
        assertEquals(calls[0].requestSha256, calls[1].requestSha256)
        assertEquals(listOf(CallStatus.FAILED, CallStatus.SUCCEEDED), calls.take(2).map { it.status })
    }

    @Test fun `in flight reduction requires explicit recovery and retains interrupted attempt cost`() {
        seed(2)
        provider.crash = true
        assertThrows(SyntheticCrash::class.java) { processor.process("run", provider) }
        provider.crash = false
        val waiting = processor.process("run", provider)
        assertEquals(ReductionProcessingStatus.NEEDS_RECOVERY, waiting.status)
        assertEquals(0, waiting.modelRequestsThisPass)
        analysis.recoverInterrupted("run")
        val done = processor.process("run", provider)
        assertEquals(1, done.modelRequestsThisPass)
        assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, done.status)
        assertEquals(listOf(CallStatus.INTERRUPTED, CallStatus.SUCCEEDED),
            analysis.task("run").state.calls.filter { it.purpose == CallPurpose.REDUCTION }.map { it.status })
    }

    @Test fun `blocked and permanently deleted feedback are explicit dependencies never omitted leaves`() {
        seed(4)
        processor.process("run", provider)
        val refs = leafClaims()
        listOf(ClaimFeedbackDecision.CORRECTED, ClaimFeedbackDecision.REJECTED, ClaimFeedbackDecision.WITHDRAWN)
            .forEachIndexed { index, decision ->
                feedback.respond("run", refs[index], analysis.task("run").stored.version, decision, "合成用户纠正。")
            }
        val last = feedback.respond("run", refs.last(), analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        feedback.delete(last.feedback.id, last.feedback.version)
        val result = processor.process("run", provider)
        assertEquals(ReductionProcessingStatus.DEPENDENCY_BLOCKED, result.status)
        assertEquals(4, result.progress.blockedLeaves.size)
        assertEquals(4, result.progress.leafCount)
        assertEquals(0, result.modelRequestsThisPass)
        assertNull(result.progress.root)
        assertEquals(0L, result.progress.coverage!!.remainingTextFragments)
    }

    @Test fun `new confirmation invalidates affected ancestors while unrelated groups remain reusable`() {
        seed(5)
        val original = processor.process("run", provider).progress.root
        val refs = leafClaims()
        feedback.respond("run", refs.first(), analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        val outdated = processor.inspect("run")
        assertNull(outdated.root)
        assertEquals(2, outdated.nodes.count { it.state == ReductionNodeState.STALE_DEPENDENCIES })
        val refreshed = processor.process("run", provider)
        assertEquals(2, refreshed.modelRequestsThisPass)
        assertNotEquals(original, refreshed.progress.root)
        feedback.respond("run", refs.last(), analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        val final = processor.process("run", provider)
        assertEquals(1, final.modelRequestsThisPass)
        assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, final.status)
        assertEquals(5, final.progress.rootLeafCount)
    }

    @Test fun `read only inspection detects concurrent feedback version changes`() {
        seed(2, expiresAt = clock.now.plusSeconds(3600))
        val ref = leafClaims().first()
        val version = analysis.task("run").stored.version
        clock.onNextInstant = { feedback.respond("run", ref, version, ClaimFeedbackDecision.CONFIRMED) }
        val mixed = processor.inspect("run")
        assertEquals(ReductionProcessingStatus.CONFLICT, mixed.status)
        assertNull(mixed.coverage)
        assertTrue(mixed.leafRefs.isEmpty())
        assertTrue(provider.reductions.isEmpty())
    }

    @Test fun `gaps remain visible even when all available text has a complete reduction tree`() {
        val attachment = repository.importAttachment("synthetic image".byteInputStream(), "synthetic.jpg", "image/jpeg")
        repository.createRecord(RecordDraft("合成未知时间", attachmentIds = setOf(attachment.id)), "note-0")
        repository.createRecord(RecordDraft("合成跨期", Occurrence(scope.from.minusDays(1), scope.from)), "note-1")
        repository.createRecord(RecordDraft("合成\u0000无法安全分片", Occurrence(day)), "unreadable")
        analysis.start(AnalysisRequest("合成周期归并", scope.copy(recordIds = setOf("note-0", "note-1", "unreadable", "missing"))), "run")
        synthesize(0); synthesize(1)
        val result = processor.process("run", provider)
        assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, result.status)
        val coverage = result.progress.coverage!!
        assertTrue(coverage.hasGaps)
        assertEquals(1, coverage.unreadAttachments)
        assertEquals(1, coverage.unreadableRecords)
        assertEquals(1, coverage.unknownTimeRecords)
        assertEquals(1, coverage.overlappingRecords)
        assertEquals(setOf("missing"), coverage.missingExactIds)
    }

    @Test fun `source changes during dispatch prevent a current root from being retained`() {
        seed(2)
        provider.callback = { repository.updateRecord("note-0", 1, RecordDraft("合成更正", Occurrence(day))) }
        val result = processor.process("run", provider)
        assertEquals(ReductionProcessingStatus.STALE, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertNull(result.progress.coverage)
        assertNull(result.progress.root)
    }

    @Test fun `task deletion during dispatch cannot resurrect the derived result`() {
        seed(2)
        provider.callback = { analysis.delete("run", analysis.task("run").stored.version) }
        val result = processor.process("run", provider)
        assertEquals(ReductionProcessingStatus.TASK_UNAVAILABLE, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertNull(result.progress.root)
    }

    @Test fun `empty single cancelled expired and unavailable tasks do not invoke reduction`() {
        assertEquals(ReductionProcessingStatus.TASK_UNAVAILABLE, processor.inspect("missing").status)
        start()
        assertEquals(ReductionProcessingStatus.NO_TEXT, processor.process("run", provider).status)
        repository.createRecord(RecordDraft("合成单个批次", Occurrence(day)), "note-0")
        analysis.start(AnalysisRequest("合成单批", scope), "single")
        analysis.synthesize("single", "leaf", listOf(EvidenceSelection("note-0", "body")), provider)
        val single = processor.process("single", provider)
        assertEquals(ReductionProcessingStatus.SINGLE_BATCH, single.status)
        assertEquals(1, single.progress.rootLeafCount)
        assertEquals(0, single.modelRequestsThisPass)
        analysis.start(AnalysisRequest("合成过期", scope, expiresAt = clock.now.plusSeconds(1)), "expiring")
        repeat(2) { analysis.synthesize("expiring", "leaf-$it", listOf(EvidenceSelection("note-0", "body")), provider) }
        assertEquals(ReductionProcessingStatus.CANCELLED, processor.process("expiring", provider, cancelled = { true }).status)
        clock.now = clock.now.plusSeconds(1)
        assertEquals(ReductionProcessingStatus.EXPIRED, processor.process("expiring", provider).status)
        assertTrue(provider.reductions.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { processor.process("single", provider, maxRequests = 0) }
        assertThrows(IllegalArgumentException::class.java) { processor.inspect("single", fanIn = 1) }
    }

    private fun open() {
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        processor = PeriodReductionProcessor(analysis)
        feedback = AnalysisFeedback(repository, clock)
    }
    private fun records(count: Int) = repeat(count) {
        repository.createRecord(RecordDraft("合成原文标记：记录$it", Occurrence(day)), "note-$it")
    }
    private fun start(limits: AnalysisLimits = AnalysisLimits(), expiresAt: Instant? = null) =
        analysis.start(AnalysisRequest("合成周期：整理已有批次的共同点、差异和未知", scope, limits, expiresAt = expiresAt), "run")
    private fun seed(count: Int, limits: AnalysisLimits = AnalysisLimits(), expiresAt: Instant? = null) {
        records(count); start(limits, expiresAt); repeat(count) { synthesize(it) }
    }
    private fun synthesize(index: Int, key: String = "leaf-$index") =
        analysis.synthesize("run", key, listOf(EvidenceSelection("note-$index", "body")), provider)
    private fun leafClaims() = analysis.task("run").state.calls.filter { it.purpose == CallPurpose.SYNTHESIS }
        .map { ClaimRef(it.batchKey, 0, AnalysisRunCodec.claimSha256(it.proposal!!.claims.single())) }
    private fun inputs(request: ModelRequest): List<JSONObject> {
        val rows = JSONObject(request.payloadJson).getJSONArray("derivedInputs")
        return (0 until rows.length()).map(rows::getJSONObject)
    }

    private class SyntheticCrash : Error("synthetic interrupted reduction")
    private inner class SyntheticProvider : AnalysisProvider {
        override val id = "synthetic-period-reduction-v1"
        override val contextWindowTokens = 128_000
        val reductions = mutableListOf<ModelRequest>()
        var tokensPerInput = 10
        var emptySynthesis = false
        var emptyReduction = false
        var failures = 0
        var crash = false
        var callback: (() -> Unit)? = null
        override fun countInputTokens(request: ModelRequest) = if (JSONObject(request.payloadJson).getString("purpose") == "REDUCTION")
            inputs(request).size * tokensPerInput else 10
        override fun complete(request: ModelRequest): BatchProposal {
            val payload = JSONObject(request.payloadJson)
            if (payload.getString("purpose") == "SYNTHESIS") {
                if (emptySynthesis) return BatchProposal(emptyList(), listOf("合成：没有足够内容形成解释。"))
                val evidence = payload.getJSONArray("evidence")
                return BatchProposal(listOf(ProposedClaim("合成待核对解释。",
                    (0 until evidence.length()).map { evidence.getJSONObject(it).getString("id") }, ClaimKind.POSSIBLE_INTERPRETATION)))
            }
            reductions += request
            if (failures > 0) { failures--; error("synthetic provider failure") }
            if (crash) throw SyntheticCrash()
            callback?.invoke()
            if (emptyReduction) return BatchProposal(emptyList(), listOf("合成：没有可概括的判断，仍保留资料覆盖。"))
            return BatchProposal(listOf(ProposedClaim("合成导航草稿，需要回查原文。",
                inputs(request).map { it.getString("id") }, ClaimKind.POSSIBLE_INTERPRETATION)))
        }
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T03:00:00Z")) : Clock() {
        var onNextInstant: (() -> Unit)? = null
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant {
            val callback = onNextInstant
            onNextInstant = null
            callback?.invoke()
            return now
        }
    }
}
