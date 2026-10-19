package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.profile.ProfileAnalysisTarget
import dev.tracelife.profile.ProfileDimension
import dev.tracelife.profile.ProfileLayer
import dev.tracelife.providers.DeepSeekCredentials
import dev.tracelife.providers.DeepSeekProvider
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
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic records only. This tests the same public executor used by production workflows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileAnalysisProtocolTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private val database = "synthetic-profile-analysis-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-19")
    private val scope = AnalysisQuery(date.minusDays(6), date, "Asia/Shanghai")
    private val target = ProfileAnalysisTarget(ProfileDimension.ABILITIES_AND_STRATEGIES, ProfileLayer.CONTEXTUAL_PATTERN)
    private val selections = listOf(EvidenceSelection("practice", "body"), EvidenceSelection("exception", "body"))

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        repository.createRecord(RecordDraft("合成记录：今天先做练习，再看说明，感觉更容易理解。", Occurrence(date)), "practice")
        repository.createRecord(RecordDraft("合成反例：陌生主题不看说明直接练习，反而难以开始。", Occurrence(date.minusDays(1))), "exception")
    }

    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `fixed target survives reopen and cannot change under the same run identity`() {
        val request = AnalysisRequest("合成画像候选", scope, profileTarget = target)
        analysis.start(request, "run")
        val provider = Provider()
        analysis.synthesize("run", "batch", selections, provider)
        val envelope = JSONObject(provider.generated.single().payloadJson)
        assertEquals(7, envelope.getInt("protocolVersion"))
        assertEquals(target.dimension.name, envelope.getJSONObject("profileTarget").getString("dimension"))
        assertEquals(target.layer.name, envelope.getJSONObject("profileTarget").getString("layer"))
        assertTrue(envelope.getString("system").contains("不得自动形成 LONG_TERM"))
        assertFalse(envelope.getJSONObject("coverage").getBoolean("wholeScopeReviewed"))
        assertFalse(envelope.getJSONObject("coverage").getBoolean("crossBatchIntegrated"))
        assertEquals(0, repository.knowledgeHeads(0, 100).size)
        assertEquals(0, repository.profileHeads(0, 100).size)
        repository.close()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        assertEquals(request, analysis.task("run").state.request)
        assertEquals(9, JSONObject(analysis.task("run").stored.payload).getInt("formatVersion"))
        analysis.synthesize("run", "batch", selections, provider)
        assertEquals(1, provider.generated.size)
        assertThrows(IllegalArgumentException::class.java) {
            analysis.start(request.copy(profileTarget = target.copy(layer = ProfileLayer.RECENT_STATE)), "run")
        }
    }

    @Test fun `profile review rereads uncited context with the exact saved target`() {
        start()
        val provider = Provider()
        analysis.synthesize("run", "batch", selections, provider)
        val ref = analysis.claimRef("run", "batch", 0)
        assertEquals(1, analysis.task("run").state.calls.single().proposal!!.claims.single().evidenceIds.size)
        analysis.reviewClaim("run", "review", ref, provider = provider)
        val input = JSONObject(provider.reviewed.single().payloadJson)
        assertEquals(7, input.getInt("protocolVersion"))
        assertEquals(target.layer.name, input.getJSONObject("profileTarget").getString("layer"))
        assertTrue(input.getString("system").contains("未被候选引用的反例"))
        assertEquals(2, input.getJSONArray("evidence").length())
        assertTrue(input.getJSONArray("evidence").getJSONObject(1).getString("text").contains("合成反例"))
        val state = analysis.task("run").state
        assertEquals(state.calls.first().evidence, state.calls.last().evidence)
        val truncated = state.calls.last().copy(evidence = state.calls.last().evidence.take(1))
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.encode(state.copy(calls = listOf(state.calls.first(), truncated)))
        }
    }

    @Test fun `profile task rejects extraction metric and reduction before preparation or dispatch`() {
        start()
        val provider = Provider()
        assertThrows(IllegalArgumentException::class.java) { analysis.runBatch("run", "extract", selections, provider) }
        assertThrows(IllegalArgumentException::class.java) { analysis.analyzeMetrics("run", "metric", provider) }
        assertThrows(IllegalArgumentException::class.java) { analysis.reduce("run", "reduce", emptyList(), provider) }
        assertEquals(0, provider.preparations)
        assertTrue(analysis.task("run").state.calls.isEmpty())
        analysis.synthesize("run", "batch", selections, provider)
        val state = analysis.task("run").state
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.encode(state.copy(calls = listOf(state.calls.single().copy(purpose = CallPurpose.EXTRACTION))))
        }
        val smuggled = JSONObject(analysis.task("run").stored.payload).apply {
            getJSONArray("calls").getJSONObject(0).put("purpose", "EXTRACTION")
        }
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(smuggled.toString()) }
    }

    @Test fun `profile candidates remain interpretations and invalid model output is a charged failed call`() {
        start()
        val provider = Provider().apply { kind = ClaimKind.REPORTED_EXPERIENCE }
        assertThrows(AnalysisValidationException::class.java) { analysis.synthesize("run", "batch", selections, provider) }
        val call = analysis.task("run").state.calls.single()
        assertEquals(CallStatus.FAILED, call.status)
        assertEquals("INVALID_RESPONSE", call.failureCode)
        assertNull(call.proposal)
        assertEquals(1, provider.generated.size)
        assertEquals(0L, PeriodSynthesisProcessor(analysis).inspect("run").coverage!!.completedTextCodePoints)
    }

    @Test fun `profile instructions count against the same byte and cumulative call budgets`() {
        analysis.start(AnalysisRequest("合成预算", scope, AnalysisLimits(maxInputBytes = 256), profileTarget = target), "tiny")
        val provider = Provider()
        assertThrows(AnalysisBudgetException::class.java) { analysis.synthesize("tiny", "batch", selections, provider) }
        assertTrue(provider.generated.isEmpty())
        assertTrue(analysis.task("tiny").state.calls.isEmpty())
        analysis.start(AnalysisRequest("合成预算", scope, AnalysisLimits(maxCalls = 1), profileTarget = target), "run")
        analysis.synthesize("run", "batch", selections, provider)
        val call = analysis.task("run").state.calls.single()
        assertEquals(provider.generated.single().payloadJson.toByteArray(Charsets.UTF_8).size, call.inputBytes)
        assertThrows(AnalysisBudgetException::class.java) {
            analysis.reviewClaim("run", "review", analysis.claimRef("run", "batch", 0), provider = provider)
        }
        assertTrue(provider.reviewed.isEmpty())
    }

    @Test fun `default budgets can generate and fully review a maximum Chinese fragment with actual DeepSeek framing`() {
        repository.updateRecord("practice", 1, RecordDraft("中".repeat(1_024), Occurrence(date)))
        val request = AnalysisRequest("合成预算验收：" + "请描述本期记录中有情境限制的学习策略，保留未知。".repeat(4), scope,
            profileTarget = target)
        analysis.start(request, "run")
        // Only prepare() is used. No credential is loaded and all dispatches remain fabricated.
        val provider = Provider(DeepSeekProvider(DeepSeekCredentials(context)))
        analysis.synthesize("run", "batch", selections.take(1), provider)
        analysis.reviewClaim("run", "review", analysis.claimRef("run", "batch", 0), provider = provider)
        val calls = analysis.task("run").state.calls
        assertEquals(2, calls.size)
        assertEquals(calls.first().evidence, calls.last().evidence)
        assertEquals(1_024, calls.first().evidence.single().end)
        calls.forEach { call ->
            assertEquals(CallStatus.SUCCEEDED, call.status)
            assertEquals(TokenCountKind.CONSERVATIVE_UTF8_BOUND, call.tokenCountKind)
            assertEquals(call.inputBytes + 1_024, call.inputTokens)
            assertTrue(call.inputTokens <= request.limits.inputTokenLimit)
        }
    }

    @Test fun `legacy v1 through v7 remain strict and never gain a profile target`() {
        val current = AnalysisRunCodec.encode(AnalysisRunState(AnalysisRequest("合成旧任务", scope)))
        for (version in 1..7) {
            val old = JSONObject(current).apply {
                put("formatVersion", version)
                withoutProfileMaintenance()
                getJSONObject("request").remove("profileTarget")
            }
            assertNull(AnalysisRunCodec.decode(old.toString()).request.profileTarget)
            val upgraded = JSONObject(AnalysisRunCodec.encode(AnalysisRunCodec.decode(old.toString())))
            assertEquals(9, upgraded.getInt("formatVersion"))
            assertTrue(upgraded.getJSONObject("request").isNull("profileTarget"))
            old.getJSONObject("request").put("profileTarget", JSONObject.NULL)
            assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(old.toString()) }
        }
        val missing = JSONObject(current).apply { getJSONObject("request").remove("profileTarget") }
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(missing.toString()) }
    }

    @Test fun `long term malformed and unknown profile targets are rejected structurally`() {
        assertThrows(IllegalArgumentException::class.java) { target.copy(layer = ProfileLayer.LONG_TERM) }
        val payload = AnalysisRunCodec.encode(AnalysisRunState(AnalysisRequest("合成目标", scope, profileTarget = target)))
        listOf(
            JSONObject().put("dimension", target.dimension.name).put("layer", "LONG_TERM"),
            JSONObject().put("dimension", "PERSONALITY_SCORE").put("layer", target.layer.name),
            JSONObject().put("dimension", target.dimension.name).put("layer", target.layer.name).put("confidence", 93),
            JSONObject().put("dimension", target.dimension.name),
            "CONTEXTUAL_PATTERN",
        ).forEach { invalid ->
            val root = JSONObject(payload).apply { getJSONObject("request").put("profileTarget", invalid) }
            assertThrows(RuntimeException::class.java) { AnalysisRunCodec.decode(root.toString()) }
        }
    }

    @Test fun `ordinary synthesis and review retain their existing wire protocol`() {
        analysis.start(AnalysisRequest("合成普通综合", scope), "run")
        val provider = Provider()
        analysis.synthesize("run", "batch", selections, provider)
        analysis.reviewClaim("run", "review", analysis.claimRef("run", "batch", 0), provider = provider)
        val generated = JSONObject(provider.generated.single().payloadJson)
        val reviewed = JSONObject(provider.reviewed.single().payloadJson)
        assertEquals(3, generated.getInt("protocolVersion"))
        assertEquals(2, reviewed.getInt("protocolVersion"))
        assertFalse(generated.has("profileTarget"))
        assertFalse(reviewed.has("profileTarget"))
        assertFalse(generated.getString("system").contains("本次生成画像候选"))
    }

    private fun start() = analysis.start(AnalysisRequest("合成画像候选", scope, profileTarget = target), "run")

    private class Provider(private val wireAdapter: DeepSeekProvider? = null) : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-profile-provider"
        override val contextWindowTokens = 100_000
        var preparations = 0
        var kind = ClaimKind.POSSIBLE_INTERPRETATION
        val generated = mutableListOf<ModelRequest>()
        val reviewed = mutableListOf<ModelRequest>()
        override fun countInputTokens(request: ModelRequest): Int { preparations++; return 100 }
        override fun prepare(request: ModelRequest) = wireAdapter?.prepare(request) ?: super<AnalysisProvider>.prepare(request)
        override fun complete(request: ModelRequest): BatchProposal {
            generated += request
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return BatchProposal(listOf(ProposedClaim("合成候选：在本次练习情境中，先尝试可能帮助理解，陌生主题仍需要说明。", listOf(id), kind)), listOf("合成局限：未检查其他时期。"))
        }
        override fun prepareReview(request: ModelRequest) = wireAdapter?.prepareReview(request)
            ?: PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            reviewed += request.original
            val id = JSONObject(request.original.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, supportingEvidenceIds = listOf(id)))
        }
    }
}
