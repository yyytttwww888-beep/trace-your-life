package dev.tracelife.storage

import android.util.JsonWriter
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRunState
import dev.tracelife.analysis.CallPurpose
import dev.tracelife.analysis.CallStatus
import dev.tracelife.analysis.ClaimRef
import java.io.StringWriter
import java.time.Instant

enum class ClaimFeedbackDecision { CONFIRMED, CORRECTED, REJECTED, WITHDRAWN }

/** User authority is independent of model wording and survives deletion of its original sources.
 * [userText] is only explicitly submitted user text, never an automatic copy of a model claim. */
data class StoredClaimFeedback(
    val id: String,
    val version: Int,
    val runId: String,
    val batchKey: String,
    val claimIndex: Int,
    val claimSha256: String,
    val source: SourceStamp,
    val decision: ClaimFeedbackDecision,
    val userText: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

internal object ClaimFeedbackValidation {
    const val MAX_ROWS = 10_000
    const val MAX_BYTES = 4L * 1024 * 1024
    const val MAX_DELETIONS = 100_000

    fun validate(value: StoredClaimFeedback) {
        SnapshotValidation.id(value.runId)
        SnapshotValidation.id(value.batchKey)
        SnapshotValidation.id(value.source.storeId)
        require(value.version in 1 until Int.MAX_VALUE && value.claimIndex in 0..19 && value.source.generation >= 0 &&
            value.updatedAt >= value.createdAt) { "反馈身份、版本或时间无效" }
        require(value.claimSha256.matches(Regex("[0-9a-f]{64}"))) { "反馈内容摘要无效" }
        require(value.id == AnalysisRunCodec.feedbackId(value.runId, ClaimRef(value.batchKey, value.claimIndex, value.claimSha256))) { "反馈身份与目标不一致" }
        require(value.userText == null || value.userText.length <= 2_000 && value.userText.isNotBlank()) { "独立文字须为 1 至 2000 字符" }
        require(value.decision != ClaimFeedbackDecision.CORRECTED || !value.userText.isNullOrBlank()) { "修改反馈须提供独立文字" }
    }

    fun validateTarget(value: StoredClaimFeedback, run: StoredAnalysisRun, state: AnalysisRunState = AnalysisRunCodec.decode(run.payload)) {
        require(matchesTarget(value, run, state)) { "反馈来源或目标与已保存的提取结论不一致" }
    }

    fun matchesTarget(value: StoredClaimFeedback, run: StoredAnalysisRun, state: AnalysisRunState): Boolean {
        if (value.runId != run.id || value.source != run.source) return false
        val call = state.calls.singleOrNull { it.batchKey == value.batchKey && it.status == CallStatus.SUCCEEDED && it.purpose == CallPurpose.EXTRACTION }
        val claim = call?.proposal?.claims?.getOrNull(value.claimIndex)
        return claim != null && AnalysisRunCodec.claimSha256(claim) == value.claimSha256
    }

    /** Same JSON field order and escaping in storage budgets and portable backup output. */
    fun writeJson(writer: JsonWriter, value: StoredClaimFeedback) {
        writer.beginObject()
        writer.name("id").value(value.id)
        writer.name("version").value(value.version.toLong())
        writer.name("runId").value(value.runId)
        writer.name("batchKey").value(value.batchKey)
        writer.name("claimIndex").value(value.claimIndex.toLong())
        writer.name("claimSha256").value(value.claimSha256)
        writer.name("source").beginObject()
        writer.name("storeId").value(value.source.storeId)
        writer.name("generation").value(value.source.generation)
        writer.endObject()
        writer.name("decision").value(value.decision.name)
        writer.name("userText").value(value.userText)
        writer.name("createdAt").value(value.createdAt.toString())
        writer.name("updatedAt").value(value.updatedAt.toString())
        writer.endObject()
    }

    fun encodedBytes(value: StoredClaimFeedback): Int {
        val output = StringWriter()
        JsonWriter(output).use { writeJson(it, value) }
        return output.toString().toByteArray(Charsets.UTF_8).size
    }

    fun validateCollection(values: List<StoredClaimFeedback>) {
        require(values.size <= MAX_ROWS && values.map { it.id }.distinct().size == values.size) { "反馈数量过多或身份重复" }
        var bytes = 0L
        values.forEach { value ->
            validate(value)
            bytes += encodedBytes(value)
            require(bytes <= MAX_BYTES) { "反馈超过 4 MiB 保存上限" }
        }
    }
}
