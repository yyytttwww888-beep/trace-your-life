package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
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
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Adversarial boundaries using only synthetic records and a deterministic, local provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ControlledAnalysisBoundaryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private val clock = MutableClock(Instant.parse("2026-10-16T03:00:00Z"))
    private val scope = AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai")
    private val selection = listOf(EvidenceSelection("note", "body"))

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "synthetic-analysis-boundary-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, databaseName)
        analysis = ControlledAnalysis(repository, clock)
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun `a citation from a previous call cannot certify a newly delivered fragment`() {
        note("合成证据前段。合成反例后段。")
        start(AnalysisLimits(fragmentCodePoints = 4))
        val provider = SyntheticProvider()
        analysis.runBatch("run", "first", selection, provider)
        val firstEvidence = evidence(provider.requests.single()).getString("id")
        provider.answer = { BatchProposal(listOf(ProposedClaim("合成伪造归因", listOf(firstEvidence), ClaimKind.POSSIBLE_INTERPRETATION))) }

        assertThrows(AnalysisValidationException::class.java) {
            analysis.runBatch("run", "second", listOf(EvidenceSelection("note", "body", 4)), provider)
        }

        val calls = analysis.task("run").state.calls
        assertNotEquals(firstEvidence, evidence(provider.requests.last()).getString("id"))
        assertEquals(listOf(CallStatus.SUCCEEDED, CallStatus.FAILED), calls.map { it.status })
        assertEquals("INVALID_RESPONSE", calls.last().failureCode)
        assertNull(calls.last().proposal)
        assertEquals(4, analysis.coveragePage("run").records.single().completedCodePoints)
    }

    @Test fun `overlapping Unicode fragments count their union and do not hide the unread counterexample tail`() {
        // Seven code points: supplementary characters and the combining mark are preserved exactly.
        note("合😀e\u0301🚀反例")
        start(AnalysisLimits(fragmentCodePoints = 4))
        val provider = SyntheticProvider()
        analysis.runBatch("run", "prefix", selection, provider)
        analysis.runBatch("run", "overlap", listOf(EvidenceSelection("note", "body", 2)), provider)

        val partial = analysis.coveragePage("run").records.single()
        assertEquals(7, partial.textCodePoints)
        assertEquals(6, partial.completedCodePoints)
        assertFalse(partial.complete)
        assertEquals(listOf("合😀e\u0301", "e\u0301🚀反"), provider.requests.map { evidence(it).getString("text") })

        analysis.runBatch("run", "tail", listOf(EvidenceSelection("note", "body", 6)), provider)
        assertEquals("例", evidence(provider.requests.last()).getString("text"))
        assertEquals(7, analysis.coveragePage("run").records.single().completedCodePoints)
        assertTrue(analysis.coveragePage("run").records.single().complete)
    }

    @Test fun `failed dispatch consumes cumulative bytes and cannot evade the budget with a retry`() {
        note()
        start(AnalysisLimits(maxInputBytes = 8_192, totalInputBytes = 8_192, maxCalls = 10))
        val provider = SyntheticProvider().apply { answer = { error("synthetic provider failure") } }
        val instructions = "x".repeat(4_000)
        assertThrows(AnalysisValidationException::class.java) {
            analysis.runBatch("run", "batch", selection, provider, instructions)
        }
        val first = analysis.task("run").state.calls.single()
        assertTrue(first.inputBytes > 4_096)
        assertEquals(CallStatus.FAILED, first.status)

        repository.close()
        repository = LifeRepository(context, clock, databaseName)
        analysis = ControlledAnalysis(repository, clock)
        assertThrows(AnalysisBudgetException::class.java) {
            analysis.runBatch("run", "batch", selection, provider, instructions)
        }
        assertEquals(1, provider.requests.size)
        assertEquals(listOf(first), analysis.task("run").state.calls)
    }

    @Test fun `a source change during tokenization prevents first dispatch and attempt registration`() {
        note()
        start()
        val provider = SyntheticProvider().apply {
            onCount = { repository.updateRecord("note", 1, RecordDraft("合成修订资料", Occurrence(scope.from))) }
        }

        assertThrows(StaleSnapshotException::class.java) { analysis.runBatch("run", "batch", selection, provider) }
        assertTrue(provider.requests.isEmpty())
        assertTrue(analysis.task("run").state.calls.isEmpty())
        assertEquals(AnalysisStatus.STALE, analysis.task("run").status)
    }

    @Test fun `a cached successful retry cannot return a candidate if tokenization changes its source`() {
        note()
        start()
        val provider = SyntheticProvider()
        analysis.runBatch("run", "batch", selection, provider)
        provider.onCount = { repository.updateRecord("note", 1, RecordDraft("合成修订资料", Occurrence(scope.from))) }

        assertThrows(StaleSnapshotException::class.java) { analysis.runBatch("run", "batch", selection, provider) }
        assertEquals(1, provider.requests.size)
        assertEquals(AnalysisStatus.STALE, analysis.task("run").status)
        assertEquals(1, analysis.task("run").state.calls.size)
    }

    @Test fun `a cached successful retry rechecks expiry after tokenization`() {
        note()
        analysis.start(AnalysisRequest("合成任务", scope, expiresAt = clock.instant().plusSeconds(60)), "run")
        val provider = SyntheticProvider()
        analysis.runBatch("run", "batch", selection, provider)
        provider.onCount = { clock.now = clock.now.plusSeconds(60) }

        assertThrows(StaleSnapshotException::class.java) { analysis.runBatch("run", "batch", selection, provider) }
        assertEquals(1, provider.requests.size)
        assertEquals(AnalysisStatus.EXPIRED, analysis.task("run").status)
    }

    @Test fun `completed text leaves attachments and unreadable NUL content visible as coverage gaps`() {
        val attachment = repository.importAttachment(ByteArrayInputStream("synthetic attachment".toByteArray()), "synthetic.txt", "text/plain")
        repository.createRecord(RecordDraft("合成正文", Occurrence(scope.from), attachmentIds = setOf(attachment.id)), "note")
        repository.createRecord(RecordDraft("合成前缀\u0000合成反例", Occurrence(scope.from)), "unreadable")
        start()
        val provider = SyntheticProvider()
        analysis.runBatch("run", "body", selection, provider)

        val coverage = analysis.coveragePage("run").records.associateBy { it.recordId }
        val textWithAttachment = coverage.getValue("note")
        assertEquals(textWithAttachment.textCodePoints, textWithAttachment.completedCodePoints)
        assertEquals(1, textWithAttachment.unreadAttachments)
        assertFalse(textWithAttachment.complete)
        assertTrue(coverage.getValue("unreadable").unreadable)
        assertFalse(coverage.getValue("unreadable").complete)
        assertEquals(1, provider.requests.size)
    }

    @Test fun `deleting a running task finishes before the provider returns and rejects its late proposal`() {
        note()
        start()
        val enteredProvider = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val exitedProvider = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val provider = SyntheticProvider().apply {
            answer = { request ->
                enteredProvider.countDown()
                try {
                    assertTrue("synthetic provider was never released", releaseProvider.await(5, TimeUnit.SECONDS))
                    val id = evidence(request).getString("id")
                    BatchProposal(listOf(ProposedClaim("合成迟到提案", listOf(id), ClaimKind.REPORTED_EXPERIENCE)))
                } finally {
                    exitedProvider.countDown()
                }
            }
        }
        try {
            val pending = workers.submit<BatchProposal> { analysis.runBatch("run", "batch", selection, provider) }
            assertTrue("synthetic provider did not start", enteredProvider.await(5, TimeUnit.SECONDS))
            val running = analysis.task("run")
            assertEquals(AnalysisStatus.IN_FLIGHT, running.status)

            workers.submit<Unit> { analysis.delete("run", running.stored.version) }.get(5, TimeUnit.SECONDS)
            assertEquals("deletion must finish while the provider is still blocked", 1L, exitedProvider.count)
            assertNull(repository.analysisRun("run"))
            assertTrue(analysis.tasks().isEmpty())

            releaseProvider.countDown()
            val failure = assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is IllegalArgumentException)
            assertNull(repository.analysisRun("run"))
            assertTrue(analysis.tasks().isEmpty())
        } finally {
            releaseProvider.countDown()
            workers.shutdownNow()
            assertTrue("synthetic workers did not stop", workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun note(text: String = "合成资料：完成了一次学习练习。") =
        repository.createRecord(RecordDraft(text, Occurrence(scope.from)), "note")

    private fun start(limits: AnalysisLimits = AnalysisLimits()) =
        analysis.start(AnalysisRequest("合成任务：检查证据覆盖", scope, limits), "run")

    private fun evidence(request: ModelRequest): JSONObject =
        JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0)

    private class SyntheticProvider : AnalysisProvider {
        override val id = "synthetic-boundary-provider-v1"
        override val contextWindowTokens = 128_000
        var onCount: (() -> Unit)? = null
        var answer: (ModelRequest) -> BatchProposal = { request ->
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            BatchProposal(listOf(ProposedClaim("合成待核对提案", listOf(id), ClaimKind.REPORTED_EXPERIENCE)))
        }
        val requests = mutableListOf<ModelRequest>()
        override fun countInputTokens(request: ModelRequest): Int {
            onCount?.invoke()
            return 100
        }
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            return answer(request)
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}
