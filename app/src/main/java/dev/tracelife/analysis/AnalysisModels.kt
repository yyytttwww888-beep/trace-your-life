package dev.tracelife.analysis

import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.SourcePage
import dev.tracelife.storage.StoredAnalysisRun
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
) {
    init {
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
)

data class AnalysisRunState(val request: AnalysisRequest, val calls: List<AnalysisCall> = emptyList())
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

class AnalysisBudgetException(message: String) : IllegalStateException(message)
class AnalysisConflictException(message: String) : IllegalStateException(message)
class AnalysisValidationException(message: String) : IllegalArgumentException(message)
class AnalysisTaskUnavailableException : IllegalArgumentException("分析任务不存在或已清理")

enum class ProviderFailureCode { CONFIGURATION, CREDENTIAL_ACCESS, AUTHENTICATION, BALANCE, RATE_LIMIT, TIMEOUT, NETWORK, SERVER, REQUEST, REDIRECT, RESPONSE, USAGE }
open class AnalysisProviderException(val code: ProviderFailureCode) : IllegalStateException("模型请求未完成：${code.name}")
