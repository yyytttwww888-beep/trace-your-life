package dev.tracelife.profile

import dev.tracelife.analysis.AnalysisProcessingCoverage
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.ClaimRef
import dev.tracelife.analysis.ClaimReview
import dev.tracelife.analysis.EvidenceReceipt
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.analysis.SynthesisIntegration
import dev.tracelife.storage.SourceStamp
import java.time.Instant

enum class ProfileCandidateStatus {
    READY, PASS_FINISHED, NEEDS_ATTENTION, NO_TEXT, PASS_LIMIT_REACHED, CANCELLED,
    RETRY_NEEDED, BUDGET_EXHAUSTED, NEEDS_RECOVERY, STALE, EXPIRED, CLOCK_ROLLBACK,
    TASK_UNAVAILABLE, CONFLICT,
}
enum class ProfileCandidateDisposition {
    PENDING_REVIEW, AWAITING_CONFIRMATION, READY_TO_MATERIALIZE,
    REVIEW_UNSUPPORTED, REVIEW_CONTRADICTED, REVIEW_INSUFFICIENT_CONTEXT,
    FEEDBACK_BLOCKED, FEEDBACK_DELETED, KNOWLEDGE_DELETED, PROFILE_DELETED,
    EXISTING_PROFILE, PROFILE_CONFLICT, MAINTENANCE_REQUIRED,
}
/** Navigation contains identities and workflow states, never model or user prose. */
data class ProfileCandidateItem(
    val claim: ClaimRef,
    val disposition: ProfileCandidateDisposition,
    val feedbackVersion: Int,
    val reviewVerdict: ReviewVerdict?,
    val knowledgeId: String,
    val knowledgeRevision: Int?,
    val profile: ProfileHead?,
    val adoption: ProfileAdoption?,
)
data class ProfileCandidateProgress(
    val runId: String,
    val status: ProfileCandidateStatus,
    val target: ProfileAnalysisTarget?,
    val source: SourceStamp?,
    val taskVersion: Int?,
    val analysisGeneration: Long?,
    val evaluatedAt: Instant,
    val validUntil: Instant?,
    val coverage: AnalysisProcessingCoverage?,
    val integration: SynthesisIntegration,
    val candidates: List<ProfileCandidateItem>,
    val total: Int,
    val nextOffset: Int?,
    val pageSize: Int,
) {
    val counterevidenceSearchExhaustive: Boolean get() = false
    val crossTaskSemanticDeduplication: Boolean get() = false
    val manualDeletedSlotTracking: Boolean get() = false
}
data class ProfileCandidateDetail(
    val item: ProfileCandidateItem,
    val proposal: ProposedClaim,
    /** All generation inputs, including context the proposal did not cite. */
    val evidence: List<EvidenceReceipt>,
    val review: ClaimReview?,
)
data class ProfileCandidateReport(
    val runId: String,
    val status: ProfileCandidateStatus,
    val modelRequestsThisPass: Int,
    val generatedBatchesThisPass: Int,
    val reviewedThisPass: Int,
    val progress: ProfileCandidateProgress,
)
enum class ProfileCandidateMaterializationStatus { CREATED, EXISTING, DELETED, CONFLICT }
data class ProfileCandidateMaterialization(
    val status: ProfileCandidateMaterializationStatus,
    val knowledge: ProfileKnowledgeRef?,
    val profile: ProfileHead?,
    val adoption: ProfileAdoption?,
) {
    val crossTaskSemanticDeduplication: Boolean get() = false
    val manualDeletedSlotTracking: Boolean get() = false
}

/** Does not include knowledge revision: later publication cannot bypass an earlier user decision. */
internal fun profileCandidateId(knowledgeId: String, target: ProfileAnalysisTarget): String =
    "profile-candidate-" + AnalysisRunCodec.sha256(AnalysisRunCodec.json(
        listOf("profile-candidate-v1", knowledgeId, target.dimension.name, target.layer.name)))
