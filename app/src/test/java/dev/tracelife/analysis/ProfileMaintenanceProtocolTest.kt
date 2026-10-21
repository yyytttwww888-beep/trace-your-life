package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.profile.*
import dev.tracelife.providers.DeepSeekCredentials
import dev.tracelife.providers.DeepSeekProvider
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
import java.time.ZoneOffset
import java.util.UUID

/** All records, decisions and provider responses in this suite are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileMaintenanceProtocolTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private val database = "synthetic-maintenance-protocol-${UUID.randomUUID()}.db"
    private val now = Instant.parse("2026-10-19T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-19")
    private val scope = AnalysisQuery(day.minusDays(6), day, "Asia/Shanghai")
    private val target = ProfileAnalysisTarget(ProfileDimension.ABILITIES_AND_STRATEGIES, ProfileLayer.CONTEXTUAL_PATTERN)
    private val selections = listOf(EvidenceSelection("a", "body"), EvidenceSelection("b", "body"))

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        repository.createRecord(RecordDraft("合成记录：熟悉主题先练习后查说明。", Occurrence(day)), "a")
        repository.createRecord(RecordDraft("合成情境：陌生主题先读说明，不能推断所有主题。", Occurrence(day.minusDays(1))), "b")
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `catalog persists only identity and strict relation matches survive reopen and idempotent retry`() {
        val old = blockedFeedback()
        val provider = Provider()
        val claim = generated(provider = provider)
        val plan = analysis.planProfileMaintenance("run", claim)
        assertEquals(1, plan.references.size)
        assertEquals(ProfileMaintenanceReferenceKind.FEEDBACK, plan.references.single().kind)
        assertFalse(JSONObject(analysis.task("run").stored.payload).getJSONArray("profileMaintenancePlans").toString().contains("合成纠正独立文字"))
        analysis.relateProfile("run", "relation", claim, plan.references.single(), provider)
        assertFalse(JSONObject(provider.relations.single().payloadJson).has("feedback"))
        assertTrue(provider.relations.single().payloadJson.contains("合成纠正独立文字"))
        val before = analysis.task("run").stored
        repository.close()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        assertEquals(plan, analysis.profileMaintenanceContext("run", claim).plan)
        analysis.relateProfile("run", "relation", claim, plan.references.single(), provider)
        assertEquals(1, provider.relations.size)
        assertEquals(before, analysis.task("run").stored)
        assertEquals(old.id, plan.references.single().id)
    }

    @Test fun `profile leaf retains shared correction when manual organization uses another dimension`() {
        val provider = Provider()
        val oldClaim = generated("old", provider)
        val feedback = AnalysisFeedback(repository, clock)
        val confirmed = feedback.respond("old", oldClaim, analysis.task("old").stored.version, ClaimFeedbackDecision.CONFIRMED).feedback
        analysis.reviewClaim("old", "review", oldClaim, provider = provider)
        val knowledge = KnowledgeLibrary(repository, clock).publish("old", oldClaim, "review", KnowledgeCategory.INSIGHT,
            analysis.task("old").stored.version)
        val otherTarget = ProfileAnalysisTarget(ProfileDimension.ENERGY_AND_RECOVERY, ProfileLayer.CONTEXTUAL_PATTERN)
        PersonalProfile(repository, clock).create(ProfileDraft(ProfileKnowledgeRef(knowledge.id, knowledge.revision), otherTarget.dimension, otherTarget.layer),
            "manual-other-dimension", "合成画像独立补充")
        feedback.update(confirmed.id, confirmed.version, analysis.task("old").stored.version,
            ClaimFeedbackDecision.CORRECTED, "合成共享纠正：这里并没有比较精力恢复。")
        analysis.start(AnalysisRequest("合成另一维度维护", scope, profileTarget = otherTarget), "run")
        analysis.synthesize("run", "generation", selections, provider)
        val claim = analysis.claimRef("run", "generation", 0)
        val plan = analysis.planProfileMaintenance("run", claim)
        assertEquals(listOf(ProfileMaintenanceReferenceKind.FEEDBACK, ProfileMaintenanceReferenceKind.PROFILE), plan.references.map { it.kind })
        analysis.relateProfile("run", "relation", claim, plan.references.single { it.kind == ProfileMaintenanceReferenceKind.PROFILE }, provider)
        val reference = JSONObject(provider.relations.single().payloadJson).getJSONObject("reference")
        assertEquals("合成画像独立补充", reference.getString("userText"))
        assertEquals("合成共享纠正：这里并没有比较精力恢复。", reference.getString("feedbackUserText"))
    }

    @Test fun `full raw scope is mandatory and another candidate scan cannot satisfy it`() {
        val provider = Provider().apply { twoClaims = true }
        val first = generated(provider = provider)
        val second = analysis.claimRef("run", "generation", 1)
        analysis.planProfileMaintenance("run", first)
        analysis.planProfileMaintenance("run", second)
        analysis.scanProfile("run", "scan-first", first, selections, provider)
        assertThrows(IllegalArgumentException::class.java) { analysis.reviewProfileMaintenance("run", "early", second, provider) }
        analysis.scanProfile("run", "scan-second-part", second, selections.take(1), provider)
        assertThrows(IllegalArgumentException::class.java) { analysis.reviewProfileMaintenance("run", "partial", second, provider) }
        assertTrue(provider.reviews.isEmpty())
        analysis.scanProfile("run", "scan-second-rest", second, selections.drop(1), provider)
        analysis.reviewProfileMaintenance("run", "complete", second, provider)
        val proof = analysis.profileMaintenanceProof("run", second)!!
        assertEquals(setOf("scan-second-part", "scan-second-rest"), proof.scans.map { it.ref.batchKey }.toSet())
    }

    @Test fun `final review rereads all original batch context including noncanonical and unrelated scan spans`() {
        val provider = Provider().apply { classification = ReductionRelevance.IRRELEVANT }
        val claim = generated(provider = provider, evidence = listOf(EvidenceSelection("a", "body", 2), EvidenceSelection("b", "body")))
        analysis.planProfileMaintenance("run", claim)
        analysis.scanProfile("run", "scan", claim, selections, provider)
        analysis.reviewProfileMaintenance("run", "final", claim, provider)
        val final = analysis.profileMaintenanceContext("run", claim).latestReview!!
        val original = analysis.task("run").state.calls.first().evidence
        assertEquals(original, final.evidence)
        assertEquals(2, JSONObject(provider.reviews.single().payloadJson).getJSONArray("evidence").length())
        assertTrue(final.profileMaintenanceProof!!.originalEvidence.containsAll(original))
        val corrupted = final.copy(evidence = final.evidence.take(1))
        val state = analysis.task("run").state
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.encode(state.copy(calls = state.calls.dropLast(1) + corrupted)) }
    }

    @Test fun `detected counterexample cannot be overwritten by a supported final response`() {
        val provider = Provider().apply { classification = ReductionRelevance.COUNTEREVIDENCE }
        val claim = generated(provider = provider)
        analysis.planProfileMaintenance("run", claim)
        analysis.scanProfile("run", "scan", claim, selections, provider)
        assertThrows(AnalysisValidationException::class.java) { analysis.reviewProfileMaintenance("run", "final", claim, provider) }
        val failed = analysis.task("run").state.calls.last()
        assertEquals(CallStatus.FAILED, failed.status)
        assertEquals("INVALID_RESPONSE", failed.failureCode)
        assertNull(failed.review)
        assertNotNull(failed.profileMaintenanceProof)
        assertNull(analysis.profileMaintenanceProof("run", claim))
        provider.verdict = ReviewVerdict.CONTRADICTED
        analysis.reviewProfileMaintenance("run", "contradicted", claim, provider)
        assertEquals(ReviewVerdict.CONTRADICTED, analysis.profileMaintenanceContext("run", claim).latestReview!!.review!!.verdict)
    }

    @Test fun `own confirmation reuses scans but invalidates previous final review`() {
        val provider = Provider()
        val claim = generated(provider = provider)
        analysis.planProfileMaintenance("run", claim)
        analysis.scanProfile("run", "scan", claim, selections, provider)
        analysis.reviewProfileMaintenance("run", "before-confirmation", claim, provider)
        AnalysisFeedback(repository, clock).respond("run", claim, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        val updated = analysis.profileMaintenanceContext("run", claim)
        assertEquals(1, updated.scans.size)
        assertNull(updated.latestReview)
        analysis.scanProfile("run", "scan", claim, selections, provider)
        assertEquals(1, provider.scans.size)
        analysis.reviewProfileMaintenance("run", "after-confirmation", claim, provider)
        assertEquals(1, analysis.profileMaintenanceContext("run", claim).latestReview!!.feedbackVersion)
        assertTrue(JSONObject(provider.reviews.last().payloadJson).getBoolean("profileMaintenance"))
        assertEquals(2, provider.reviews.size)
    }

    @Test fun `complete catalog includes unpublished correction and blocks dispatch after changes`() {
        val old = blockedFeedback()
        val provider = Provider()
        val claim = generated(provider = provider)
        val plan = analysis.planProfileMaintenance("run", claim)
        AnalysisFeedback(repository, clock).update(old.id, old.version, analysis.task("old").stored.version,
            ClaimFeedbackDecision.REJECTED, "合成更新后的独立限制")
        assertThrows(AnalysisDependencyBlockedException::class.java) { analysis.relateProfile("run", "relation", claim, plan.references.single(), provider) }
        assertTrue(provider.relations.isEmpty())
    }

    @Test fun `feedback changed during dispatch cannot save stale relation`() {
        val old = blockedFeedback()
        val provider = Provider()
        val claim = generated(provider = provider)
        val plan = analysis.planProfileMaintenance("run", claim)
        provider.onRelation = { AnalysisFeedback(repository, clock).update(old.id, old.version, analysis.task("old").stored.version,
            ClaimFeedbackDecision.REJECTED, "合成并发否定") }
        assertThrows(IllegalStateException::class.java) { analysis.relateProfile("run", "relation", claim, plan.references.single(), provider) }
        assertFalse(analysis.task("run").state.calls.any { it.purpose == CallPurpose.PROFILE_RELATION && it.status == CallStatus.SUCCEEDED })
    }

    @Test fun `deleted old machine text is not silently classified unrelated`() {
        blockedFeedback()
        analysis.delete("old", analysis.task("old").stored.version)
        val provider = Provider().apply { relation = ProfileRelationResult(ProfileRelation.UNRELATED, ProfileMaintenanceRecommendation.KEEP, "合成无关判断") }
        val claim = generated(provider = provider)
        val plan = analysis.planProfileMaintenance("run", claim)
        val leaf = repository.profileMaintenanceReference(plan.references.single())
        assertNull(leaf.claim)
        assertFalse(leaf.sourceAvailable)
        assertThrows(AnalysisValidationException::class.java) { analysis.relateProfile("run", "relation", claim, plan.references.single(), provider) }
        assertFalse(analysis.task("run").state.calls.any { it.purpose == CallPurpose.PROFILE_RELATION && it.status == CallStatus.SUCCEEDED })
    }

    @Test fun `original scan omissions and forged refs are charged invalid responses`() {
        val provider = Provider().apply { omitScanReceipt = true }
        val claim = generated(provider = provider)
        analysis.planProfileMaintenance("run", claim)
        assertThrows(AnalysisValidationException::class.java) { analysis.scanProfile("run", "scan", claim, selections, provider) }
        assertEquals("INVALID_RESPONSE", analysis.task("run").state.calls.last().failureCode)
        assertTrue(analysis.profileMaintenanceContext("run", claim).scans.isEmpty())
    }

    @Test fun `successful relationships and scanned receipts cannot be replaced through new operation keys`() {
        blockedFeedback()
        val provider = Provider()
        val claim = generated(provider = provider)
        val reference = analysis.planProfileMaintenance("run", claim).references.single()
        analysis.relateProfile("run", "relation", claim, reference, provider)
        assertThrows(IllegalArgumentException::class.java) { analysis.relateProfile("run", "new-relation", claim, reference, provider) }
        analysis.scanProfile("run", "scan", claim, selections, provider)
        assertThrows(IllegalArgumentException::class.java) { analysis.scanProfile("run", "new-scan", claim, selections.take(1), provider) }
        assertEquals(1, provider.relations.size)
        assertEquals(1, provider.scans.size)
    }

    @Test fun `stored final proof cannot omit an earlier counterexample scan even if original batch still reread`() {
        val provider = Provider().apply { classification = ReductionRelevance.COUNTEREVIDENCE; verdict = ReviewVerdict.CONTRADICTED }
        val claim = generated(provider = provider)
        analysis.planProfileMaintenance("run", claim)
        analysis.scanProfile("run", "counterexample", claim, selections.take(1), provider)
        provider.classification = ReductionRelevance.CONTEXT
        analysis.scanProfile("run", "context", claim, selections.drop(1), provider)
        analysis.reviewProfileMaintenance("run", "final", claim, provider)
        val state = analysis.task("run").state
        val final = state.calls.last()
        val proof = final.profileMaintenanceProof!!
        val truncated = final.copy(profileMaintenanceProof = proof.copy(scans = proof.scans.drop(1)))
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.encode(state.copy(calls = state.calls.dropLast(1) + truncated)) }
    }

    @Test fun `new codec keeps strict v8 compatibility and rejects maintenance smuggled into old formats`() {
        val provider = Provider()
        val claim = generated(provider = provider)
        val before = analysis.task("run").state
        val v8 = JSONObject(AnalysisRunCodec.encode(before)).withoutProfileMaintenance().put("formatVersion", 8)
        val historical = before.copy(calls = before.calls.map { it.copy(sourceFacts = null,
            review = it.review?.copy(factCheck = null)) })
        assertEquals(historical, AnalysisRunCodec.decode(v8.toString()))
        assertEquals(12, JSONObject(AnalysisRunCodec.encode(AnalysisRunCodec.decode(v8.toString()))).getInt("formatVersion"))
        analysis.planProfileMaintenance("run", claim)
        val payload = JSONObject(analysis.task("run").stored.payload)
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(payload.put("formatVersion", 8).toString()) }
        val v9Missing = JSONObject(analysis.task("run").stored.payload).apply { remove("profileMaintenancePlans") }
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(v9Missing.toString()) }
    }

    @Test fun `maintenance framing counts against default budgets and includes full Chinese fragment`() {
        repository.deleteRecord("b", 1)
        repository.updateRecord("a", 1, RecordDraft("中".repeat(1_024), Occurrence(day)))
        val provider = Provider().apply {
            framing = DeepSeekProvider(DeepSeekCredentials(context))
        }
        val claim = generated(provider = provider, evidence = selections.take(1))
        analysis.planProfileMaintenance("run", claim)
        analysis.scanProfile("run", "scan", claim, selections.take(1), provider)
        analysis.reviewProfileMaintenance("run", "final", claim, provider)
        analysis.task("run").state.calls.forEach {
            assertEquals(it.inputBytes + 1_024, it.inputTokens)
            assertTrue(it.inputTokens <= AnalysisLimits().inputTokenLimit)
        }
    }

    private fun generated(runId: String = "run", provider: Provider = Provider(), evidence: List<EvidenceSelection> = selections): ClaimRef {
        analysis.start(AnalysisRequest("合成画像维护任务", scope, profileTarget = target), runId)
        analysis.synthesize(runId, "generation", evidence, provider)
        return analysis.claimRef(runId, "generation", 0)
    }
    private fun blockedFeedback(): dev.tracelife.storage.StoredClaimFeedback {
        val claim = generated("old")
        return AnalysisFeedback(repository, clock).respond("old", claim, analysis.task("old").stored.version,
            ClaimFeedbackDecision.CORRECTED, "合成纠正独立文字：只限熟悉主题，不能泛化。").feedback
    }

    private class Provider : AnalysisProvider, SemanticReviewProvider, SemanticReductionScanProvider, SemanticProfileRelationProvider {
        override val id = "synthetic-maintenance-provider"
        override val contextWindowTokens = 100_000
        var twoClaims = false
        var omitScanReceipt = false
        var classification = ReductionRelevance.CONTEXT
        var verdict = ReviewVerdict.SUPPORTED
        var relation = ProfileRelationResult(ProfileRelation.SUPPLEMENT, ProfileMaintenanceRecommendation.NARROW, "合成新增情境与旧限制相关")
        var onRelation: () -> Unit = {}
        var framing: DeepSeekProvider? = null
        val relations = mutableListOf<ModelRequest>()
        val scans = mutableListOf<ModelRequest>()
        val reviews = mutableListOf<ModelRequest>()
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray(Charsets.UTF_8).size
        override fun prepare(request: ModelRequest) = framing?.prepare(request) ?: PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
        override fun complete(request: ModelRequest): BatchProposal {
            val evidence = JSONObject(request.payloadJson).getJSONArray("evidence")
            val claim = ProposedClaim("合成候选：本期熟悉主题的练习经历可能存在情境差异。", listOf(evidence.getJSONObject(0).getString("id")), ClaimKind.POSSIBLE_INTERPRETATION)
            return BatchProposal(if (twoClaims) listOf(claim, claim.copy(text = "合成第二候选：本期只作近期探索。")) else listOf(claim))
        }
        override fun prepareReview(request: ModelRequest) = framing?.prepareReview(request) ?: PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            reviews += request.original
            val ids = evidenceIds(request.original)
            return ReviewCompletion(when (verdict) {
                ReviewVerdict.CONTRADICTED -> ClaimReview(verdict, contradictingEvidenceIds = ids.take(1))
                ReviewVerdict.SUPPORTED -> ClaimReview(verdict, supportingEvidenceIds = ids.take(1))
                else -> ClaimReview(verdict, unknowns = listOf("合成上下文缺口"))
            })
        }
        override fun prepareScan(request: ModelRequest) = framing?.prepareScan(request) ?: PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
            scans += request.original
            val ids = evidenceIds(request.original).let { if (omitScanReceipt) it.take(1) else it }
            return ReductionScanCompletion(ReductionScanResult(ids.map { ReductionFinding(it, classification, "合成片段分类") }))
        }
        override fun prepareRelation(request: ModelRequest) = framing?.prepareRelation(request) ?: PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
        override fun relatePrepared(request: PreparedModelRequest): ProfileRelationCompletion {
            relations += request.original
            onRelation()
            return ProfileRelationCompletion(relation)
        }
        private fun evidenceIds(request: ModelRequest): List<String> {
            val items = JSONObject(request.payloadJson).getJSONArray("evidence")
            return (0 until items.length()).map { items.getJSONObject(it).getString("id") }
        }
    }
}
