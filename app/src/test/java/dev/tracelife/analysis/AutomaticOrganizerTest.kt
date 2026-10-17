package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
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

/** Synthetic source material and providers only; never uses credentials or the network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AutomaticOrganizerTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var processor: AnalysisBatchProcessor
    private lateinit var knowledge: KnowledgeLibrary
    private lateinit var feedback: AnalysisFeedback
    private lateinit var organizer: AutomaticOrganizer
    private val name = "synthetic-organizer-${UUID.randomUUID()}.db"
    private val date = LocalDate.parse("2026-10-17")
    private val scope = AnalysisQuery(date, date, "Asia/Shanghai")
    private val clock = MutableClock()
    private val extractor = SyntheticExtractor()
    private val reviewer = SyntheticReviewer()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        open()
        repository.createRecord(RecordDraft("合成资料：我记录了一次练习，结果需要逐条核对。", Occurrence(date)), "source")
    }

    @After fun tearDown() { repository.close(); context.deleteDatabase(name) }

    @Test fun `one shared request allowance resumes after reopen and inspect is read only`() {
        start()
        val first = process(1)
        assertEquals(OrganizationStatus.PASS_LIMIT_REACHED, first.status)
        assertEquals(1, first.modelRequestsThisPass)
        assertEquals(0, first.publishedThisPass)
        assertEquals(0L, first.progress.coverage!!.remainingTextFragments)
        assertEquals(1, first.progress.counts[ClaimDisposition.PENDING_REVIEW])
        val version = analysis.task("run").stored.version
        assertEquals(first.progress, organizer.inspect("run"))
        assertEquals(version, analysis.task("run").stored.version)
        assertEquals(1, extractor.requests.size)
        assertTrue(reviewer.requests.isEmpty())

        reopen()
        val second = process(1)
        assertEquals(OrganizationStatus.PASS_FINISHED, second.status)
        assertEquals(1, second.modelRequestsThisPass)
        assertEquals(1, second.publishedThisPass)
        assertEquals(1, second.progress.counts[ClaimDisposition.PUBLISHED])
        val saved = knowledge.get(second.progress.claims.single().knowledgeId)!!.value
        assertEquals(KnowledgeCategory.OBSERVATION, saved.category)
        assertTrue(saved.topics.isEmpty())
        assertFalse(second.progress.counterevidenceSearchExhaustive)
        val again = process()
        assertEquals(0, again.modelRequestsThisPass)
        assertEquals(0, again.publishedThisPass)
        assertEquals(1, knowledge.history(saved.id).size)
    }

    @Test fun `several candidates share extraction and review allowance without losing progress`() {
        extractor.kinds = List(3) { ClaimKind.REPORTED_EXPERIENCE }
        start()
        val first = process(2)
        assertEquals(2, first.modelRequestsThisPass)
        assertEquals(1, first.publishedThisPass)
        assertEquals(2, first.progress.counts[ClaimDisposition.PENDING_REVIEW])
        val second = process(2)
        assertEquals(2, second.modelRequestsThisPass)
        assertEquals(2, second.publishedThisPass)
        assertEquals(OrganizationStatus.PASS_FINISHED, second.status)
        assertEquals(3, second.progress.counts[ClaimDisposition.PUBLISHED])
        assertEquals(1, extractor.requests.size)
        assertEquals(3, reviewer.requests.size)
    }

    @Test fun `negative review verdicts are retained without trying again for a favorable answer`() {
        for (verdict in listOf(ReviewVerdict.UNSUPPORTED, ReviewVerdict.CONTRADICTED, ReviewVerdict.INSUFFICIENT_CONTEXT)) {
            val runId = "run-${verdict.name}"
            start(runId = runId)
            reviewer.verdict = verdict
            val first = organizer.process(runId, extractor, reviewer)
            assertEquals(OrganizationStatus.NEEDS_ATTENTION, first.status)
            assertEquals(2, first.modelRequestsThisPass)
            assertEquals(verdict, first.progress.claims.single().reviewVerdict)
            val again = organizer.process(runId, extractor, reviewer)
            assertEquals(0, again.modelRequestsThisPass)
            assertEquals(0, again.publishedThisPass)
            assertNull(knowledge.get(again.progress.claims.single().knowledgeId))
        }
    }

    @Test fun `interpretations wait for independent confirmation before review and publication`() {
        extractor.kinds = listOf(ClaimKind.POSSIBLE_INTERPRETATION)
        start()
        val waiting = process()
        assertEquals(OrganizationStatus.NEEDS_ATTENTION, waiting.status)
        assertEquals(1, waiting.modelRequestsThisPass)
        assertEquals(ClaimDisposition.AWAITING_CONFIRMATION, waiting.progress.claims.single().disposition)
        assertTrue(reviewer.requests.isEmpty())
        val ref = waiting.progress.claims.single().claim
        feedback.respond("run", ref, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED,
            "合成用户确认：仅限这一次练习的解释。")
        val published = process(1)
        assertEquals(1, published.modelRequestsThisPass)
        val value = knowledge.get(published.progress.claims.single().knowledgeId)!!.value
        assertEquals(KnowledgeCategory.INSIGHT, value.category)
        assertEquals(1, value.feedbackVersion)
        assertEquals(1, JSONObject(reviewer.requests.single().payloadJson).getJSONObject("feedback").getInt("version"))
    }

    @Test fun `new feedback versions require a new review and never reuse earlier support`() {
        start()
        val initial = process()
        val ref = initial.progress.claims.single().claim
        val confirmed = feedback.respond("run", ref, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        val first = process(1)
        assertEquals(1, first.modelRequestsThisPass)
        assertEquals(2, first.progress.claims.single().knowledgeRevision)
        feedback.update(confirmed.feedback.id, confirmed.feedback.version, analysis.task("run").stored.version,
            ClaimFeedbackDecision.CONFIRMED, "合成用户补充：仍需保留局限。")
        val second = process(1)
        assertEquals(1, second.modelRequestsThisPass)
        assertEquals(3, second.progress.claims.single().knowledgeRevision)
        assertEquals(3, reviewer.requests.map { it.idempotencyKey }.distinct().size)
        assertEquals(listOf(0, 1, 2), reviewer.requests.map {
            JSONObject(it.payloadJson).getJSONObject("feedback").getInt("version")
        })
    }

    @Test fun `corrections rejections withdrawals and deleted feedback prevent paid processing`() {
        extractor.kinds = List(4) { ClaimKind.REPORTED_EXPERIENCE }
        start()
        extract()
        val refs = (0..3).map { analysis.claimRef("run", "manual", it) }
        listOf(ClaimFeedbackDecision.CORRECTED, ClaimFeedbackDecision.REJECTED, ClaimFeedbackDecision.WITHDRAWN)
            .forEachIndexed { index, decision ->
                feedback.respond("run", refs[index], analysis.task("run").stored.version, decision, "合成用户独立文字。")
            }
        val deleted = feedback.respond("run", refs[3], analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        feedback.delete(deleted.feedback.id, deleted.feedback.version)
        val result = process()
        assertEquals(OrganizationStatus.NEEDS_ATTENTION, result.status)
        assertEquals(0, result.modelRequestsThisPass)
        assertEquals(3, result.progress.counts[ClaimDisposition.FEEDBACK_BLOCKED])
        assertEquals(1, result.progress.counts[ClaimDisposition.FEEDBACK_DELETED])
        assertEquals(0, knowledge.index().total)
    }

    @Test fun `permanently deleted knowledge is not reviewed again or recreated`() {
        start()
        val initial = process()
        val value = knowledge.get(initial.progress.claims.single().knowledgeId)!!.value
        knowledge.delete(value.id, value.revision)
        reopen()
        val result = process()
        assertEquals(0, result.modelRequestsThisPass)
        assertEquals(0, result.publishedThisPass)
        assertEquals(ClaimDisposition.KNOWLEDGE_DELETED, result.progress.claims.single().disposition)
        assertNull(knowledge.get(value.id))
    }

    @Test fun `latest manual negative review blocks a previously supported publication`() {
        start()
        val initial = process()
        val ref = initial.progress.claims.single().claim
        reviewer.verdict = ReviewVerdict.CONTRADICTED
        analysis.reviewClaim("run", "manual-counterevidence", ref, provider = reviewer)
        val result = process()
        assertEquals(OrganizationStatus.NEEDS_ATTENTION, result.status)
        assertEquals(0, result.modelRequestsThisPass)
        assertEquals(0, result.publishedThisPass)
        assertEquals(ClaimDisposition.REVIEW_CONTRADICTED, result.progress.claims.single().disposition)
        assertEquals(KnowledgeEvidenceState.REVIEW_CHANGED,
            knowledge.get(result.progress.claims.single().knowledgeId)!!.evidenceState)
    }

    @Test fun `republishing a new supported review preserves manually selected classification and topics`() {
        start()
        val initial = process()
        val ref = initial.progress.claims.single().claim
        val value = knowledge.get(initial.progress.claims.single().knowledgeId)!!.value
        knowledge.publish("run", ref, value.reviewBatchKey, KnowledgeCategory.KNOWLEDGE_NOTE,
            analysis.task("run").stored.version, value.revision, setOf("custom-learning"))
        analysis.reviewClaim("run", "manual-new-context-review", ref, provider = reviewer)
        val result = process()
        assertEquals(0, result.modelRequestsThisPass)
        assertEquals(1, result.publishedThisPass)
        val updated = knowledge.get(value.id)!!.value
        assertEquals(3, updated.revision)
        assertEquals(KnowledgeCategory.KNOWLEDGE_NOTE, updated.category)
        assertEquals(setOf("custom-learning"), updated.topics)
        assertEquals("manual-new-context-review", updated.reviewBatchKey)
    }

    @Test fun `failure stops the pass and explicit resume retries the same registered request`() {
        start()
        extract()
        reviewer.fail = true
        val failed = process()
        assertEquals(OrganizationStatus.RETRY_NEEDED, failed.status)
        assertEquals(1, failed.modelRequestsThisPass)
        assertEquals(0, failed.publishedThisPass)
        reopen()
        reviewer.fail = false
        val resumed = process(1)
        assertEquals(OrganizationStatus.PASS_FINISHED, resumed.status)
        assertEquals(1, resumed.modelRequestsThisPass)
        assertEquals(reviewer.requests[0].idempotencyKey, reviewer.requests[1].idempotencyKey)
        assertEquals(listOf(1, 2), analysis.task("run").state.calls.filter { it.purpose == CallPurpose.REVIEW }.map { it.attempt })
    }

    @Test fun `interrupted review requires explicit recovery and cannot automatically replay a charge`() {
        start()
        extract()
        reviewer.crash = true
        assertThrows(SyntheticCrash::class.java) { process() }
        reopen()
        reviewer.crash = false
        val blocked = process()
        assertEquals(OrganizationStatus.NEEDS_RECOVERY, blocked.status)
        assertEquals(0, blocked.modelRequestsThisPass)
        assertNull(blocked.progress.coverage)
        assertEquals(1, reviewer.requests.size)
        analysis.recoverInterrupted("run")
        val resumed = process(1)
        assertEquals(OrganizationStatus.PASS_FINISHED, resumed.status)
        assertEquals(reviewer.requests[0].idempotencyKey, reviewer.requests[1].idempotencyKey)
    }

    @Test fun `task budget remains shared even when the pass allowance is larger`() {
        start(limits = AnalysisLimits(maxCalls = 1))
        val result = process()
        assertEquals(OrganizationStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertEquals(ClaimDisposition.PENDING_REVIEW, result.progress.claims.single().disposition)
        assertTrue(reviewer.requests.isEmpty())
        assertEquals(0, process().modelRequestsThisPass)
        assertEquals(0, knowledge.index().total)
    }

    @Test fun `cancellation stops before the next request or publication and can resume`() {
        start()
        val cancelled = AtomicBoolean(true)
        assertEquals(0, organizer.process("run", extractor, reviewer, cancelled = cancelled::get).modelRequestsThisPass)
        cancelled.set(false)
        extractor.onComplete = { cancelled.set(true) }
        val first = organizer.process("run", extractor, reviewer, cancelled = cancelled::get)
        assertEquals(OrganizationStatus.CANCELLED, first.status)
        assertEquals(1, first.modelRequestsThisPass)
        assertTrue(reviewer.requests.isEmpty())
        extractor.onComplete = null
        cancelled.set(false)
        reviewer.onComplete = { cancelled.set(true) }
        val reviewed = organizer.process("run", extractor, reviewer, cancelled = cancelled::get)
        assertEquals(OrganizationStatus.CANCELLED, reviewed.status)
        assertEquals(1, reviewed.modelRequestsThisPass)
        assertEquals(0, reviewed.publishedThisPass)
        reviewer.onComplete = null
        assertEquals(0, process().modelRequestsThisPass)
        assertEquals(1, knowledge.index().total)
    }

    @Test fun `source mutation during review blocks the late result and all publication`() {
        start()
        extract()
        reviewer.onComplete = {
            repository.updateRecord("source", 1, RecordDraft("合成来源已被纠正。", Occurrence(date)))
        }
        val result = process()
        assertEquals(OrganizationStatus.STALE, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertNull(result.progress.coverage)
        assertTrue(result.progress.claims.isEmpty())
        assertEquals(0, knowledge.index().total)
    }

    @Test fun `feedback arriving during review prevents publication and requires recovery`() {
        start()
        extract()
        val ref = analysis.claimRef("run", "manual", 0)
        reviewer.onComplete = {
            feedback.respond("run", ref, analysis.task("run").stored.version, ClaimFeedbackDecision.REJECTED)
        }
        val result = process()
        assertEquals(OrganizationStatus.NEEDS_RECOVERY, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertEquals(0, knowledge.index().total)
        reviewer.onComplete = null
        analysis.recoverInterrupted("run")
        val after = process()
        assertEquals(0, after.modelRequestsThisPass)
        assertEquals(ClaimDisposition.FEEDBACK_BLOCKED, after.progress.claims.single().disposition)
    }

    @Test fun `inspect rejects task or analysis generation changes without exposing mixed progress`() {
        start(expiresAt = clock.instant().plusSeconds(3600))
        val published = process()
        val id = published.progress.claims.single().knowledgeId
        val calls = analysis.task("run").state.calls.size
        clock.onNextInstant = {
            val current = repository.analysisRun("run")!!
            repository.saveAnalysisRun(current.copy(version = current.version + 1), current.version)
        }
        val taskChanged = organizer.inspect("run")
        assertEquals(OrganizationStatus.CONFLICT, taskChanged.status)
        assertNull(taskChanged.coverage)
        assertTrue(taskChanged.claims.isEmpty())
        val version = analysis.task("run").stored.version
        clock.onNextInstant = { knowledge.delete(id, 1) }
        val knowledgeChanged = organizer.inspect("run")
        assertEquals(OrganizationStatus.CONFLICT, knowledgeChanged.status)
        assertNull(knowledgeChanged.coverage)
        assertEquals(version, analysis.task("run").stored.version)
        assertEquals(calls, analysis.task("run").state.calls.size)
    }

    @Test fun `expired missing and invalid requests never dispatch a provider`() {
        start(expiresAt = clock.instant().plusSeconds(1))
        clock.now = clock.now.plusSeconds(1)
        assertEquals(OrganizationStatus.EXPIRED, process().status)
        analysis.delete("run", analysis.task("run").stored.version)
        assertEquals(OrganizationStatus.TASK_UNAVAILABLE, process().status)
        assertThrows(IllegalArgumentException::class.java) { process(0) }
        assertThrows(IllegalArgumentException::class.java) { process(101) }
        assertTrue(extractor.requests.isEmpty())
        assertTrue(reviewer.requests.isEmpty())
    }

    @Test fun `empty proposals and unknown dates retain coverage gaps without invented conclusions`() {
        repository.updateRecord("source", 1, RecordDraft("合成日期不明的片段。"))
        extractor.kinds = emptyList()
        start()
        val result = process()
        assertEquals(OrganizationStatus.NEEDS_ATTENTION, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertEquals(1, result.progress.coverage!!.unknownTimeRecords)
        assertEquals(0L, result.progress.coverage.remainingTextFragments)
        assertTrue(result.progress.claims.isEmpty())
        assertTrue(reviewer.requests.isEmpty())
        assertEquals(0, knowledge.index().total)
    }

    @Test fun `an empty first fragment cannot mark an unread tail processed`() {
        extractor.kinds = emptyList()
        start(limits = AnalysisLimits(fragmentCodePoints = 4))
        val result = process(1)
        assertEquals(OrganizationStatus.PASS_LIMIT_REACHED, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertEquals(4L, result.progress.coverage!!.completedTextCodePoints)
        assertTrue(result.progress.coverage.remainingTextFragments > 0)
        assertTrue(result.progress.claims.isEmpty())
        assertTrue(reviewer.requests.isEmpty())
    }

    private fun process(limit: Int = 5) = organizer.process("run", extractor, reviewer, maxModelRequests = limit)

    private fun start(runId: String = "run", limits: AnalysisLimits = AnalysisLimits(), expiresAt: Instant? = null) =
        analysis.start(AnalysisRequest("整理合成记录", scope, limits, focusTopics = setOf("hint-only"), expiresAt = expiresAt), runId)

    private fun extract() = analysis.runBatch("run", "manual", listOf(EvidenceSelection("source", "body")), extractor)

    private fun open() {
        repository = LifeRepository(context, clock, name)
        analysis = ControlledAnalysis(repository, clock)
        processor = AnalysisBatchProcessor(analysis)
        knowledge = KnowledgeLibrary(repository, clock)
        feedback = AnalysisFeedback(repository, clock)
        organizer = AutomaticOrganizer(repository, analysis, processor, knowledge)
    }

    private fun reopen() { repository.close(); open() }

    private class SyntheticExtractor : AnalysisProvider {
        override val id = "synthetic-organizer-extractor"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<ModelRequest>()
        var kinds = listOf(ClaimKind.REPORTED_EXPERIENCE)
        var onComplete: (() -> Unit)? = null
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            onComplete?.invoke()
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return BatchProposal(kinds.mapIndexed { index, kind -> ProposedClaim("合成候选 $index：用户记录了一次练习。", listOf(id), kind) })
        }
    }

    private class SyntheticReviewer : SemanticReviewProvider {
        override val id = "synthetic-organizer-reviewer"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<ModelRequest>()
        var verdict = ReviewVerdict.SUPPORTED
        var fail = false
        var crash = false
        var onComplete: (() -> Unit)? = null
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            requests += request.original
            if (fail) error("synthetic provider failure")
            if (crash) throw SyntheticCrash()
            onComplete?.invoke()
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            val review = when (verdict) {
                ReviewVerdict.SUPPORTED -> ClaimReview(verdict, supportingEvidenceIds = listOf(id))
                ReviewVerdict.CONTRADICTED -> ClaimReview(verdict, contradictingEvidenceIds = listOf(id))
                ReviewVerdict.UNSUPPORTED -> ClaimReview(verdict, issues = listOf(ReviewIssue.OTHER))
                ReviewVerdict.INSUFFICIENT_CONTEXT -> ClaimReview(verdict, unknowns = listOf("合成缺口：上下文不足。"))
            }
            return ReviewCompletion(review)
        }
    }

    private class SyntheticCrash : Error("synthetic process interruption")

    private class MutableClock(var now: Instant = Instant.parse("2026-10-17T03:00:00Z")) : Clock() {
        var onNextInstant: (() -> Unit)? = null
        override fun instant(): Instant {
            val callback = onNextInstant
            onNextInstant = null
            callback?.invoke()
            return now
        }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
