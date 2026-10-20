package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceStamp
import dev.tracelife.storage.StoredAnalysisRun
import org.json.JSONArray
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
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic source, prepared transport and historic payloads; never uses a key or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AnalysisAccountingTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var databaseName: String
    private val now = Instant.parse("2026-10-17T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val scope = AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai")
    private val selection = listOf(EvidenceSelection("note", "body"))

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "synthetic-accounting-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, databaseName)
        analysis = ControlledAnalysis(repository, clock)
        repository.createRecord(RecordDraft("合成资料：今天复习了一个方法。", Occurrence(scope.from)), "note")
    }

    @After fun tearDown() { repository.close(); context.deleteDatabase(databaseName) }

    @Test fun `ledger persists actual prepared body accounting and separately records server usage`() {
        analysis.start(AnalysisRequest("合成整理任务", scope), "run")
        val provider = PreparedProvider()
        analysis.runBatch("run", "b", selection, provider)
        val sent = provider.sent.single()
        repository.close()
        repository = LifeRepository(context, clock, databaseName)
        analysis = ControlledAnalysis(repository, clock)
        val call = analysis.task("run").state.calls.single()
        assertNotEquals(sent.original.payloadJson, sent.payloadJson)
        assertEquals(sent.payloadJson.toByteArray(Charsets.UTF_8).size, call.inputBytes)
        assertEquals(AnalysisRunCodec.sha256(sent.payloadJson), call.requestSha256)
        assertEquals(sent.inputTokens, call.inputTokens)
        assertEquals(TokenCountKind.CONSERVATIVE_UTF8_BOUND, call.tokenCountKind)
        assertEquals(ModelUsage(sent.inputTokens - 1, 7), call.usage)
        assertFalse(analysis.task("run").stored.payload.contains("今天复习了一个方法"))
        analysis.runBatch("run", "b", selection, provider)
        assertEquals("Successful cached request must not dispatch again", 1, provider.sent.size)
    }

    @Test fun `provider wrapper bytes cannot bypass the executor input budget`() {
        analysis.start(AnalysisRequest("合成整理任务", scope, AnalysisLimits(maxInputBytes = 4_096)), "run")
        val provider = PreparedProvider().apply { framing = "synthetic-wrapper".repeat(300) }
        assertThrows(AnalysisBudgetException::class.java) { analysis.runBatch("run", "b", selection, provider) }
        assertTrue(provider.sent.isEmpty())
        assertTrue(analysis.task("run").state.calls.isEmpty())
    }

    @Test fun `changed supplier wrapper cannot reuse a successful batch even with unchanged source`() {
        analysis.start(AnalysisRequest("合成整理任务", scope), "run")
        val provider = PreparedProvider()
        analysis.runBatch("run", "b", selection, provider)
        provider.framing = "synthetic changed wrapper"
        assertThrows(AnalysisConflictException::class.java) { analysis.runBatch("run", "b", selection, provider) }
        assertEquals(1, provider.sent.size)
        assertEquals(1, analysis.task("run").state.calls.size)
    }

    @Test fun `actual usage above the registered bound rejects proposal and processing coverage`() {
        analysis.start(AnalysisRequest("合成整理任务", scope), "run")
        val provider = PreparedProvider().apply { overrun = true }
        assertThrows(AnalysisValidationException::class.java) { analysis.runBatch("run", "b", selection, provider) }
        val call = analysis.task("run").state.calls.single()
        assertEquals(CallStatus.FAILED, call.status)
        assertEquals("INVALID_RESPONSE", call.failureCode)
        assertNull(call.proposal)
        assertEquals(0, analysis.coveragePage("run").records.single().completedCodePoints)
        assertEquals(1, provider.sent.size)
    }

    @Test fun `legacy payload remains exact accounting while mixed version fields are rejected`() {
        val legacy = legacyFixture(1, 10)
        val decoded = AnalysisRunCodec.decode(legacy.toString())
        assertEquals(TokenCountKind.EXACT, decoded.calls.single().tokenCountKind)
        assertNull(decoded.calls.single().usage)
        assertEquals(decoded, AnalysisRunCodec.decode(AnalysisRunCodec.encode(decoded)))
        legacy.getJSONArray("calls").getJSONObject(0).put("tokenCountKind", "CONSERVATIVE_UTF8_BOUND")
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(legacy.toString()) }
        val v2WithoutAccounting = legacyFixture(1, 10).put("formatVersion", 2)
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(v2WithoutAccounting.toString()) }
    }

    @Test fun `larger new output reserve does not expand a saved task budget or its dispatched request`() {
        analysis.start(AnalysisRequest("合成旧额度", scope, AnalysisLimits(outputTokenReserve = 2_000)), "old")
        analysis.start(AnalysisRequest("合成新额度", scope), "new")
        repository.close()
        repository = LifeRepository(context, clock, databaseName)
        analysis = ControlledAnalysis(repository, clock)
        val provider = PreparedProvider()
        listOf("old" to 2_000, "new" to 8_000).forEach { (id, expected) ->
            val state = analysis.task(id).state
            assertEquals(expected, state.request.limits.outputTokenReserve)
            assertEquals(state, AnalysisRunCodec.decode(AnalysisRunCodec.encode(state)))
            analysis.runBatch(id, "record", selection, provider)
            assertEquals(expected, provider.sent.last().original.maximumOutputTokens)
        }
    }

    @Test fun `valid legacy payload near capacity validates without forcing a larger format upgrade`() {
        // Tune actual claim content, not irrelevant JSON whitespace. Each historic call remains
        // below its proposal budget, while the whole payload leaves too little room for v2 fields.
        val target = AnalysisRunCodec.MAX_PAYLOAD_BYTES - 8
        var low = 1
        var high = 2_000
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (legacyFixture(100, middle).toString().toByteArray(Charsets.UTF_8).size <= target) low = middle else high = middle - 1
        }
        val legacy = legacyFixture(100, low)
        var remaining = target - legacy.toString().toByteArray(Charsets.UTF_8).size
        val calls = legacy.getJSONArray("calls")
        for (callIndex in 0 until calls.length()) {
            val claims = calls.getJSONObject(callIndex).getJSONObject("proposal").getJSONArray("claims")
            for (claimIndex in 0 until claims.length()) {
                val claim = claims.getJSONObject(claimIndex)
                val text = claim.getString("text")
                val add = minOf(remaining, 2_000 - text.length)
                claim.put("text", text + "x".repeat(add))
                remaining -= add
            }
        }
        assertEquals(0, remaining)
        val payload = legacy.toString()
        assertEquals(target, payload.toByteArray(Charsets.UTF_8).size)
        val state = AnalysisRunCodec.decode(payload)
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.encode(state) }
        val stored = StoredAnalysisRun("synthetic-history", 1, SourceStamp("synthetic-store", 0), now, now, null, payload)
        AnalysisRunCodec.validateStored(stored)
        assertEquals(100, AnalysisRunCodec.decode(stored.payload).calls.size)
    }

    private fun legacyFixture(callCount: Int, textLength: Int): JSONObject {
        val hash = AnalysisRunCodec.sha256("x")
        val ref = EvidenceReceipt(AnalysisRunCodec.evidenceId("synthetic-record", 1, "body", 0, 1, hash),
            "synthetic-record", 1, "body", 0, 1, 1, hash)
        val proposal = BatchProposal(List(10) { ProposedClaim("x".repeat(textLength), listOf(ref.id), ClaimKind.REPORTED_EXPERIENCE) })
        val call = AnalysisCall("synthetic-batch", 1, "synthetic-provider", hash, 10, 10, listOf(ref), now,
            CallStatus.SUCCEEDED, finishedAt = now, proposal = proposal)
        val root = JSONObject(AnalysisRunCodec.encode(AnalysisRunState(AnalysisRequest("合成历史容量测试", scope), listOf(call))))
        root.withoutProfileMaintenance()
        root.getJSONObject("request").remove("profileTarget")
        val template = root.getJSONArray("calls").getJSONObject(0)
        template.remove("tokenCountKind")
        template.remove("usage")
        listOf("metricEvidence", "purpose", "reviewTarget", "review", "feedbackVersion", "reductionInputs", "reductionOutput", "redactedReductionIdentity", "reductionTarget", "reductionReviewTokens", "reductionScan", "reductionScans").forEach(template::remove)
        val calls = JSONArray()
        repeat(callCount) { index -> calls.put(JSONObject(template.toString()).put("batchKey", "synthetic-batch-$index")) }
        return root.put("formatVersion", 1).put("calls", calls)
    }

    private class PreparedProvider : AnalysisProvider {
        override val id = "synthetic-prepared-provider-v1"
        override val contextWindowTokens = 128_000
        var framing = "synthetic supplier instruction"
        var overrun = false
        val sent = mutableListOf<PreparedModelRequest>()
        override fun countInputTokens(request: ModelRequest): Int = error("Executor must use prepared accounting")
        override fun complete(request: ModelRequest): BatchProposal = error("Executor must dispatch the prepared body")
        override fun prepare(request: ModelRequest): PreparedModelRequest {
            val body = AnalysisRunCodec.json(mapOf("syntheticWrapper" to framing, "input" to request.payloadJson))
            return PreparedModelRequest(request, body, body.toByteArray(Charsets.UTF_8).size + 128, TokenCountKind.CONSERVATIVE_UTF8_BOUND)
        }
        override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
            sent += request
            val id = JSONObject(request.original.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return ModelCompletion(BatchProposal(listOf(ProposedClaim("合成待核对提案", listOf(id), ClaimKind.REPORTED_EXPERIENCE))),
                ModelUsage(request.inputTokens + if (overrun) 1 else -1, 7))
        }
    }
}
