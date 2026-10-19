package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.StoredClaimFeedback
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

/** Synthetic records, decisions and model responses only; never reads a key or uses a network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SemanticReviewTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var database: String
    private val clock = MutableClock(Instant.parse("2026-10-17T03:00:00Z"))
    private val scope = AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai")
    private val extraction = SyntheticExtraction()
    private val reviewer = SyntheticReview()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        database = "synthetic-review-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        repository.createRecord(RecordDraft("合成：周一学习了方法。但是周二并没有实践。", Occurrence(scope.from)), "note")
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `review rereads original source and persists independent outcome with exact wire accounting`() {
        val ref = start()
        val result = analysis.reviewClaim("run", "review", ref, provider = reviewer)
        val sent = reviewer.sent.single()
        val input = JSONObject(sent.original.payloadJson)
        assertEquals("合成：周一学习了方法。但是周二并没有实践。", input.getJSONArray("evidence").getJSONObject(0).getString("text"))
        assertEquals(ref.claimSha256, input.getJSONObject("target").getString("claimSha256"))
        assertFalse(input.getJSONObject("coverage").getBoolean("counterevidenceSearchExhaustive"))
        assertEquals(ReviewVerdict.SUPPORTED, result.verdict)
        val call = analysis.currentReview("run", ref)!!
        assertEquals(CallPurpose.REVIEW, call.purpose)
        assertNull(call.proposal)
        assertEquals(ref, call.reviewTarget)
        assertEquals(sent.payloadJson.toByteArray(Charsets.UTF_8).size, call.inputBytes)
        assertEquals(AnalysisRunCodec.sha256(sent.payloadJson), call.requestSha256)
        assertEquals(ModelUsage(10, 3), call.usage)
        assertEquals(AnalysisStatus.NEEDS_REVIEW, analysis.task("run").status)
        repository.close()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        assertEquals(call, analysis.currentReview("run", ref))
        assertEquals(result, analysis.reviewClaim("run", "review", ref, provider = reviewer))
        assertEquals(1, reviewer.sent.size)
    }

    @Test fun `context and counterexample fragments never count as successful extraction coverage`() {
        val ref = start(AnalysisLimits(fragmentCodePoints = 8))
        reviewer.answer = { input ->
            val evidence = input.getJSONArray("evidence")
            ClaimReview(ReviewVerdict.CONTRADICTED, contradictingEvidenceIds = listOf(evidence.getJSONObject(1).getString("id")),
                issues = listOf(ReviewIssue.COUNTEREVIDENCE_OMISSION))
        }
        analysis.reviewClaim("run", "review", ref, listOf(EvidenceSelection("note", "body", 16)), reviewer)
        assertEquals(8, analysis.coveragePage("run").records.single().completedCodePoints)
        val report = AnalysisBatchProcessor(analysis).process("run", extraction, 1)
        assertEquals(16L, report.coverage!!.completedTextCodePoints)
        assertEquals(8, analysis.task("run").state.calls.last().evidence.single().start)
    }

    @Test fun `target identity scope and mandatory evidence cannot be bypassed`() {
        repository.createRecord(RecordDraft("合成范围外记录", Occurrence(scope.from.minusDays(3))), "outside")
        val ref = start()
        assertThrows(IllegalArgumentException::class.java) { analysis.reviewClaim("run", "bad", ref.copy(claimSha256 = "0".repeat(64)), provider = reviewer) }
        assertThrows(IllegalArgumentException::class.java) { analysis.reviewClaim("run", "bad", ref, listOf(EvidenceSelection("outside", "body")), reviewer) }
        assertThrows(IllegalArgumentException::class.java) { analysis.claimRef("run", "extract", 99) }
        assertTrue(reviewer.sent.isEmpty())
        assertEquals(1, analysis.task("run").state.calls.size)
    }

    @Test fun `every cited original fragment is mandatory even when extra context is empty`() {
        repository.createRecord(RecordDraft("合成另一原文", Occurrence(scope.from)), "other")
        analysis.start(AnalysisRequest("合成多来源核对", scope), "run")
        extraction.allReferences = true
        analysis.runBatch("run", "extract", listOf(EvidenceSelection("note", "body"), EvidenceSelection("other", "body")), extraction)
        val ref = analysis.claimRef("run", "extract", 0)
        analysis.reviewClaim("run", "review", ref, provider = reviewer)
        assertEquals(setOf("note", "other"), analysis.currentReview("run", ref)!!.evidence.map { it.recordId }.toSet())
    }

    @Test fun `references from another successful call cannot be forged into a review`() {
        repository.createRecord(RecordDraft("合成另一原文", Occurrence(scope.from)), "other")
        val ref = start()
        analysis.runBatch("run", "other", listOf(EvidenceSelection("other", "body")), extraction)
        val otherId = analysis.task("run").state.calls.last().evidence.single().id
        reviewer.answer = { ClaimReview(ReviewVerdict.SUPPORTED, listOf(otherId)) }
        assertThrows(AnalysisValidationException::class.java) { analysis.reviewClaim("run", "review", ref, provider = reviewer) }
        assertEquals("INVALID_RESPONSE", analysis.task("run").state.calls.last().failureCode)
        assertNull(analysis.currentReview("run", ref))
    }

    @Test fun `invalid support verdict contradiction overlap and output size are rejected`() {
        val ref = start(AnalysisLimits(maxOutputBytes = 256))
        val bad = listOf<(JSONObject) -> ClaimReview>(
            { ClaimReview(ReviewVerdict.SUPPORTED) },
            { input -> val id = evidenceId(input); ClaimReview(ReviewVerdict.SUPPORTED, listOf(id), listOf(id)) },
            { ClaimReview(ReviewVerdict.CONTRADICTED) },
            { ClaimReview(ReviewVerdict.INSUFFICIENT_CONTEXT) },
            { input -> ClaimReview(ReviewVerdict.SUPPORTED, listOf(evidenceId(input)), issues = listOf(ReviewIssue.CAUSAL_OVERREACH)) },
            { ClaimReview(ReviewVerdict.UNSUPPORTED, unknowns = listOf("合成未知".repeat(200))) },
        )
        bad.forEachIndexed { index, value ->
            reviewer.answer = value
            assertThrows(AnalysisValidationException::class.java) { analysis.reviewClaim("run", "bad-$index", ref, provider = reviewer) }
        }
        assertTrue(analysis.task("run").state.calls.drop(1).all { it.status == CallStatus.FAILED && it.review == null })
    }

    @Test fun `review obeys prepared request budget and shared cumulative attempt budget`() {
        val ref = start(AnalysisLimits(maxCalls = 2))
        reviewer.wrapper = "x".repeat(70_000)
        assertThrows(AnalysisBudgetException::class.java) { analysis.reviewClaim("run", "large", ref, provider = reviewer) }
        assertEquals(1, analysis.task("run").state.calls.size)
        assertTrue(reviewer.sent.isEmpty())
        reviewer.wrapper = "synthetic review envelope"
        analysis.reviewClaim("run", "review", ref, provider = reviewer)
        assertThrows(AnalysisBudgetException::class.java) { analysis.reviewClaim("run", "another", ref, provider = reviewer) }
        assertEquals(1, reviewer.sent.size)
    }

    @Test fun `failed review retries exact request and exposes only safe failure code`() {
        val ref = start()
        reviewer.before = { throw AnalysisProviderException(ProviderFailureCode.RATE_LIMIT) }
        assertThrows(AnalysisValidationException::class.java) { analysis.reviewClaim("run", "review", ref, provider = reviewer) }
        assertEquals("PROVIDER_RATE_LIMIT", analysis.task("run").state.calls.last().failureCode)
        reviewer.before = {}
        analysis.reviewClaim("run", "review", ref, provider = reviewer)
        assertEquals(2, analysis.task("run").state.calls.last().attempt)
        reviewer.wrapper = "changed"
        assertThrows(AnalysisConflictException::class.java) { analysis.reviewClaim("run", "review", ref, provider = reviewer) }
        assertEquals(2, reviewer.sent.size)
    }

    @Test fun `interrupted review requires recovery and resumes after repository reopen`() {
        val ref = start()
        reviewer.before = { throw AssertionError("synthetic worker stopped") }
        assertThrows(AssertionError::class.java) { analysis.reviewClaim("run", "review", ref, provider = reviewer) }
        assertEquals(CallStatus.IN_FLIGHT, analysis.task("run").state.calls.last().status)
        reviewer.before = {}
        assertThrows(AnalysisConflictException::class.java) { analysis.reviewClaim("run", "review", ref, provider = reviewer) }
        repository.close()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        analysis.recoverInterrupted("run")
        analysis.reviewClaim("run", "review", ref, provider = reviewer)
        assertEquals(listOf(CallStatus.INTERRUPTED, CallStatus.SUCCEEDED), analysis.task("run").state.calls.drop(1).map { it.status })
    }

    @Test fun `source revision and source deletion during review prevent late results`() {
        val ref = start()
        reviewer.before = { repository.updateRecord("note", 1, RecordDraft("合成已修改", Occurrence(scope.from))) }
        assertThrows(StaleSnapshotException::class.java) { analysis.reviewClaim("run", "review", ref, provider = reviewer) }
        assertEquals(AnalysisStatus.STALE, analysis.task("run").status)
        assertThrows(StaleSnapshotException::class.java) { analysis.currentReview("run", ref) }
        val newRef = start(runId = "next")
        reviewer.before = { repository.deleteRecord("note", 2) }
        assertThrows(StaleSnapshotException::class.java) { analysis.reviewClaim("next", "review", newRef, provider = reviewer) }
        assertTrue(analysis.tasks().isEmpty())
    }

    @Test fun `expiry invalidates completed and in flight review even without source writes`() {
        val ref = start(expiresAt = clock.instant().plusSeconds(60))
        analysis.reviewClaim("run", "review", ref, provider = reviewer)
        clock.value = clock.instant().plusSeconds(60)
        assertThrows(StaleSnapshotException::class.java) { analysis.currentReview("run", ref) }
        assertThrows(StaleSnapshotException::class.java) { analysis.reviewClaim("run", "review", ref, provider = reviewer) }
    }

    @Test fun `feedback changes invalidate cache and deletion does not restore version zero checks`() {
        val ref = start()
        analysis.reviewClaim("run", "review", ref, provider = reviewer)
        val feedback = feedback(ref, ClaimFeedbackDecision.REJECTED)
        assertNull(analysis.currentReview("run", ref))
        assertThrows(AnalysisConflictException::class.java) { analysis.reviewClaim("run", "review", ref, provider = reviewer) }
        analysis.reviewClaim("run", "new-review", ref, provider = reviewer)
        val input = JSONObject(reviewer.sent.last().original.payloadJson)
        assertEquals("REJECTED", input.getJSONObject("feedback").getString("decision"))
        assertEquals("合成用户独立意见", input.getJSONObject("feedback").getString("userText"))
        assertEquals(1, analysis.currentReview("run", ref)!!.feedbackVersion)
        assertEquals(ClaimFeedbackDecision.REJECTED, repository.claimFeedback(feedback.id)!!.decision)
        repository.deleteClaimFeedback(feedback.id, feedback.version)
        assertNull(analysis.currentReview("run", ref))
        assertEquals(2, repository.claimFeedbackVersion(feedback.id))
        analysis.reviewClaim("run", "deleted-feedback", ref, provider = reviewer)
        assertEquals(2, analysis.currentReview("run", ref)!!.feedbackVersion)
    }

    @Test fun `feedback written while provider runs rejects completion and requires explicit recovery`() {
        val ref = start()
        reviewer.before = { feedback(ref, ClaimFeedbackDecision.CORRECTED) }
        assertThrows(RevisionConflictException::class.java) { analysis.reviewClaim("run", "review", ref, provider = reviewer) }
        assertEquals(CallStatus.IN_FLIGHT, analysis.task("run").state.calls.last().status)
        assertNull(analysis.currentReview("run", ref))
        reviewer.before = {}
        analysis.recoverInterrupted("run")
        assertThrows(AnalysisConflictException::class.java) { analysis.reviewClaim("run", "review", ref, provider = reviewer) }
        analysis.reviewClaim("run", "after-feedback", ref, provider = reviewer)
        assertEquals(1, analysis.currentReview("run", ref)!!.feedbackVersion)
    }

    @Test fun `codec v3 binds review to target and mandatory receipts while retaining strict v2`() {
        val ref = start()
        val v3 = JSONObject(analysis.task("run").stored.payload)
        val legacyCall = v3.getJSONArray("calls").getJSONObject(0)
        listOf("metricEvidence", "purpose", "reviewTarget", "review", "feedbackVersion", "reductionInputs", "reductionOutput", "redactedReductionIdentity", "reductionTarget", "reductionReviewTokens", "reductionScan", "reductionScans").forEach(legacyCall::remove)
        v3.withoutProfileMaintenance()
        v3.getJSONObject("request").remove("profileTarget")
        val v2 = v3.put("formatVersion", 2).toString()
        assertEquals(CallPurpose.EXTRACTION, AnalysisRunCodec.decode(v2).calls.single().purpose)
        assertEquals(9, JSONObject(AnalysisRunCodec.encode(AnalysisRunCodec.decode(v2))).getInt("formatVersion"))
        legacyCall.put("purpose", "EXTRACTION")
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(v3.toString()) }
        analysis.reviewClaim("run", "review", ref, provider = reviewer)
        val state = analysis.task("run").state
        assertEquals(state, AnalysisRunCodec.decode(AnalysisRunCodec.encode(state)))
        val forged = state.calls.last().copy(reviewTarget = ref.copy(claimSha256 = "0".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.encode(state.copy(calls = state.calls.dropLast(1) + forged)) }
    }

    @Test fun `deleted feedback redacts derived review words while preserving budgets and earlier checks`() {
        val ref = start()
        analysis.reviewClaim("run", "before-feedback", ref, provider = reviewer)
        val value = feedback(ref, ClaimFeedbackDecision.CONFIRMED)
        reviewer.answer = { input -> ClaimReview(ReviewVerdict.SUPPORTED, listOf(evidenceId(input)), unknowns = listOf("合成需删除的反馈派生摘录")) }
        analysis.reviewClaim("run", "with-feedback", ref, provider = reviewer)
        val before = analysis.task("run").stored
        val redacted = AnalysisRunCodec.redactDeletedFeedback(before, setOf(value.id), clock.instant())
        val calls = AnalysisRunCodec.decode(redacted.payload).calls
        val original = analysis.task("run").state.calls
        assertEquals(before.version + 1, redacted.version)
        assertEquals(original.take(2), calls.take(2))
        assertEquals(original.last().inputBytes, calls.last().inputBytes)
        assertEquals(original.last().inputTokens, calls.last().inputTokens)
        assertEquals(original.last().evidence, calls.last().evidence)
        assertEquals(CallStatus.FAILED, calls.last().status)
        assertEquals("FEEDBACK_DELETED", calls.last().failureCode)
        assertNull(calls.last().review)
        assertNull(calls.last().usage)
        assertFalse(redacted.payload.contains("合成需删除的反馈派生摘录"))
        assertEquals(redacted, AnalysisRunCodec.redactDeletedFeedback(redacted, setOf(value.id), clock.instant()))
        assertEquals(before, AnalysisRunCodec.redactDeletedFeedback(before, setOf("f".repeat(64)), clock.instant()))
    }

    @Test fun `deleted feedback fences in flight review and preserves legacy payload without changes`() {
        val ref = start()
        val value = feedback(ref, ClaimFeedbackDecision.CONFIRMED)
        reviewer.before = { throw AssertionError("synthetic stopped worker") }
        assertThrows(AssertionError::class.java) { analysis.reviewClaim("run", "pending", ref, provider = reviewer) }
        val run = analysis.task("run").stored
        val redacted = AnalysisRunCodec.redactDeletedFeedback(run, setOf(value.id), clock.instant().minusSeconds(60))
        val call = AnalysisRunCodec.decode(redacted.payload).calls.last()
        assertEquals(CallStatus.FAILED, call.status)
        assertEquals(call.startedAt, call.finishedAt)
        val original = JSONObject(run.payload)
        val first = original.getJSONArray("calls").getJSONObject(0)
        listOf("metricEvidence", "purpose", "reviewTarget", "review", "feedbackVersion", "reductionInputs", "reductionOutput", "redactedReductionIdentity", "reductionTarget", "reductionReviewTokens", "reductionScan", "reductionScans").forEach(first::remove)
        original.withoutProfileMaintenance()
        original.getJSONObject("request").remove("profileTarget")
        original.put("calls", org.json.JSONArray().put(first)).put("formatVersion", 2)
        val legacy = run.copy(payload = original.toString())
        assertEquals(legacy, AnalysisRunCodec.redactDeletedFeedback(legacy, setOf(value.id), clock.instant()))
    }

    private fun start(limits: AnalysisLimits = AnalysisLimits(), expiresAt: Instant? = null, runId: String = "run"): ClaimRef {
        analysis.start(AnalysisRequest("合成证据核对", scope, limits, expiresAt = expiresAt), runId)
        analysis.runBatch(runId, "extract", listOf(EvidenceSelection("note", "body")), extraction)
        return analysis.claimRef(runId, "extract", 0)
    }

    private fun feedback(ref: ClaimRef, decision: ClaimFeedbackDecision): StoredClaimFeedback {
        val run = analysis.task("run").stored
        val value = StoredClaimFeedback(AnalysisRunCodec.feedbackId("run", ref), 1, "run", ref.batchKey, ref.claimIndex,
            ref.claimSha256, run.source, decision, "合成用户独立意见", clock.instant(), clock.instant())
        return repository.saveClaimFeedback(value, null, run.version)
    }

    private class SyntheticExtraction : AnalysisProvider {
        override val id = "synthetic-extraction"
        override val contextWindowTokens = 100_000
        var allReferences = false
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            val evidence = JSONObject(request.payloadJson).getJSONArray("evidence")
            val ids = (0 until if (allReferences) evidence.length() else 1).map { evidence.getJSONObject(it).getString("id") }
            return BatchProposal(listOf(ProposedClaim("合成待核对陈述", ids, ClaimKind.REPORTED_EXPERIENCE)))
        }
    }

    private class SyntheticReview : SemanticReviewProvider {
        override val id = "synthetic-review"
        override val contextWindowTokens = 100_000
        var wrapper = "synthetic review envelope"
        var before: () -> Unit = {}
        var answer: (JSONObject) -> ClaimReview = { input -> ClaimReview(ReviewVerdict.SUPPORTED, listOf(evidenceId(input))) }
        val sent = mutableListOf<PreparedModelRequest>()
        override fun prepareReview(request: ModelRequest): PreparedModelRequest =
            PreparedModelRequest(request, AnalysisRunCodec.json(mapOf("wrapper" to wrapper, "input" to request.payloadJson)), 200)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            sent += request
            before()
            return ReviewCompletion(answer(JSONObject(request.original.payloadJson)), ModelUsage(10, 3))
        }
    }

    private class MutableClock(var value: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = value
    }
    companion object {
        private fun evidenceId(input: JSONObject) = input.getJSONArray("evidence").getJSONObject(0).getString("id")
    }
}
