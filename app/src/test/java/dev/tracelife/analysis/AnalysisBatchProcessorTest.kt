package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.Cadence
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.model.TemplateRef
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
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic records and responses only. No DeepSeek credentials or live network calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AnalysisBatchProcessorTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var databaseName: String
    private lateinit var analysis: ControlledAnalysis
    private lateinit var processor: AnalysisBatchProcessor
    private val clock = MutableClock(Instant.parse("2026-10-17T03:00:00Z"))
    private val scope = AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "synthetic-auto-analysis-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, databaseName)
        analysis = ControlledAnalysis(repository, clock)
        processor = AnalysisBatchProcessor(analysis)
    }

    @After fun tearDown() { repository.close(); context.deleteDatabase(databaseName) }

    @Test fun `a bounded pass resumes after reopen without replaying or hiding the tail`() {
        note("合成😀前段完成。尾部反例未完成。")
        start(AnalysisLimits(fragmentCodePoints = 4, pageSize = 1))
        val provider = SyntheticProvider()
        val first = processor.process("run", provider, maxBatches = 1)
        assertEquals(AnalysisProcessingStatus.PASS_LIMIT_REACHED, first.status)
        assertEquals(1, first.completedBatchesThisPass)
        assertEquals(4L, first.coverage!!.completedTextCodePoints)
        assertEquals(3L, first.coverage.remainingTextFragments)
        assertEquals(1, provider.requests.size)

        reopen()
        val last = processor.process("run", provider)
        assertEquals(AnalysisProcessingStatus.ALL_TEXT_PROCESSED, last.status)
        assertEquals(3, last.completedBatchesThisPass)
        assertEquals(0L, last.coverage!!.remainingTextFragments)
        assertEquals(last.coverage.textCodePoints, last.coverage.completedTextCodePoints)
        assertEquals(listOf(0, 4, 8, 12), provider.requests.map { evidence(it).getInt("start") })
        assertTrue(provider.requests.last().payloadJson.contains("未完成"))
        assertEquals(AnalysisStatus.NEEDS_REVIEW, analysis.task("run").status)

        val again = processor.process("run", provider)
        assertEquals(0, again.completedBatchesThisPass)
        assertEquals(4, provider.requests.size)
        assertEquals(4, analysis.task("run").state.calls.count { it.proposal != null })
    }

    @Test fun `topic hints do not filter full period and time uncertainty survives complete text coverage`() {
        note("合成工作记录", "work", setOf("wrong-topic"))
        repository.createRecord(RecordDraft("合成未知日期"), "unknown")
        repository.createRecord(RecordDraft("合成跨期记录", Occurrence(scope.from.minusDays(1), scope.from), setOf("other")), "overlap")
        repository.createRecord(RecordDraft("合成范围外", Occurrence(scope.through.plusDays(1))), "outside")
        analysis.start(AnalysisRequest("合成全周问题", scope, AnalysisLimits(pageSize = 1), focusTopics = setOf("learning")), "run")
        val provider = SyntheticProvider()
        val report = processor.process("run", provider)

        assertEquals(AnalysisProcessingStatus.TEXT_COMPLETE_WITH_GAPS, report.status)
        assertEquals(3, report.coverage!!.records)
        assertEquals(1, report.coverage.unknownTimeRecords)
        assertEquals(1, report.coverage.overlappingRecords)
        assertEquals(setOf("work", "unknown", "overlap"), provider.requests.map { evidence(it).getString("recordId") }.toSet())
    }

    @Test fun `all answers are processed while attachments and unreadable records remain gaps`() {
        val attachment = repository.importAttachment("synthetic-image".byteInputStream(), "synthetic.jpg", "image/jpeg")
        val template = repository.saveTemplate(TemplateDraft("合成模板", Cadence.DAILY,
            questions = listOf(TemplateQuestion("a", "合成：预期？"), TemplateQuestion("b", "合成：实际？"))), "template")
        repository.createRecord(RecordDraft("合成正文", Occurrence(scope.from), template = TemplateRef(template.id, template.version),
            answers = mapOf("a" to "合成计划", "b" to "合成反例"), attachmentIds = setOf(attachment.id)), "review")
        repository.createRecord(RecordDraft("合成无法分片\u0000反例", Occurrence(scope.from)), "unreadable")
        start()
        val provider = SyntheticProvider()
        val first = processor.process("run", provider, 1)
        assertEquals(AnalysisProcessingStatus.PASS_LIMIT_REACHED, first.status)
        assertEquals(2L, first.coverage!!.remainingTextFragments)
        assertEquals(1, first.coverage.unreadAttachments)
        assertEquals(1, first.coverage.unreadableRecords)

        val done = processor.process("run", provider)
        assertEquals(AnalysisProcessingStatus.TEXT_COMPLETE_WITH_GAPS, done.status)
        assertEquals(0L, done.coverage!!.remainingTextFragments)
        assertEquals(1, done.coverage.unreadAttachments)
        assertEquals(1, done.coverage.unreadableRecords)
        assertEquals(setOf("body", "answer:a", "answer:b"), provider.requests.map { evidence(it).getString("field") }.toSet())
        assertTrue(provider.requests.any { it.payloadJson.contains("实际？") })
        assertTrue(provider.requests.none { it.payloadJson.contains("synthetic-image") })
    }

    @Test fun `failure stops one pass and explicit retry uses the same key without duplicate proposals`() {
        note("合成正文与后续反例")
        start(AnalysisLimits(fragmentCodePoints = 4))
        val provider = SyntheticProvider().apply { fail = true }
        val failed = processor.process("run", provider)
        assertEquals(AnalysisProcessingStatus.RETRY_NEEDED, failed.status)
        assertEquals(0, failed.completedBatchesThisPass)
        assertEquals(0L, failed.coverage!!.completedTextCodePoints)
        assertEquals(1, provider.requests.size)
        assertFalse(analysis.task("run").stored.payload.contains("secret synthetic failure"))

        reopen()
        provider.fail = false
        val done = processor.process("run", provider)
        assertEquals(AnalysisProcessingStatus.ALL_TEXT_PROCESSED, done.status)
        assertEquals(provider.requests[0].idempotencyKey, provider.requests[1].idempotencyKey)
        assertEquals(listOf(1, 2), analysis.task("run").state.calls.take(2).map { it.attempt })
        assertEquals(done.completedBatchesThisPass, analysis.task("run").state.calls.count { it.proposal != null })
    }

    @Test fun `attempt and input budgets leave remaining text rather than falsely completing`() {
        note("合成材料有未读尾部")
        start(AnalysisLimits(fragmentCodePoints = 4, maxCalls = 1))
        val provider = SyntheticProvider()
        val report = processor.process("run", provider)
        assertEquals(AnalysisProcessingStatus.BUDGET_EXHAUSTED, report.status)
        assertEquals(1, report.completedBatchesThisPass)
        assertTrue(report.coverage!!.remainingTextFragments > 0)
        assertEquals(1, provider.requests.size)
        assertEquals(AnalysisProcessingStatus.BUDGET_EXHAUSTED, processor.process("run", provider).status)

        analysis.start(AnalysisRequest("合成很小字节预算", scope, AnalysisLimits(maxInputBytes = 256)), "tiny")
        val tiny = processor.process("tiny", provider)
        assertEquals(AnalysisProcessingStatus.BUDGET_EXHAUSTED, tiny.status)
        assertEquals(0L, tiny.coverage!!.completedTextCodePoints)
        assertTrue(analysis.task("tiny").state.calls.isEmpty())
        assertEquals(1, provider.requests.size)
    }

    @Test fun `interrupted dispatch requires caller recovery before retrying`() {
        note()
        start()
        val provider = SyntheticProvider().apply { crash = true }
        assertThrows(SyntheticCrash::class.java) { processor.process("run", provider) }
        reopen()
        provider.crash = false
        val blocked = processor.process("run", provider)
        assertEquals(AnalysisProcessingStatus.NEEDS_RECOVERY, blocked.status)
        assertNull(blocked.coverage)
        assertEquals(1, provider.requests.size)

        analysis.recoverInterrupted("run")
        assertEquals(AnalysisProcessingStatus.ALL_TEXT_PROCESSED, processor.process("run", provider).status)
        assertEquals(provider.requests[0].idempotencyKey, provider.requests[1].idempotencyKey)
        assertEquals(listOf(CallStatus.INTERRUPTED, CallStatus.SUCCEEDED), analysis.task("run").state.calls.map { it.status })
    }

    @Test fun `cancellation before the next call preserves progress and allows later resume`() {
        note("合成第一段与后续反例")
        start(AnalysisLimits(fragmentCodePoints = 4))
        val cancel = AtomicBoolean(true)
        val provider = SyntheticProvider()
        val before = processor.process("run", provider, cancelled = cancel::get)
        assertEquals(AnalysisProcessingStatus.CANCELLED, before.status)
        assertEquals(0, before.completedBatchesThisPass)
        assertTrue(provider.requests.isEmpty())

        cancel.set(false)
        provider.onComplete = { cancel.set(true) }
        val partial = processor.process("run", provider, cancelled = cancel::get)
        assertEquals(AnalysisProcessingStatus.CANCELLED, partial.status)
        assertEquals(1, partial.completedBatchesThisPass)
        assertEquals(4L, partial.coverage!!.completedTextCodePoints)
        provider.onComplete = null
        cancel.set(false)
        assertEquals(AnalysisProcessingStatus.ALL_TEXT_PROCESSED, processor.process("run", provider, cancelled = cancel::get).status)
    }

    @Test fun `expired stale and deleted tasks cannot dispatch or return current coverage`() {
        note()
        analysis.start(AnalysisRequest("合成过期任务", scope, expiresAt = clock.instant().plusSeconds(1)), "expired")
        clock.now = clock.now.plusSeconds(1)
        val provider = SyntheticProvider()
        val expired = processor.process("expired", provider)
        assertEquals(AnalysisProcessingStatus.EXPIRED, expired.status)
        assertNull(expired.coverage)

        start()
        repository.updateRecord("note", 1, RecordDraft("合成修订", Occurrence(scope.from)))
        val stale = processor.process("run", provider)
        assertEquals(AnalysisProcessingStatus.STALE, stale.status)
        assertNull(stale.coverage)
        analysis.delete("run", analysis.task("run").stored.version)
        assertEquals(AnalysisProcessingStatus.TASK_UNAVAILABLE, processor.process("run", provider).status)
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun `source mutation during dispatch stops processing and does not save or count its response`() {
        note()
        start()
        val provider = SyntheticProvider().apply {
            onComplete = { repository.updateRecord("note", 1, RecordDraft("合成生成期间修改", Occurrence(scope.from))) }
        }
        val report = processor.process("run", provider)
        assertEquals(AnalysisProcessingStatus.STALE, report.status)
        assertEquals(0, report.completedBatchesThisPass)
        assertNull(report.coverage)
        assertTrue(analysis.task("run").state.calls.all { it.proposal == null })
    }

    @Test fun `explicit task deletion during dispatch returns unavailable without restoring the late proposal`() {
        note()
        start()
        val provider = SyntheticProvider().apply {
            onComplete = { analysis.delete("run", analysis.task("run").stored.version) }
        }

        val report = processor.process("run", provider)

        assertEquals(AnalysisProcessingStatus.TASK_UNAVAILABLE, report.status)
        assertEquals(0, report.completedBatchesThisPass)
        assertNull(report.coverage)
        assertEquals(1, provider.requests.size)
        assertTrue(analysis.tasks().isEmpty())
        assertEquals(AnalysisProcessingStatus.TASK_UNAVAILABLE, processor.process("run", provider).status)
        assertEquals(1, provider.requests.size)
    }

    @Test fun `concurrent task version update blocks late proposal and requires explicit recovery`() {
        note()
        start()
        val provider = SyntheticProvider().apply {
            onComplete = {
                val current = analysis.task("run").stored
                repository.saveAnalysisRun(current.copy(version = current.version + 1), current.version)
            }
        }

        val report = processor.process("run", provider)

        assertEquals(AnalysisProcessingStatus.NEEDS_RECOVERY, report.status)
        assertEquals(0, report.completedBatchesThisPass)
        assertNull(report.coverage)
        assertEquals(CallStatus.IN_FLIGHT, analysis.task("run").state.calls.single().status)
        assertNull(analysis.task("run").state.calls.single().proposal)
        provider.onComplete = null
        analysis.recoverInterrupted("run")
        assertEquals(AnalysisProcessingStatus.ALL_TEXT_PROCESSED, processor.process("run", provider).status)
        assertEquals(1, analysis.task("run").state.calls.count { it.proposal != null })
    }

    @Test fun `no source and missing exact IDs are distinguished without model calls`() {
        start()
        val provider = SyntheticProvider()
        val empty = processor.process("run", provider)
        assertEquals(AnalysisProcessingStatus.NO_DATA, empty.status)
        assertEquals(0, empty.coverage!!.records)
        analysis.start(AnalysisRequest("合成缺失 ID", scope.copy(recordIds = setOf("missing"))), "missing-run")
        val missing = processor.process("missing-run", provider)
        assertEquals(AnalysisProcessingStatus.TEXT_COMPLETE_WITH_GAPS, missing.status)
        assertEquals(setOf("missing"), missing.coverage!!.missingExactIds)
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun `overlapping prior receipts count their union and cannot conceal a partial final fragment`() {
        note("合😀e\u0301🚀反例")
        start(AnalysisLimits(fragmentCodePoints = 4))
        val provider = SyntheticProvider()
        analysis.runBatch("run", "manual-first", listOf(EvidenceSelection("note", "body", 0)), provider)
        analysis.runBatch("run", "manual-overlap", listOf(EvidenceSelection("note", "body", 2)), provider)
        val cancelled = processor.process("run", provider, cancelled = { true })
        assertEquals(6L, cancelled.coverage!!.completedTextCodePoints)
        assertEquals(1L, cancelled.coverage.remainingTextFragments)

        val done = processor.process("run", provider)
        assertEquals(AnalysisProcessingStatus.ALL_TEXT_PROCESSED, done.status)
        assertEquals(1, done.completedBatchesThisPass)
        assertEquals(4, evidence(provider.requests.last()).getInt("start"))
        assertEquals(7L, done.coverage!!.completedTextCodePoints)
        assertEquals(0L, done.coverage.remainingTextFragments)
    }

    @Test fun `per-pass limit is bounded before touching any task`() {
        val provider = SyntheticProvider()
        assertThrows(IllegalArgumentException::class.java) { processor.process("missing", provider, 0) }
        assertThrows(IllegalArgumentException::class.java) { processor.process("missing", provider, 101) }
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun `invalid task identifiers remain validation errors rather than unavailable results`() {
        assertThrows(IllegalArgumentException::class.java) { processor.process(" ", SyntheticProvider()) }
    }

    private fun note(text: String = "合成资料：完成练习。", id: String = "note", topics: Set<String> = emptySet()) =
        repository.createRecord(RecordDraft(text, Occurrence(scope.from), topics), id)

    private fun start(limits: AnalysisLimits = AnalysisLimits()) =
        analysis.start(AnalysisRequest("合成逐片段整理", scope, limits), "run")

    private fun reopen() {
        repository.close()
        repository = LifeRepository(context, clock, databaseName)
        analysis = ControlledAnalysis(repository, clock)
        processor = AnalysisBatchProcessor(analysis)
    }

    private fun evidence(request: ModelRequest) = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0)

    private class SyntheticCrash : Error("synthetic process interruption")
    private class SyntheticProvider : AnalysisProvider {
        override val id = "synthetic-auto-provider-v1"
        override val contextWindowTokens = 128_000
        val requests = mutableListOf<ModelRequest>()
        var fail = false
        var crash = false
        var onComplete: (() -> Unit)? = null
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            if (fail) error("secret synthetic failure: ${request.payloadJson}")
            if (crash) throw SyntheticCrash()
            onComplete?.invoke()
            val evidenceId = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return BatchProposal(listOf(ProposedClaim("合成待核对观察", listOf(evidenceId), ClaimKind.REPORTED_EXPERIENCE)))
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant() = now
    }
}
