package dev.tracelife.storage

import dev.tracelife.analysis.ClaimRef
import dev.tracelife.analysis.ClaimReview
import dev.tracelife.analysis.EvidenceReceipt
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.analysis.ReductionReviewProof
import dev.tracelife.metrics.RecordMetricReceipt
import java.time.Instant

enum class KnowledgeCategory { OBSERVATION, KNOWLEDGE_NOTE, INSIGHT }

/** Immutable publication proof. Original user feedback stays independently owned by its table. */
data class StoredKnowledgeRevision(
    val id: String,
    val revision: Int,
    val runId: String,
    val claimRef: ClaimRef,
    val source: SourceStamp,
    val scope: AnalysisQuery,
    val expiresAt: Instant?,
    val category: KnowledgeCategory,
    val claim: ProposedClaim,
    val reviewBatchKey: String,
    val reviewAttempt: Int,
    val reviewProviderId: String,
    val reviewRequestSha256: String,
    val review: ClaimReview,
    val evidence: List<EvidenceReceipt>,
    val feedbackVersion: Int,
    val topics: Set<String>,
    val createdAt: Instant,
    val publishedAt: Instant,
    val reductionProof: ReductionReviewProof? = null,
    val metricEvidence: List<RecordMetricReceipt> = emptyList(),
)

data class KnowledgeHead(val id: String, val revision: Int)
