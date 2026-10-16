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
import dev.tracelife.transfer.StaleSnapshotException
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

/** Entirely synthetic source material and deterministic provider responses; no model/network used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ControlledAnalysisTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var databaseName: String
    private val clock = MutableClock(Instant.parse("2026-10-16T03:00:00Z"))
    private val scope = AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai")
    private val selection = listOf(EvidenceSelection("note", "body"))

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "synthetic-runtime-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, databaseName)
        analysis = ControlledAnalysis(repository, clock)
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(databaseName) }

    @Test fun `plan is idempotent and changed question cannot reuse identity`() {
        note()
        val first = analysis.start(AnalysisRequest("合成任务：复盘本周", scope), "run")
        assertEquals(first, analysis.start(first.state.request, "run"))
        assertEquals(1, analysis.tasks().size)
        assertThrows(IllegalArgumentException::class.java) { analysis.start(first.state.request.copy(question = "不同任务"), "run") }
        assertEquals(AnalysisStatus.READY, analysis.task("run").status)
    }

    @Test fun `routing preserves all topics and separately reports unknown overlapping and missing exact IDs`() {
        note()
        repository.createRecord(RecordDraft("合成未分类记录"), "unknown")
        repository.createRecord(RecordDraft("合成跨周记录", Occurrence(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-15")), setOf("other")), "overlap")
        val task = analysis.start(AnalysisRequest("合成全周任务", scope, AnalysisLimits(pageSize = 1), setOf("learning")))
        val route = analysis.route(task.stored.id)
        assertEquals(3, route.records)
        assertEquals(1, route.unknownTimeRecords)
        assertEquals(1, route.overlappingRecords)
        var offset: Int? = 0
        val ids = mutableListOf<String>()
        while (offset != null) {
            val page = analysis.evidencePage(task.stored.id, offset).page
            ids += page.items.map { it.id }; offset = page.nextOffset
        }
        assertEquals(setOf("note", "unknown", "overlap"), ids.toSet())
        val specific = analysis.start(AnalysisRequest("合成精确任务", scope.copy(recordIds = setOf("note", "missing"))))
        assertEquals(setOf("missing"), analysis.route(specific.stored.id).missingExactIds)
    }

    @Test fun `multiple calls cover long Unicode source without duplicate coverage and remain candidates`() {
        val text = "合成😀记录🚀有进步。但末尾反例是计划未落实。"
        note(text)
        analysis.start(AnalysisRequest("合成任务", scope, AnalysisLimits(fragmentCodePoints = 6)), "run")
        val provider = SyntheticProvider()
        var start = 0
        val total = text.codePointCount(0, text.length)
        while (start < total) {
            val selections = listOf(EvidenceSelection("note", "body", start))
            analysis.runBatch("run", "batch-$start", selections, provider)
            start = minOf(total, start + 6)
            assertEquals(start, analysis.coveragePage("run").records.single().completedCodePoints)
        }
        val count = provider.requests.size
        val cached = analysis.runBatch("run", "batch-0", selection, provider)
        assertEquals(count, provider.requests.size)
        assertEquals(1, cached.claims.size)
        assertTrue(analysis.coveragePage("run").records.single().complete)
        assertEquals(AnalysisStatus.NEEDS_REVIEW, analysis.task("run").status)
        assertFalse(analysis.task("run").stored.payload.contains("计划未落实"))
        assertTrue(provider.requests.last().payloadJson.contains("未落实"))
    }

    @Test fun `answer includes original template question plus instructions history and range in measured request`() {
        val template = repository.saveTemplate(TemplateDraft("合成模板", Cadence.DAILY, questions = listOf(TemplateQuestion("q", "合成问题：预期与实际差在哪里？"))), "template")
        repository.createRecord(RecordDraft(answers = mapOf("q" to "合成回答：没完成"), template = TemplateRef(template.id, 1)), "note")
        repository.saveTemplate(template.draft.copy(questions = listOf(TemplateQuestion("q", "新版本的问题"))), "template", 1)
        analysis.start(AnalysisRequest("合成任务", scope), "run")
        val provider = SyntheticProvider()
        analysis.runBatch("run", "answer", listOf(EvidenceSelection("note", "answer:q")), provider, "合成额外指令", listOf("合成历史"))
        val payload = provider.requests.single().payloadJson
        assertTrue(payload.contains("预期与实际差在哪里"))
        assertFalse(payload.contains("新版本的问题"))
        assertTrue(payload.contains("合成额外指令") && payload.contains("合成历史") && payload.contains("unknownTimeRecords"))
        assertEquals(provider.measured.single(), provider.requests.single())
        val ledger = analysis.task("run").state.calls.single()
        assertEquals(payload.toByteArray(Charsets.UTF_8).size, ledger.inputBytes)
    }

    @Test fun `byte token and output reserve limits reject before dispatch and without recording a call`() {
        note("合成资料".repeat(100))
        analysis.start(AnalysisRequest("合成任务", scope, AnalysisLimits(maxInputBytes = 256)), "bytes")
        val provider = SyntheticProvider()
        assertThrows(AnalysisBudgetException::class.java) { analysis.runBatch("bytes", "b", selection, provider) }
        assertTrue(provider.requests.isEmpty() && analysis.task("bytes").state.calls.isEmpty())
        analysis.start(AnalysisRequest("合成任务", scope, AnalysisLimits(inputTokenLimit = 10, outputTokenReserve = 10)), "tokens")
        provider.tokens = 11
        assertThrows(AnalysisBudgetException::class.java) { analysis.runBatch("tokens", "b", selection, provider) }
        provider.tokens = 5; provider.window = 14
        assertThrows(AnalysisBudgetException::class.java) { analysis.runBatch("tokens", "b", selection, provider) }
        provider.tokens = -1
        assertThrows(AnalysisBudgetException::class.java) { analysis.runBatch("tokens", "b", selection, provider) }
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun `provider failure is durable counts against budget and retries produce one successful proposal`() {
        note()
        analysis.start(AnalysisRequest("合成任务", scope, AnalysisLimits(maxCalls = 2)), "run")
        val provider = SyntheticProvider().apply { fail = true }
        assertThrows(AnalysisValidationException::class.java) { analysis.runBatch("run", "b", selection, provider) }
        assertEquals(AnalysisStatus.NEEDS_RETRY, analysis.task("run").status)
        reopen()
        assertEquals(CallStatus.FAILED, analysis.task("run").state.calls.single().status)
        provider.fail = false
        analysis.runBatch("run", "b", selection, provider)
        assertEquals(2, analysis.task("run").state.calls.size)
        assertEquals(1, analysis.task("run").state.calls.count { it.proposal != null })
        assertEquals(provider.requests[0].idempotencyKey, provider.requests[1].idempotencyKey)
        assertThrows(AnalysisBudgetException::class.java) { analysis.runBatch("run", "another", selection, provider) }
        analysis.runBatch("run", "b", selection, provider)
        assertEquals(2, provider.requests.size)
    }

    @Test fun `interrupted dispatch survives restart requires recovery and cannot silently change retry payload`() {
        note()
        analysis.start(AnalysisRequest("合成任务", scope), "run")
        val provider = SyntheticProvider().apply { interrupt = true }
        assertThrows(SyntheticCrash::class.java) { analysis.runBatch("run", "b", selection, provider) }
        reopen()
        assertEquals(AnalysisStatus.IN_FLIGHT, analysis.task("run").status)
        provider.interrupt = false
        assertThrows(AnalysisConflictException::class.java) { analysis.runBatch("run", "b", selection, provider) }
        analysis.recoverInterrupted("run")
        assertEquals(AnalysisStatus.NEEDS_RETRY, analysis.task("run").status)
        assertThrows(AnalysisConflictException::class.java) { analysis.runBatch("run", "b", selection, provider, "changed") }
        analysis.runBatch("run", "b", selection, provider)
        assertEquals(listOf(CallStatus.INTERRUPTED, CallStatus.SUCCEEDED), analysis.task("run").state.calls.map { it.status })
    }

    @Test fun `source changes during model execution cannot save old proposal and deletion removes all task text`() {
        note()
        analysis.start(AnalysisRequest("合成任务", scope), "run")
        val provider = SyntheticProvider().apply { beforeReturn = { repository.updateRecord("note", 1, RecordDraft("合成修订")) } }
        assertThrows(StaleSnapshotException::class.java) { analysis.runBatch("run", "b", selection, provider) }
        assertEquals(AnalysisStatus.STALE, analysis.task("run").status)
        assertTrue(analysis.task("run").state.calls.all { it.proposal == null })
        analysis.start(AnalysisRequest("合成新任务", scope), "new")
        provider.beforeReturn = { repository.deleteRecord("note", 2) }
        assertThrows(StaleSnapshotException::class.java) { analysis.runBatch("new", "b", selection, provider) }
        assertTrue(analysis.tasks().isEmpty())
    }

    @Test fun `backfill invalidates an uncited range and expiry works with no new records`() {
        note()
        analysis.start(AnalysisRequest("合成任务", scope, expiresAt = clock.instant().plusSeconds(60)), "run")
        clock.now = clock.now.plusSeconds(60)
        assertEquals(AnalysisStatus.EXPIRED, analysis.task("run").status)
        assertThrows(StaleSnapshotException::class.java) { analysis.route("run") }
        analysis.start(AnalysisRequest("合成任务", scope), "backfill")
        repository.createRecord(RecordDraft("合成补记", Occurrence(scope.from)), "late")
        assertEquals(AnalysisStatus.STALE, analysis.task("backfill").status)
        assertThrows(StaleSnapshotException::class.java) { analysis.evidencePage("backfill") }
    }

    @Test fun `invalid or oversized response saves failure and never marks source processed`() {
        note()
        analysis.start(AnalysisRequest("合成任务", scope, AnalysisLimits(maxOutputBytes = 256)), "run")
        val provider = SyntheticProvider().apply { responseText = "合成过长候选".repeat(100) }
        assertThrows(AnalysisValidationException::class.java) { analysis.runBatch("run", "b", selection, provider) }
        assertEquals("INVALID_RESPONSE", analysis.task("run").state.calls.single().failureCode)
        assertEquals(0, analysis.coveragePage("run").records.single().completedCodePoints)
        assertNull(analysis.task("run").state.calls.single().proposal)
    }

    private fun note(text: String = "合成资料：今天完成了一次学习练习。") = repository.createRecord(RecordDraft(text, Occurrence(scope.from)), "note")
    private fun reopen() { repository.close(); repository = LifeRepository(context, clock, databaseName); analysis = ControlledAnalysis(repository, clock) }
    private class SyntheticCrash : Error("synthetic interruption")
    private class SyntheticProvider : AnalysisProvider {
        override val id = "synthetic-provider-v1"
        var window = 128_000
        override val contextWindowTokens get() = window
        var tokens = 100
        var fail = false
        var interrupt = false
        var responseText = "合成待核对提案"
        var beforeReturn: (() -> Unit)? = null
        val measured = mutableListOf<ModelRequest>()
        val requests = mutableListOf<ModelRequest>()
        override fun countInputTokens(request: ModelRequest): Int { measured += request; return tokens }
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            if (fail) error("synthetic failure with private payload that must not be persisted")
            if (interrupt) throw SyntheticCrash()
            beforeReturn?.invoke()
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return BatchProposal(listOf(ProposedClaim(responseText, listOf(id), ClaimKind.REPORTED_EXPERIENCE)))
        }
    }
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant() = now
    }
}
