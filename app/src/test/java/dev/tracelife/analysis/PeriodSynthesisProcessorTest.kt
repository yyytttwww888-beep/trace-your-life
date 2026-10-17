package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
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
class PeriodSynthesisProcessorTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var processor: PeriodSynthesisProcessor
    private val database = "synthetic-synthesis-processor-${UUID.randomUUID()}.db"
    private val day = LocalDate.parse("2026-10-17")
    private val scope = AnalysisQuery(day.minusDays(6), day, "Asia/Shanghai")
    private val clock = MutableClock()
    private val provider = SyntheticProvider()

    @Before fun setUp() { context = RuntimeEnvironment.getApplication(); open() }
    @After fun tearDown() { repository.close(); context.deleteDatabase(database) }

    @Test fun `several original records enter one synthesis while extraction coverage remains separate`() {
        note("expectation", "合成：计划先做练习。")
        note("actual", "合成：练习前看了很久资料。")
        start()
        val result = processor.process("run", provider)
        assertEquals(SynthesisProcessingStatus.INPUTS_PROCESSED, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertEquals(SynthesisIntegration.SINGLE_CALL_ALL_AVAILABLE_TEXT, result.progress.integration)
        assertEquals(setOf("expectation", "actual"), evidence(provider.requests.single()).map { it.getString("recordId") }.toSet())
        assertEquals(0L, AnalysisBatchProcessor(analysis).coverage("run").completedTextCodePoints)
        assertTrue(result.progress.batches.single().containsAllAvailableText)
        assertEquals(1, result.progress.batches.single().claims.size)
        assertFalse(result.progress.counterevidenceSearchExhaustive)
        val version = analysis.task("run").stored.version
        assertEquals(result.progress, processor.inspect("run"))
        assertEquals(version, analysis.task("run").stored.version)
    }

    @Test fun `bounded calls resume after reopen and never label separate batches globally integrated`() {
        repeat(5) { note("note-$it", "合成材料$it") }
        start()
        val first = processor.process("run", provider, maxBatches = 1, fragmentsPerBatch = 2)
        assertEquals(SynthesisProcessingStatus.PASS_LIMIT_REACHED, first.status)
        assertEquals(3L, first.progress.coverage!!.remainingTextFragments)
        repository.close(); open()
        val done = processor.process("run", provider, fragmentsPerBatch = 2)
        assertEquals(2, done.modelRequestsThisPass)
        assertEquals(SynthesisIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED, done.progress.integration)
        assertEquals(0L, done.progress.coverage!!.remainingTextFragments)
        assertEquals(5, provider.requests.flatMap(::evidence).map { it.getString("recordId") }.distinct().size)
        assertEquals(0, processor.process("run", provider).modelRequestsThisPass)
    }

    @Test fun `Unicode long record includes the tail and does not complete after its first window`() {
        val text = "合成😀前段看似成功，后段出现相反结果。"
        note("long", text)
        start(AnalysisLimits(fragmentCodePoints = 4))
        val first = processor.process("run", provider, maxBatches = 1, fragmentsPerBatch = 2)
        assertEquals(8L, first.progress.coverage!!.completedTextCodePoints)
        assertTrue(first.progress.coverage.remainingTextFragments > 0)
        processor.process("run", provider, fragmentsPerBatch = 2)
        assertEquals(text, provider.requests.flatMap(::evidence).joinToString("") { it.getString("text") })
        assertEquals(text.codePointCount(0, text.length).toLong(), processor.inspect("run").coverage!!.completedTextCodePoints)
    }

    @Test fun `actual prepared budget shrinks selection before dispatch without losing omitted fragments`() {
        repeat(4) { note("note-$it", "合成资料$it") }
        provider.tokensPerFragment = 100
        start(AnalysisLimits(inputTokenLimit = 150))
        val result = processor.process("run", provider, maxBatches = 1, fragmentsPerBatch = 4)
        assertEquals(1, result.modelRequestsThisPass)
        assertEquals(1, evidence(provider.requests.single()).size)
        assertEquals(3L, result.progress.coverage!!.remainingTextFragments)
        assertEquals(1, analysis.task("run").state.calls.size)
        assertEquals(3, processor.process("run", provider, fragmentsPerBatch = 4).modelRequestsThisPass)
    }

    @Test fun `single fragment budget exhaustion does not dispatch or claim coverage`() {
        note("note", "合成资料")
        provider.tokensPerFragment = 200
        start(AnalysisLimits(inputTokenLimit = 100))
        val result = processor.process("run", provider)
        assertEquals(SynthesisProcessingStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(0, result.modelRequestsThisPass)
        assertEquals(1L, result.progress.coverage!!.remainingTextFragments)
        assertTrue(analysis.task("run").state.calls.isEmpty())
    }

    @Test fun `failed provider stops the pass and explicit retry retains batch identity`() {
        note("note", "合成资料")
        start()
        provider.fail = true
        val first = processor.process("run", provider)
        assertEquals(SynthesisProcessingStatus.RETRY_NEEDED, first.status)
        assertEquals(1, first.modelRequestsThisPass)
        provider.fail = false
        assertEquals(1, processor.process("run", provider).modelRequestsThisPass)
        val calls = analysis.task("run").state.calls
        assertEquals(listOf(1, 2), calls.map { it.attempt })
        assertEquals(1, calls.map { it.batchKey }.distinct().size)
        assertEquals(1, calls.map { it.requestSha256 }.distinct().size)
    }

    @Test fun `in flight work requires explicit recovery and counts interrupted dispatch in budget`() {
        note("note", "合成资料")
        start()
        provider.crash = true
        assertThrows(SyntheticCrash::class.java) { processor.process("run", provider) }
        provider.crash = false
        assertEquals(SynthesisProcessingStatus.NEEDS_RECOVERY, processor.process("run", provider).status)
        analysis.recoverInterrupted("run")
        assertEquals(1, processor.process("run", provider).modelRequestsThisPass)
        assertEquals(listOf(CallStatus.INTERRUPTED, CallStatus.SUCCEEDED), analysis.task("run").state.calls.map { it.status })
    }

    @Test fun `unknown time overlaps exact ID omissions unreadable text and attachments remain explicit`() {
        val attachment = repository.importAttachment("synthetic image".byteInputStream(), "synthetic.jpg", "image/jpeg")
        repository.createRecord(RecordDraft("合成未知时间", attachmentIds = setOf(attachment.id)), "unknown")
        repository.createRecord(RecordDraft("合成跨期", Occurrence(scope.from.minusDays(1), scope.from)), "overlap")
        note("unreadable", "合成\u0000不可安全分片")
        analysis.start(AnalysisRequest("合成周期综合", scope.copy(recordIds = setOf("unknown", "overlap", "unreadable", "missing"))), "run")
        val result = processor.process("run", provider)
        assertEquals(SynthesisProcessingStatus.INPUTS_PROCESSED_WITH_GAPS, result.status)
        val coverage = result.progress.coverage!!
        assertEquals(1, coverage.unreadAttachments)
        assertEquals(1, coverage.unreadableRecords)
        assertEquals(1, coverage.unknownTimeRecords)
        assertEquals(1, coverage.overlappingRecords)
        assertEquals(setOf("missing"), coverage.missingExactIds)
        assertTrue(provider.requests.none { "synthetic image" in it.payloadJson })
    }

    @Test fun `source update during model call prevents stale synthesis from being retained`() {
        note("note", "合成资料")
        start()
        provider.callback = { repository.updateRecord("note", 1, RecordDraft("合成更正", Occurrence(day))) }
        val result = processor.process("run", provider)
        assertEquals(SynthesisProcessingStatus.STALE, result.status)
        assertNull(result.progress.coverage)
        assertTrue(analysis.task("run").state.calls.none { it.status == CallStatus.SUCCEEDED })
    }

    @Test fun `expiry cancellation missing tasks and empty input have distinct outcomes`() {
        assertEquals(SynthesisProcessingStatus.TASK_UNAVAILABLE, processor.inspect("missing").status)
        start()
        assertEquals(SynthesisProcessingStatus.NO_TEXT, processor.process("run", provider).status)
        note("note", "合成资料")
        analysis.start(AnalysisRequest("合成待取消", scope, expiresAt = clock.now.plusSeconds(1)), "active")
        assertEquals(SynthesisProcessingStatus.CANCELLED, processor.process("active", provider, cancelled = { true }).status)
        clock.now = clock.now.plusSeconds(1)
        assertEquals(SynthesisProcessingStatus.EXPIRED, processor.process("active", provider).status)
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun `task deletion during model response returns unavailable without resurrecting output`() {
        note("note", "合成资料")
        start()
        provider.callback = { analysis.delete("run", analysis.task("run").stored.version) }
        val result = processor.process("run", provider)
        assertEquals(SynthesisProcessingStatus.TASK_UNAVAILABLE, result.status)
        assertNull(result.progress.coverage)
        assertEquals(1, result.modelRequestsThisPass)
        assertThrows(AnalysisTaskUnavailableException::class.java) { analysis.task("run") }
    }

    @Test fun `cumulative call budget is shared with extraction and is not reset for synthesis`() {
        note("note", "合成资料")
        start(AnalysisLimits(maxCalls = 1))
        analysis.runBatch("run", "extraction", listOf(EvidenceSelection("note", "body")), provider)
        val result = processor.process("run", provider)
        assertEquals(SynthesisProcessingStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(0, result.modelRequestsThisPass)
        assertEquals(1L, result.progress.coverage!!.remainingTextFragments)
        assertEquals(0, result.progress.batches.size)
    }

    private fun open() {
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        processor = PeriodSynthesisProcessor(analysis)
    }
    private fun start(limits: AnalysisLimits = AnalysisLimits()) = analysis.start(AnalysisRequest("合成周期：对照预期、实际与下一步实验", scope, limits), "run")
    private fun note(id: String, text: String) = repository.createRecord(RecordDraft(text, Occurrence(day)), id)
    private fun evidence(request: ModelRequest): List<JSONObject> {
        val rows = JSONObject(request.payloadJson).getJSONArray("evidence")
        return (0 until rows.length()).map(rows::getJSONObject)
    }
    private class SyntheticCrash : Error("synthetic interruption")
    private inner class SyntheticProvider : AnalysisProvider {
        override val id = "synthetic-period-synthesis-v1"
        override val contextWindowTokens = 128_000
        val requests = mutableListOf<ModelRequest>()
        var tokensPerFragment = 100
        var fail = false
        var crash = false
        var callback: (() -> Unit)? = null
        override fun countInputTokens(request: ModelRequest) = evidence(request).size * tokensPerFragment
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            if (fail) error("synthetic failure")
            if (crash) throw SyntheticCrash()
            callback?.invoke()
            return BatchProposal(listOf(ProposedClaim("合成待核对假设：可尝试先完成短练习再查资料。",
                evidence(request).map { it.getString("id") }, ClaimKind.POSSIBLE_INTERPRETATION)))
        }
    }
    private class MutableClock(var now: Instant = Instant.parse("2026-10-17T03:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant() = now
    }
}
