package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.providers.DeepSeekCredentials
import dev.tracelife.providers.DeepSeekHttpResponse
import dev.tracelife.providers.DeepSeekModel
import dev.tracelife.providers.DeepSeekProvider
import dev.tracelife.providers.DeepSeekTransport
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
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
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.KeyGenerator

/** Public workflow, real DeepSeek wire preparation and synthetic HTTP responses; no network or real key.
 * The fabricated model supports every target so publication failures must come from source guards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StructuredEvidenceFlowTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var credentials: DeepSeekCredentials
    private lateinit var workspace: File
    private val database = "synthetic-structured-flow-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-21T03:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-18")
    private val scope = AnalysisQuery(date, date, "Asia/Shanghai")
    private val selections = listOf(EvidenceSelection("source", "body"))
    private val source = "合成资料：我计划下周每次练习五分钟，到今天还没试这个安排。"
    private val faithful = "记录计划下周每次练习五分钟，这个安排尚未尝试。"
    private val waiting = "原记录中的计划是先等待五分钟再开始练习；这个计划还没有执行。"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        workspace = File(context.cacheDir, "synthetic-structured-flow-${UUID.randomUUID()}").apply { mkdirs() }
        val wrapping = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        credentials = DeepSeekCredentials(File(workspace, "credentials"), context.packageName) { wrapping }
        credentials.configure("synthetic-structured-flow-key".toCharArray())
        repository.createRecord(RecordDraft(source, Occurrence(date, zoneId = "Asia/Shanghai")), "source")
    }

    @After fun cleanup() {
        repository.close()
        context.deleteDatabase(database)
        credentials.clear()
        workspace.deleteRecursively()
    }

    @Test fun `duration changed into waiting cannot publish even when actual provider returns support`() {
        val wire = SupportingWire(waiting)
        val claim = generate(wire)
        val review = analysis.reviewClaim("run", "review", claim, provider = wire.provider)

        assertNotEquals(ReviewVerdict.SUPPORTED, review.verdict)
        assertNotNull(review.factCheck)
        assertTrue("The source conflict must remain visible", review.unknowns.isNotEmpty() || review.issues.isNotEmpty())
        assertEquals(CallStatus.SUCCEEDED, analysis.currentReview("run", claim)!!.status)
        assertThrows(IllegalArgumentException::class.java) { publish(claim) }
        assertEquals(0, KnowledgeLibrary(repository, clock).index().total)
        assertEquals(2, wire.sent.size)
        assertEquals(listOf(CallPurpose.SYNTHESIS, CallPurpose.REVIEW), analysis.task("run").state.calls.map { it.purpose })
    }

    @Test fun `faithful duration and explicitly new waiting suggestion can both publish`() {
        val suggestion = "下次可先等待五分钟再开始练习，并记录是否开始；这是尚未尝试的新建议，不代表原记录的计划或已经有效。"
        listOf(faithful, suggestion).forEachIndexed { index, target ->
            val runId = "control-$index"
            val wire = SupportingWire(target)
            val claim = generate(wire, runId)
            val review = analysis.reviewClaim(runId, "review", claim, provider = wire.provider)
            assertEquals(target, ReviewVerdict.SUPPORTED, review.verdict)
            assertNotNull(review.factCheck)
            assertEquals(target, publish(claim, runId).claim.text)
            assertEquals(2, wire.sent.size)
            assertEquals(2, analysis.task(runId).state.calls.size)
        }
        assertEquals(2, KnowledgeLibrary(repository, clock).index().total)
    }

    @Test fun `facts and unchanged original evidence enter both measured requests without another dispatch`() {
        val wire = SupportingWire(faithful)
        val claim = generate(wire)
        analysis.reviewClaim("run", "review", claim, provider = wire.provider)
        val calls = analysis.task("run").state.calls

        assertEquals(2, wire.sent.size)
        assertEquals(2, calls.size)
        calls.zip(wire.sent).forEach { (call, body) ->
            val envelope = userEnvelope(body)
            assertNotNull(call.sourceFacts)
            val persistedFacts = requireNotNull(call.sourceFacts)
            val wireFacts = envelope.getJSONObject("sourceFacts")
            val wireSources = wireFacts.getJSONArray("sources")
            assertEquals(persistedFacts.sources.size, wireSources.length())
            persistedFacts.sources.forEachIndexed { index, factSource ->
                val descriptor = wireSources.getJSONObject(index)
                assertEquals(setOf("id", "field"), descriptor.keys().asSequence().toSet())
                assertEquals(factSource.evidenceId, descriptor.getString("id"))
                assertEquals(factSource.field, descriptor.getString("field"))
            }
            val expectedFacts = JSONObject(AnalysisRunCodec.json(EvidenceFacts.packMap(persistedFacts))).getJSONArray("facts")
            assertEquals(expectedFacts.toString(), wireFacts.getJSONArray("facts").toString())
            val evidence = envelope.getJSONArray("evidence")
            assertEquals(1, evidence.length())
            assertEquals(source, evidence.getJSONObject(0).getString("text"))
            assertEquals(listOf(evidence.getJSONObject(0).getString("id")), call.evidence.map { it.id })
            assertEquals(body.toByteArray(Charsets.UTF_8).size, call.inputBytes)
            assertEquals(call.inputBytes + 1_024, call.inputTokens)
            assertEquals(AnalysisRunCodec.sha256(body), call.requestSha256)
            assertEquals(TokenCountKind.CONSERVATIVE_UTF8_BOUND, call.tokenCountKind)
            assertEquals(ModelUsage(100, 100), call.usage)
        }
        assertEquals(calls.first().sourceFacts, calls.last().sourceFacts)
        assertEquals(ReviewVerdict.SUPPORTED, calls.last().review!!.verdict)
    }

    @Test fun `complete wire including facts must fit budget before transport dispatch`() {
        val baseline = SupportingWire(faithful)
        generate(baseline)
        val body = baseline.sent.single()
        val wireWithoutFacts = JSONObject(body)
        val messages = wireWithoutFacts.getJSONArray("messages")
        val userIndex = (0 until messages.length()).single { messages.getJSONObject(it).getString("role") == "user" }
        val withoutFacts = userEnvelope(body).apply { remove("sourceFacts") }
        messages.getJSONObject(userIndex).put("content", withoutFacts.toString())
        val limit = body.toByteArray(Charsets.UTF_8).size - 1
        assertTrue("This budget would fit if facts were omitted", wireWithoutFacts.toString().toByteArray(Charsets.UTF_8).size < limit)

        val bounded = SupportingWire(faithful)
        analysis.start(AnalysisRequest(QUESTION, scope, AnalysisLimits(maxInputBytes = limit, maxCalls = 2)), "bounded")
        assertThrows(AnalysisBudgetException::class.java) {
            analysis.synthesize("bounded", "generate", selections, bounded.provider)
        }
        assertTrue(bounded.sent.isEmpty())
        assertTrue(analysis.task("bounded").state.calls.isEmpty())
    }

    @Test fun `reopening preserves facts and blocking proof and cached review does not dispatch again`() {
        val wire = SupportingWire(waiting)
        val claim = generate(wire)
        val review = analysis.reviewClaim("run", "review", claim, provider = wire.provider)
        val calls = analysis.task("run").state.calls
        assertNotNull(calls.first().sourceFacts)
        assertNotNull(review.factCheck)

        repository.close()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        assertEquals(calls, analysis.task("run").state.calls)
        assertEquals(review, analysis.reviewClaim("run", "review", claim, provider = wire.provider))
        assertEquals(2, wire.sent.size)
        assertThrows(IllegalArgumentException::class.java) { publish(claim) }
    }

    @Test fun `source revision prevents review from consuming an older fact pack`() {
        val wire = SupportingWire(faithful)
        val claim = generate(wire)
        assertNotNull(analysis.task("run").state.calls.single().sourceFacts)
        repository.updateRecord("source", 1, RecordDraft("合成修订：实际计划是先等待五分钟再练习。", Occurrence(date)))

        assertEquals(AnalysisStatus.STALE, analysis.task("run").status)
        assertThrows(StaleSnapshotException::class.java) {
            analysis.reviewClaim("run", "review", claim, provider = wire.provider)
        }
        assertEquals(1, wire.sent.size)
        assertEquals(0, KnowledgeLibrary(repository, clock).index().total)
    }

    @Test fun `deleting source during review erases facts and rejects late model support`() {
        val wire = SupportingWire(faithful)
        val claim = generate(wire)
        wire.beforeReview = { repository.deleteRecord("source", 1) }

        assertThrows(StaleSnapshotException::class.java) {
            analysis.reviewClaim("run", "review", claim, provider = wire.provider)
        }
        assertEquals(2, wire.sent.size)
        assertTrue(analysis.tasks().isEmpty())
        assertTrue(repository.captureSnapshot(emptyDirectory("deleted")).analysisRuns.isEmpty())
        assertNull(repository.record("source"))
        assertEquals(0, KnowledgeLibrary(repository, clock).index().total)
    }

    @Test fun `snapshot restore retains structured proof and deletion fences prevent its return`() {
        val wire = SupportingWire(faithful)
        val claim = generate(wire)
        analysis.reviewClaim("run", "review", claim, provider = wire.provider)
        val knowledge = publish(claim)
        val files = emptyDirectory("snapshot")
        val snapshot = repository.captureSnapshot(files)
        val originalCalls = analysis.task("run").state.calls
        val restoredDatabase = "synthetic-restored-facts-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, restoredDatabase).use { restored ->
                restored.restoreSnapshot(snapshot, files, restored.currentGeneration())
                val restoredAnalysis = ControlledAnalysis(restored, clock)
                assertEquals(originalCalls, restoredAnalysis.task("run").state.calls)
                assertEquals(knowledge.review.factCheck, restored.knowledgeRevision(knowledge.id)!!.review.factCheck)
                assertNotNull(restoredAnalysis.task("run").state.calls.last().review!!.factCheck)
                assertEquals(AnalysisStatus.STALE, restoredAnalysis.task("run").status)
                assertEquals(2, wire.sent.size)
            }
        } finally { context.deleteDatabase(restoredDatabase) }

        repository.deleteRecord("source", 1)
        repository.restoreSnapshot(snapshot, files, repository.currentGeneration(), replaceExisting = true)
        assertNull(repository.record("source"))
        assertTrue(analysis.tasks().isEmpty())
        assertNull(repository.knowledgeRevision(knowledge.id))
        assertTrue(repository.captureSnapshot(emptyDirectory("after-restore")).analysisRuns.isEmpty())
        assertEquals(2, wire.sent.size)
    }

    private fun generate(wire: SupportingWire, runId: String = "run"): ClaimRef {
        analysis.start(AnalysisRequest(QUESTION, scope, AnalysisLimits(maxCalls = 2)), runId)
        analysis.synthesize(runId, "generate", selections, wire.provider)
        val claim = analysis.claimRef(runId, "generate", 0)
        AnalysisFeedback(repository, clock).respond(runId, claim, analysis.task(runId).stored.version,
            ClaimFeedbackDecision.CONFIRMED, "合成流程确认，不表示模型陈述真实。")
        return claim
    }

    private fun publish(claim: ClaimRef, runId: String = "run") = KnowledgeLibrary(repository, clock).publish(
        runId, claim, "review", KnowledgeCategory.INSIGHT, analysis.task(runId).stored.version)

    private fun emptyDirectory(name: String) = File(workspace, name).apply { check(mkdirs()) }

    private fun userEnvelope(body: String): JSONObject {
        val messages = JSONObject(body).getJSONArray("messages")
        return JSONObject((0 until messages.length()).map { messages.getJSONObject(it) }
            .single { it.getString("role") == "user" }.getString("content"))
    }

    private inner class SupportingWire(private val target: String) {
        val sent = mutableListOf<String>()
        var beforeReview: () -> Unit = {}
        val provider = DeepSeekProvider(credentials, DeepSeekModel.FLASH, DeepSeekTransport { body, _ ->
            sent += body
            val payload = userEnvelope(body)
            val evidence = payload.getJSONArray("evidence").getJSONObject(0)
            val content = if (payload.getString("purpose") == "REVIEW") {
                beforeReview()
                val parts = payload.getJSONObject("target").getJSONArray("parts")
                mapOf("checks" to (0 until parts.length()).map { index -> mapOf(
                    "partId" to parts.getJSONObject(index).getString("partId"),
                    "statementType" to "REPORTED_FACT", "status" to "SUPPORTED",
                    "reason" to "合成模型无条件支持，用于验证程序独立门禁。",
                    "anchors" to listOf(mapOf("evidenceId" to evidence.getString("id"),
                        "field" to "/text", "quote" to evidence.getString("text"))),
                ) }, "issues" to emptyList<String>(), "unknowns" to emptyList<String>(), "verdict" to "SUPPORTED")
            } else mapOf("claims" to listOf(mapOf("text" to target,
                "evidenceIds" to listOf(evidence.getString("id")), "kind" to "POSSIBLE_INTERPRETATION")),
                "unknowns" to emptyList<String>())
            DeepSeekHttpResponse(200, AnalysisRunCodec.json(mapOf(
                "object" to "chat.completion", "model" to DeepSeekModel.FLASH.apiId,
                "choices" to listOf(mapOf("index" to 0, "finish_reason" to "stop",
                    "message" to mapOf("role" to "assistant", "content" to AnalysisRunCodec.json(content)))),
                "usage" to mapOf("prompt_tokens" to 100, "completion_tokens" to 100, "total_tokens" to 200),
            )).toByteArray(Charsets.UTF_8))
        })
    }

    private companion object { const val QUESTION = "整理这份合成计划，保留实际时长、执行状态和建议边界。" }
}
