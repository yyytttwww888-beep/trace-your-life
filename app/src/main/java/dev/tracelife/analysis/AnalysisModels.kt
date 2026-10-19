package dev.tracelife.analysis

import dev.tracelife.actions.ActionEvidencePlan
import dev.tracelife.actions.ActionEvidenceReceipt
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.SourcePage
import dev.tracelife.storage.StoredAnalysisRun
import dev.tracelife.metrics.RecordMetricReceipt
import dev.tracelife.profile.ProfileAnalysisTarget
import dev.tracelife.profile.ProfileMaintenancePlan
import dev.tracelife.profile.ProfileMaintenanceReference
import dev.tracelife.profile.ProfileRelationResult
import dev.tracelife.profile.ProfileMaintenanceProof
import java.time.Instant

data class AnalysisLimits(
    val pageSize: Int = 25,
    val fragmentCodePoints: Int = 1_024,
    val maxInputBytes: Int = 65_536,
    val inputTokenLimit: Int = 12_000,
    val outputTokenReserve: Int = 2_000,
    val maxCalls: Int = 100,
    val totalInputBytes: Long = 1_048_576,
    val maxOutputBytes: Int = 32_768,
) {
    init {
        require(pageSize in 1..100 && fragmentCodePoints in 1..4_096)
        require(maxInputBytes in 256..262_144 && inputTokenLimit in 1..100_000)
        require(outputTokenReserve in 1..32_768 && maxCalls in 1..100)
        require(totalInputBytes in maxInputBytes.toLong()..16_777_216 && maxOutputBytes in 256..65_536)
    }
}

data class AnalysisRequest(
    val question: String,
    val scope: AnalysisQuery,
    val limits: AnalysisLimits = AnalysisLimits(),
    val focusTopics: Set<String> = emptySet(),
    val expiresAt: Instant? = null,
    val profileTarget: ProfileAnalysisTarget? = null,
    val actionPeriod: Boolean = false,
) {
    init {
        require(!actionPeriod || profileTarget == null) { "行动复盘与画像生成必须使用独立任务" }
        require(question.isNotBlank() && question.length <= 2_000)
        require(focusTopics.size <= 30 && focusTopics.all { it.isNotBlank() && it.length <= 80 })
    }
}

data class EvidenceSelection(val recordId: String, val field: String, val start: Int = 0)

/** Offsets are Unicode code points, end exclusive; only the cited field is evidence. */
data class EvidenceReceipt(
    val id: String,
    val recordId: String,
    val revision: Int,
    val field: String,
    val start: Int,
    val end: Int,
    val totalCodePoints: Int,
    val sha256: String,
)

enum class ClaimKind { REPORTED_EXPERIENCE, POSSIBLE_INTERPRETATION }
data class ProposedClaim(val text: String, val evidenceIds: List<String>, val kind: ClaimKind)
data class BatchProposal(val claims: List<ProposedClaim>, val unknowns: List<String> = emptyList())

data class ClaimRef(val batchKey: String, val claimIndex: Int, val claimSha256: String)
enum class CallPurpose {
    EXTRACTION, SYNTHESIS, ACTION_SYNTHESIS, REVIEW, REDUCTION, REDUCTION_SCAN, REDUCTION_REVIEW, METRIC_ANALYSIS, PROFILE_RELATION, PROFILE_SCAN;

    val producesClaims: Boolean get() = this == EXTRACTION || this == SYNTHESIS || this == ACTION_SYNTHESIS || this == METRIC_ANALYSIS
}

/** Derived drafts are routing material, never original evidence or publishable claims. */
data class ReductionNodeRef(val batchKey: String, val attempt: Int, val outputSha256: String)
data class ReductionFeedbackToken(val claim: ClaimRef, val version: Int)
data class ReductionInput(val id: String, val node: ReductionNodeRef, val requestSha256: String,
    val feedbackTokens: List<ReductionFeedbackToken>, val includeDerivedFeedback: Boolean = false)
data class ReductionStatementRef(val root: ReductionNodeRef, val index: Int, val statementSha256: String)
data class RedactedReductionIdentity(val outputSha256: String, val statements: List<ClaimRef>)
enum class ReductionRelevance { SUPPORT, COUNTEREVIDENCE, CONTEXT, IRRELEVANT, NEEDS_CONTEXT }
data class ReductionFinding(val receiptId: String, val classification: ReductionRelevance, val reason: String,
    val issues: List<ReviewIssue> = emptyList())
data class ReductionScanResult(val findings: List<ReductionFinding>, val unknowns: List<String> = emptyList())
data class ReductionScanRef(val batchKey: String, val attempt: Int, val requestSha256: String, val outputSha256: String)
data class ReductionScanProof(val ref: ReductionScanRef, val providerId: String, val evidence: List<EvidenceReceipt>, val result: ReductionScanResult)
data class ReductionReviewProof(val target: ReductionStatementRef, val statement: ReductionStatement,
    val dependencyTokens: List<ReductionFeedbackToken>, val originalEvidence: List<EvidenceReceipt>,
    val leafRefs: List<ReductionNodeRef>, val scans: List<ReductionScanProof>)
data class ReductionReviewContext(val target: ReductionStatementRef, val feedbackVersion: Int,
    val dependencyTokens: List<ReductionFeedbackToken>, val originalEvidence: List<EvidenceReceipt>,
    val leafRefs: List<ReductionNodeRef>, val scans: List<ReductionScanProof>, val latestReview: AnalysisCall?)
data class ReductionStatement(val text: String, val inputIds: List<String>)
data class ReductionDigest(val statements: List<ReductionStatement>, val unknowns: List<String> = emptyList())
data class ReductionDependencies(val ref: ReductionNodeRef, val leafRefs: List<ReductionNodeRef>,
    val originalEvidence: List<EvidenceReceipt>, val feedbackTokens: List<ReductionFeedbackToken>)
data class ReductionNodeView(val ref: ReductionNodeRef, val inputs: List<ReductionInput>, val digest: ReductionDigest,
    val leafRefs: List<ReductionNodeRef>, val originalEvidence: List<EvidenceReceipt>)
enum class ReviewVerdict { SUPPORTED, UNSUPPORTED, CONTRADICTED, INSUFFICIENT_CONTEXT }
enum class ReviewIssue { USER_REPORT_AS_FACT, SCOPE_OVERGENERALIZATION, CAUSAL_OVERREACH, COUNTEREVIDENCE_OMISSION, INSUFFICIENT_CONTEXT, OTHER }
/** A bounded model quality check, never an independent truth or permission to publish. */
data class ClaimReview(
    val verdict: ReviewVerdict,
    val supportingEvidenceIds: List<String> = emptyList(),
    val contradictingEvidenceIds: List<String> = emptyList(),
    val issues: List<ReviewIssue> = emptyList(),
    val unknowns: List<String> = emptyList(),
)

enum class CallStatus { IN_FLIGHT, SUCCEEDED, FAILED, INTERRUPTED }
data class AnalysisCall(
    val batchKey: String,
    val attempt: Int,
    val providerId: String,
    val requestSha256: String,
    val inputBytes: Int,
    val inputTokens: Int,
    val evidence: List<EvidenceReceipt>,
    val startedAt: Instant,
    val status: CallStatus,
    val finishedAt: Instant? = null,
    val failureCode: String? = null,
    val proposal: BatchProposal? = null,
    val tokenCountKind: TokenCountKind = TokenCountKind.EXACT,
    val usage: ModelUsage? = null,
    val purpose: CallPurpose = CallPurpose.EXTRACTION,
    val reviewTarget: ClaimRef? = null,
    val review: ClaimReview? = null,
    val feedbackVersion: Int = 0,
    val reductionInputs: List<ReductionInput> = emptyList(),
    val reductionOutput: ReductionDigest? = null,
    val redactedReductionIdentity: RedactedReductionIdentity? = null,
    val reductionTarget: ReductionStatementRef? = null,
    val reductionReviewTokens: List<ReductionFeedbackToken> = emptyList(),
    val reductionScan: ReductionScanResult? = null,
    val reductionScans: List<ReductionScanRef> = emptyList(),
    val metricEvidence: List<RecordMetricReceipt> = emptyList(),
    val profileMaintenancePlanSha256: String? = null,
    val profileReference: ProfileMaintenanceReference? = null,
    val profileRelation: ProfileRelationResult? = null,
    val profileScan: ReductionScanResult? = null,
    val profileMaintenanceProof: ProfileMaintenanceProof? = null,
    val actionEvidence: List<ActionEvidenceReceipt> = emptyList(),
)

data class AnalysisRunState(val request: AnalysisRequest, val calls: List<AnalysisCall> = emptyList(),
    val profileMaintenancePlans: List<ProfileMaintenancePlan> = emptyList(),
    val actionPlan: ActionEvidencePlan? = null)
enum class AnalysisStatus { READY, NEEDS_REVIEW, NEEDS_RETRY, IN_FLIGHT, STALE, EXPIRED }
data class AnalysisTask(val stored: StoredAnalysisRun, val state: AnalysisRunState, val status: AnalysisStatus)

/** Navigation contains no source bodies and is never itself evidence for a personal claim. */
data class AnalysisRoute(
    val runId: String,
    val sourceGeneration: Long,
    val question: String,
    val focusTopics: Set<String>,
    val from: String,
    val through: String,
    val zoneId: String,
    val records: Int,
    val unknownTimeRecords: Int,
    val overlappingRecords: Int,
    val missingExactIds: Set<String>,
    val pageSize: Int,
)

data class AnalysisEvidencePage(val runId: String, val offset: Int, val page: SourcePage)
data class RecordProcessingCoverage(
    val recordId: String,
    val textCodePoints: Int,
    val completedCodePoints: Int,
    val unreadAttachments: Int,
    val unreadable: Boolean,
) {
    val complete: Boolean get() = !unreadable && unreadAttachments == 0 && textCodePoints == completedCodePoints
}
data class AnalysisCoveragePage(
    val records: List<RecordProcessingCoverage>,
    val totalRecords: Int,
    val unknownTimeRecords: Int,
    val overlappingRecords: Int,
    val nextOffset: Int?,
)

/** The adapter must send exactly this complete input; it may not append hidden history/tools. */
data class ModelRequest(val payloadJson: String, val idempotencyKey: String, val maximumOutputTokens: Int)

enum class TokenCountKind { EXACT, CONSERVATIVE_UTF8_BOUND }
data class ModelUsage(val promptTokens: Int, val completionTokens: Int)
data class PreparedModelRequest(val original: ModelRequest, val payloadJson: String, val inputTokens: Int,
    val tokenCountKind: TokenCountKind = TokenCountKind.EXACT)
data class ModelCompletion(val proposal: BatchProposal, val usage: ModelUsage? = null)
data class ReviewCompletion(val review: ClaimReview, val usage: ModelUsage? = null)

data class ReductionScanCompletion(val result: ReductionScanResult, val usage: ModelUsage? = null)
interface SemanticReductionScanProvider {
    val id: String
    val contextWindowTokens: Int
    fun prepareScan(request: ModelRequest): PreparedModelRequest
    fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion
}

interface SemanticReviewProvider {
    val id: String
    val contextWindowTokens: Int
    fun prepareReview(request: ModelRequest): PreparedModelRequest
    fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion
}

/** Trusted adapters prepare the exact wire body before the executor checks bytes, tokens and hash.
 * Conservative counts are labeled separately from model-specific exact tokenization. */
interface AnalysisProvider {
    val id: String
    val contextWindowTokens: Int
    fun countInputTokens(request: ModelRequest): Int
    fun complete(request: ModelRequest): BatchProposal
    fun prepare(request: ModelRequest): PreparedModelRequest = PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
    fun completePrepared(request: PreparedModelRequest): ModelCompletion = ModelCompletion(complete(request.original))
}

/** Deletion must fall back to a permanent task tombstone, never partially erase a full ledger. */
class AnalysisRedactionCapacityException : IllegalStateException("反馈清理后的身份台账超过保存上限，须永久清理关联任务")
internal class AnalysisPayloadCapacityException(message: String) : IllegalArgumentException(message)
class AnalysisBudgetException(message: String) : IllegalStateException(message)
class AnalysisConflictException(message: String) : IllegalStateException(message)
class AnalysisDependencyBlockedException(message: String) : IllegalStateException(message)
class AnalysisValidationException(message: String) : IllegalArgumentException(message)
class AnalysisTaskUnavailableException : IllegalArgumentException("分析任务不存在或已清理")

enum class ProviderFailureCode { CONFIGURATION, CREDENTIAL_ACCESS, AUTHENTICATION, BALANCE, RATE_LIMIT, TIMEOUT, NETWORK, SERVER, REQUEST, REDIRECT, RESPONSE, USAGE }
open class AnalysisProviderException(val code: ProviderFailureCode) : IllegalStateException("模型请求未完成：${code.name}")
