package dev.tracelife.analysis

import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.StoredClaimFeedback
import java.time.Clock

/** Evidence validity and user agreement are independent; neither establishes objective truth. */
enum class FeedbackEvidenceState { CURRENT, STALE, EXPIRED, TASK_UNAVAILABLE, DEPENDENCY_CHANGED }
data class ClaimFeedbackView(
    val feedback: StoredClaimFeedback,
    val evidenceState: FeedbackEvidenceState,
    val currentRunVersion: Int?,
)

/** User-only entry point. Never expose it as a model tool or copy AI text into userText implicitly. */
class AnalysisFeedback(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    fun forClaim(runId: String, claim: ClaimRef): ClaimFeedbackView? =
        get(AnalysisRunCodec.feedbackId(runId, claim))

    fun forReduction(runId: String, target: ReductionStatementRef): ClaimFeedbackView? =
        forClaim(runId, ReductionGraph.feedbackRef(target))

    fun get(id: String): ClaimFeedbackView? = repository.claimFeedback(id)?.let(::view)

    fun list(offset: Int = 0, limit: Int = 25): List<ClaimFeedbackView> =
        repository.claimFeedbacks(offset, limit).map(::view)

    /** Submit a decision against the exact task version the user inspected. */
    fun respond(
        runId: String,
        claim: ClaimRef,
        expectedRunVersion: Int,
        decision: ClaimFeedbackDecision,
        userText: String? = null,
        expectedVersion: Int? = null,
    ): ClaimFeedbackView {
        val run = repository.analysisRun(runId) ?: throw AnalysisTaskUnavailableException()
        val id = AnalysisRunCodec.feedbackId(runId, claim)
        val previous = repository.claimFeedback(id)
        val now = clock.instant()
        val feedback = StoredClaimFeedback(
            id, nextVersion(expectedVersion), runId, claim.batchKey, claim.claimIndex, claim.claimSha256,
            run.source, decision, userText, previous?.createdAt ?: now, now,
        )
        return view(repository.saveClaimFeedback(feedback, expectedVersion, expectedRunVersion))
    }

    /** The identity includes the complete root output, while storage checks every ancestor fence. */
    fun respondReduction(
        runId: String,
        target: ReductionStatementRef,
        expectedRunVersion: Int,
        decision: ClaimFeedbackDecision,
        userText: String? = null,
        expectedVersion: Int? = null,
    ): ClaimFeedbackView {
        val run = repository.analysisRun(runId) ?: throw AnalysisTaskUnavailableException()
        val state = AnalysisRunCodec.decode(run.payload)
        val call = requireNotNull(state.calls.singleOrNull {
            it.batchKey == target.root.batchKey && it.attempt == target.root.attempt &&
                it.purpose == CallPurpose.REDUCTION && it.status == CallStatus.SUCCEEDED
        }) { "归并陈述已不存在或已清除" }
        require(ReductionGraph.statementRef(call, target.index) == target) { "归并陈述身份已变化" }
        return respond(runId, ReductionGraph.feedbackRef(target), expectedRunVersion, decision, userText, expectedVersion)
    }

    /** Maintain independent user text after source loss; storage prevents re-confirming stale evidence. */
    fun update(
        id: String,
        expectedVersion: Int,
        expectedRunVersion: Int?,
        decision: ClaimFeedbackDecision,
        userText: String?,
    ): ClaimFeedbackView {
        val previous = repository.claimFeedback(id) ?: throw IllegalArgumentException("反馈不存在或已删除")
        val updated = previous.copy(version = nextVersion(expectedVersion), decision = decision,
            userText = userText, updatedAt = clock.instant())
        return view(repository.saveClaimFeedback(updated, expectedVersion, expectedRunVersion))
    }

    /** Permanent removal has a tombstone. Use WITHDRAWN to keep an editable decision record. */
    fun delete(id: String, expectedVersion: Int) = repository.deleteClaimFeedback(id, expectedVersion)

    private fun view(feedback: StoredClaimFeedback): ClaimFeedbackView {
        val run = repository.analysisRun(feedback.runId)
        val validity = when {
            run == null -> FeedbackEvidenceState.TASK_UNAVAILABLE
            run.source != feedback.source || run.source != repository.analysisSourceStamp() -> FeedbackEvidenceState.STALE
            run.expiresAt != null && !clock.instant().isBefore(run.expiresAt) -> FeedbackEvidenceState.EXPIRED
            !repository.claimFeedbackDependenciesCurrent(run.id,
                ClaimRef(feedback.batchKey, feedback.claimIndex, feedback.claimSha256), run.version) -> FeedbackEvidenceState.DEPENDENCY_CHANGED
            else -> FeedbackEvidenceState.CURRENT
        }
        return ClaimFeedbackView(feedback, validity, run?.version)
    }

    private fun nextVersion(expected: Int?): Int {
        require(expected == null || expected in 1 until Int.MAX_VALUE - 1) { "反馈版本无效或已达上限" }
        return (expected ?: 0) + 1
    }
}
