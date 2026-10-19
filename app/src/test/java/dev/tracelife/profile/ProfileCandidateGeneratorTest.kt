package dev.tracelife.profile

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.providers.DeepSeekCredentials
import dev.tracelife.providers.DeepSeekProvider
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
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

/** Synthetic original records, providers and clocks; no credentials or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileCandidateGeneratorTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var generator: ProfileCandidateGenerator
    private val database = "synthetic-profile-candidates-${UUID.randomUUID()}.db"
    private val date = LocalDate.parse("2026-10-19")
    private val target = ProfileAnalysisTarget(ProfileDimension.ABILITIES_AND_STRATEGIES, ProfileLayer.CONTEXTUAL_PATTERN)
    private val clock = MutableClock()
    private val provider = SyntheticProvider()
    private val reviewer = SyntheticReviewer()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        open()
        repository.createRecord(RecordDraft("合成资料：先做一次小练习，再检查错误；另外一次因为疲劳没有完成。", Occurrence(date)), "source")
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `one shared allowance reviews pending claims before further generation and resumes after reopen`() {
        start(limits = AnalysisLimits(fragmentCodePoints = 12))
        val first = process(1, 1)
        assertEquals(ProfileCandidateStatus.PASS_LIMIT_REACHED, first.status)
        assertEquals(1, first.modelRequestsThisPass)
        assertEquals(ProfileCandidateDisposition.PENDING_REVIEW, first.progress.candidates.single().disposition)
        assertTrue(first.progress.coverage!!.remainingTextFragments > 0)
        repository.close(); open()
        val generation = repository.currentAnalysisGeneration()
        assertEquals(first.progress, generator.inspect("run"))
        assertEquals(generation, repository.currentAnalysisGeneration())
        val second = process(1, 1)
        assertEquals(1, second.modelRequestsThisPass)
        assertEquals(0, second.generatedBatchesThisPass)
        assertEquals(1, second.reviewedThisPass)
        assertEquals(listOf(CallPurpose.SYNTHESIS, CallPurpose.REVIEW), analysis.task("run").state.calls.map { it.purpose })
        assertEquals(ProfileCandidateDisposition.AWAITING_CONFIRMATION, second.progress.candidates.single().disposition)
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertTrue(repository.profileHeads().isEmpty())
        assertFalse(second.progress.crossTaskSemanticDeduplication)
        assertFalse(second.progress.manualDeletedSlotTracking)
        assertFalse(second.progress.counterevidenceSearchExhaustive)
    }

    @Test fun `confirmation requires a new review before explicit atomic materialization and adoption stays separate`() {
        start()
        val processed = process()
        assertEquals(2, processed.modelRequestsThisPass)
        val item = processed.progress.candidates.single()
        assertEquals(ProfileCandidateDisposition.AWAITING_CONFIRMATION, item.disposition)
        assertThrows(IllegalArgumentException::class.java) { materialize(item.claim) }
        confirm(item.claim)
        assertEquals(ProfileCandidateDisposition.PENDING_REVIEW, generator.inspect("run").candidates.single().disposition)
        assertThrows(IllegalArgumentException::class.java) { materialize(item.claim) }
        assertEquals(1, process().modelRequestsThisPass)
        val source = repository.analysisSourceStamp()
        val saved = materialize(item.claim)
        assertEquals(ProfileCandidateMaterializationStatus.CREATED, saved.status)
        assertEquals(ProfileAdoption.CANDIDATE, saved.adoption)
        assertEquals(source, repository.analysisSourceStamp())
        assertNull(repository.profileRevision(saved.profile!!.id)!!.userText)
        assertEquals(0, PersonalProfile(repository, clock).open().total)
        val repeat = materialize(item.claim)
        assertEquals(ProfileCandidateMaterializationStatus.EXISTING, repeat.status)
        assertEquals(saved.profile, repeat.profile)
        assertEquals(1, repository.knowledgeHistory(saved.knowledge!!.knowledgeId).size)
        assertEquals(1, repository.profileHistory(saved.profile.id).size)
        assertEquals(0, process().modelRequestsThisPass)
        PersonalProfile(repository, clock).adopt(saved.profile.id, 1, "user-adopt")
        assertEquals(1, PersonalProfile(repository, clock).open().total)
    }

    @Test fun `default single fragment batches fit real DeepSeek preparation for both generation and complete review`() {
        repeat(4) { index ->
            val text = ("Synthetic scenario $index: " + "a".repeat(1_024)).take(1_024)
            if (index == 0) repository.updateRecord("source", 1, RecordDraft(text, Occurrence(date)))
            else repository.createRecord(RecordDraft(text, Occurrence(date)), "synthetic-$index")
        }
        // Preparation is real, including both system messages and the conservative UTF-8 bound.
        // Dispatch is fabricated; no key is read and no network operation is available here.
        val wire = DeepSeekProvider(DeepSeekCredentials(context))
        val generationInputs = mutableListOf<PreparedModelRequest>()
        val reviewInputs = mutableListOf<PreparedModelRequest>()
        val measured = object : AnalysisProvider, SemanticReviewProvider {
            override val id = wire.id
            override val contextWindowTokens = wire.contextWindowTokens
            override fun countInputTokens(request: ModelRequest) = wire.countInputTokens(request)
            override fun prepare(request: ModelRequest) = wire.prepare(request)
            override fun prepareReview(request: ModelRequest) = wire.prepareReview(request)
            override fun complete(request: ModelRequest) = provider.complete(request)
            override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                generationInputs += request
                return ModelCompletion(provider.complete(request.original))
            }
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                reviewInputs += request
                return reviewer.reviewPrepared(request)
            }
        }
        start()
        val report = generator.process("run", measured, measured, maxModelRequests = 8)
        val diagnostics = analysis.task("run").state.calls.joinToString { call ->
            "${call.purpose}:${call.status}:${call.failureCode ?: "none"}:inputTokens=${call.inputTokens}"
        }
        assertEquals("requests=${report.modelRequestsThisPass}; $diagnostics", ProfileCandidateStatus.NEEDS_ATTENTION, report.status)
        assertEquals(8, report.modelRequestsThisPass)
        assertEquals(4, report.generatedBatchesThisPass)
        assertEquals(4, report.reviewedThisPass)
        assertEquals(0L, report.progress.coverage!!.remainingTextFragments)
        assertEquals(SynthesisIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED, report.progress.integration)
        assertTrue(report.progress.candidates.all { it.disposition == ProfileCandidateDisposition.AWAITING_CONFIRMATION })
        assertEquals(4, generationInputs.size)
        assertEquals(4, reviewInputs.size)
        (generationInputs + reviewInputs).forEach { request ->
            assertEquals(TokenCountKind.CONSERVATIVE_UTF8_BOUND, request.tokenCountKind)
            assertTrue(request.inputTokens <= AnalysisLimits().inputTokenLimit)
            assertEquals(1, JSONObject(request.original.payloadJson).getJSONArray("evidence").length())
        }
        generationInputs.zip(reviewInputs).forEach { (generated, reviewed) ->
            val original = JSONObject(generated.original.payloadJson).getJSONArray("evidence")
            assertEquals(1_024, original.getJSONObject(0).getString("text").length)
            assertEquals(original.toString(), JSONObject(reviewed.original.payloadJson).getJSONArray("evidence").toString())
        }
        assertEquals(8, analysis.task("run").state.calls.size)
        assertEquals(0, generator.process("run", measured, measured).modelRequestsThisPass)
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertTrue(repository.profileHeads().isEmpty())
    }

    @Test fun `existing declines and independent text survive retries and stable candidate deletion blocks recreation`() {
        val claim = ready()
        val saved = materialize(claim)
        val profile = PersonalProfile(repository, clock)
        profile.feedback(saved.profile!!.id, 1, "user-context", "合成用户独立文字。")
        profile.decline(saved.profile.id, 2, "user-decline")
        val repeated = materialize(claim)
        assertEquals(ProfileCandidateMaterializationStatus.EXISTING, repeated.status)
        assertEquals(ProfileAdoption.DECLINED, repeated.adoption)
        assertEquals(3, repeated.profile!!.revision)
        assertEquals("合成用户独立文字。", repository.profileRevision(saved.profile.id)!!.userText)
        profile.delete(saved.profile.id, 3)
        val generation = repository.currentAnalysisGeneration()
        assertEquals(ProfileCandidateMaterializationStatus.DELETED, materialize(claim).status)
        assertEquals(ProfileCandidateDisposition.PROFILE_DELETED, generator.inspect("run").candidates.single().disposition)
        assertEquals(0, process().modelRequestsThisPass)
        assertEquals(generation, repository.currentAnalysisGeneration())
        assertTrue(repository.profileHeads().isEmpty())
        assertEquals(1, repository.knowledgeHistory(saved.knowledge!!.knowledgeId).size)
    }

    @Test fun `manual same slot preserves current user state and changed fixed knowledge revision requires explicit rebinding`() {
        val claim = ready()
        val task = analysis.task("run")
        val review = analysis.currentReview("run", claim)!!
        val library = KnowledgeLibrary(repository, clock)
        val knowledge = library.publish("run", claim, review.batchKey, dev.tracelife.storage.KnowledgeCategory.INSIGHT, task.stored.version)
        val profile = PersonalProfile(repository, clock)
        profile.create(ProfileDraft(ProfileKnowledgeRef(knowledge.id, knowledge.revision), target.dimension, target.layer), "manual")
        profile.decline("manual", 1, "decline-manual")
        assertEquals(ProfileHead("manual", 2), materialize(claim).profile)
        assertEquals(ProfileAdoption.DECLINED, materialize(claim).adoption)
        // A new explicit review changes the knowledge proof; the candidate pipeline must not rebind.
        analysis.reviewClaim("run", "new-explicit-review", claim, provider = reviewer)
        val refreshed = library.publish("run", claim, "new-explicit-review", dev.tracelife.storage.KnowledgeCategory.INSIGHT,
            analysis.task("run").stored.version, knowledge.revision)
        assertEquals(2, refreshed.revision)
        val conflict = materialize(claim)
        assertEquals(ProfileCandidateMaterializationStatus.CONFLICT, conflict.status)
        assertEquals(1, conflict.knowledge!!.revision)
        assertEquals(ProfileHead("manual", 2), conflict.profile)
        assertEquals(2, repository.knowledgeHistory(knowledge.id).size)
    }

    @Test fun `pages contain only metadata and reject derived changes while leaves expose only the selected proposal`() {
        provider.claimCount = 3
        start(); process(1)
        val first = generator.inspect("run", limit = 1)
        val second = generator.next(first)
        assertEquals(3, first.total)
        assertEquals(1, first.nextOffset)
        assertEquals(2, second.nextOffset)
        assertEquals(1, first.candidates.size)
        val leaf = generator.candidate(first, first.candidates.single().claim)
        assertTrue(leaf.proposal.text.startsWith("合成假设 0"))
        assertEquals(1, leaf.evidence.size)
        assertThrows(IllegalArgumentException::class.java) { generator.candidate(first, second.candidates.single().claim) }
        assertThrows(UnsupportedOperationException::class.java) { (first.candidates as MutableList<ProfileCandidateItem>).clear() }
        confirm(first.candidates.single().claim)
        assertThrows(StaleSnapshotException::class.java) { generator.next(first) }
        assertThrows(RevisionConflictException::class.java) { generator.candidate("run", leaf.item.claim, first.taskVersion!!) }
        assertThrows(IllegalArgumentException::class.java) { generator.inspect("run", limit = 101) }
    }

    @Test fun `unsupported feedback blocked and permanently deleted identities never get automatic retries`() {
        reviewer.verdict = ReviewVerdict.CONTRADICTED
        start(); val item = process().progress.candidates.single()
        assertEquals(ProfileCandidateDisposition.REVIEW_CONTRADICTED, item.disposition)
        assertEquals(0, process().modelRequestsThisPass)
        val feedback = AnalysisFeedback(repository, clock)
        val rejected = feedback.respond("run", item.claim, analysis.task("run").stored.version, ClaimFeedbackDecision.REJECTED)
        assertEquals(ProfileCandidateDisposition.FEEDBACK_BLOCKED, generator.inspect("run").candidates.single().disposition)
        assertEquals(0, process().modelRequestsThisPass)
        feedback.delete(rejected.feedback.id, rejected.feedback.version)
        assertEquals(ProfileCandidateDisposition.FEEDBACK_DELETED, generator.inspect("run").candidates.single().disposition)
        assertEquals(0, process().modelRequestsThisPass)
        assertThrows(IllegalArgumentException::class.java) { materialize(item.claim) }
    }

    @Test fun `failed generation retries only on the next explicit pass and successful calls do not repeat`() {
        provider.failNext = true
        start()
        val failed = process()
        assertEquals(ProfileCandidateStatus.RETRY_NEEDED, failed.status)
        assertEquals(1, failed.modelRequestsThisPass)
        assertEquals(1, analysis.task("run").state.calls.size)
        val resumed = process()
        assertEquals(2, resumed.modelRequestsThisPass)
        assertEquals(3, analysis.task("run").state.calls.size)
        assertEquals(0, process().modelRequestsThisPass)
        assertEquals(2, provider.requests)
        assertEquals(1, reviewer.requests)
    }

    @Test fun `source changes during review block late results and source deletion retains independent profile text`() {
        start(); process(1)
        reviewer.onReview = { repository.updateRecord("source", 1, RecordDraft("合成来源已更正。", Occurrence(date))) }
        assertEquals(ProfileCandidateStatus.STALE, process().status)
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertTrue(repository.profileHeads().isEmpty())
        reviewer.onReview = null
        start("new-run")
        process(runId = "new-run")
        val claim = generator.inspect("new-run").candidates.single().claim
        confirm(claim, "new-run"); process(runId = "new-run")
        val saved = generator.materialize("new-run", claim, analysis.task("new-run").stored.version)
        PersonalProfile(repository, clock).feedback(saved.profile!!.id, 1, "independent", "合成独立背景保留。")
        repository.deleteRecord("source", 2)
        assertEquals(ProfileCandidateStatus.TASK_UNAVAILABLE, generator.inspect("new-run").status)
        assertEquals("合成独立背景保留。", repository.profileRevision(saved.profile.id)!!.userText)
        assertNull(repository.knowledgeRevision(saved.knowledge!!.knowledgeId))
    }

    @Test fun `clock rollback and expiry prevent model dispatch materialization and stale page reuse`() {
        val original = clock.now
        start(expiresAt = original.plusSeconds(30))
        clock.now = original.minusSeconds(1)
        assertEquals(ProfileCandidateStatus.CLOCK_ROLLBACK, process().status)
        assertEquals(0, provider.requests)
        clock.now = original
        val claim = process().progress.candidates.single().claim
        confirm(claim); process()
        val saved = materialize(claim)
        val page = generator.inspect("run")
        val version = analysis.task("run").stored.version
        clock.now = original.minusSeconds(1)
        assertThrows(IllegalArgumentException::class.java) { generator.materialize("run", claim, version) }
        clock.now = original
        var reads = 0
        clock.onRead = { if (++reads == 2) clock.now = original.plusSeconds(30) }
        // Direct bridge: first read fixes evaluation, second is the EXISTING result commit fence.
        assertThrows(IllegalArgumentException::class.java) { repository.materializeProfileCandidate("run", claim, version) }
        clock.onRead = null
        assertEquals(ProfileCandidateStatus.EXPIRED, process().status)
        assertThrows(StaleSnapshotException::class.java) { generator.candidate(page, claim) }
        assertEquals(1, repository.profileHistory(saved.profile!!.id).size)
    }

    @Test fun `capacity failure rolls back new knowledge and candidate as one transaction`() {
        provider.claimCount = 2
        start(); val claims = process().progress.candidates.map { it.claim }
        claims.forEach { confirm(it) }; process()
        materialize(claims[0])
        val otherId = generator.inspect("run").candidates[1].knowledgeId
        context.openOrCreateDatabase(database, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("UPDATE profile_revisions SET encoded_bytes = ?", arrayOf(ProfileValidation.MAX_BYTES))
        }
        val before = repository.currentAnalysisGeneration()
        assertThrows(IllegalArgumentException::class.java) { materialize(claims[1]) }
        assertNull(repository.knowledgeRevision(otherId))
        assertEquals(1, repository.profileHeads().size)
        assertEquals(before, repository.currentAnalysisGeneration())
    }

    private fun open() {
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        generator = ProfileCandidateGenerator(repository, analysis, clock)
    }
    private fun start(id: String = "run", limits: AnalysisLimits = AnalysisLimits(), expiresAt: Instant? = null) = generator.start(
        AnalysisRequest("合成任务：检查本范围中的学习策略及适用限制。", AnalysisQuery(date, date, "Asia/Shanghai"),
            limits = limits, expiresAt = expiresAt, profileTarget = target), id)
    private fun process(max: Int = 5, fragments: Int = 1, runId: String = "run") =
        generator.process(runId, provider, reviewer, max, fragments)
    private fun confirm(claim: ClaimRef, runId: String = "run") = AnalysisFeedback(repository, clock).respond(runId, claim,
        analysis.task(runId).stored.version, ClaimFeedbackDecision.CONFIRMED)
    private fun materialize(claim: ClaimRef) = generator.materialize("run", claim, analysis.task("run").stored.version)
    private fun ready(): ClaimRef {
        start(); val claim = process().progress.candidates.single().claim
        confirm(claim); process()
        return claim
    }
    private class MutableClock : Clock() {
        var now: Instant = Instant.parse("2026-10-19T03:00:00Z")
        var onRead: (() -> Unit)? = null
        override fun instant(): Instant { onRead?.invoke(); return now }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private class SyntheticProvider : AnalysisProvider {
        override val id = "synthetic-profile-generator-v1"
        override val contextWindowTokens = 20_000
        var requests = 0
        var claimCount = 1
        var failNext = false
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            requests++
            if (failNext) { failNext = false; throw AnalysisProviderException(ProviderFailureCode.NETWORK) }
            val evidence = JSONObject(request.payloadJson).getJSONArray("evidence")
            val id = evidence.getJSONObject(0).getString("id")
            return BatchProposal((0 until claimCount).map { ProposedClaim("合成假设 $it：本次小练习可能帮助检查错误，疲劳情境尚不确定。",
                listOf(id), ClaimKind.POSSIBLE_INTERPRETATION) })
        }
    }
    private class SyntheticReviewer : SemanticReviewProvider {
        override val id = "synthetic-profile-reviewer-v1"
        override val contextWindowTokens = 20_000
        var requests = 0
        var verdict = ReviewVerdict.SUPPORTED
        var onReview: (() -> Unit)? = null
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            requests++; onReview?.invoke()
            // Prepared payload may be a supplier wire body; evidence lives in the original envelope.
            val id = JSONObject(request.original.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return ReviewCompletion(when (verdict) {
                ReviewVerdict.SUPPORTED -> ClaimReview(verdict, supportingEvidenceIds = listOf(id))
                ReviewVerdict.CONTRADICTED -> ClaimReview(verdict, contradictingEvidenceIds = listOf(id))
                else -> ClaimReview(verdict, unknowns = listOf("合成资料不足。"))
            })
        }
    }
}
