package dev.tracelife.actions

import dev.tracelife.storage.SnapshotValidation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Identity only; never retains a machine quotation when its source is erased. */
data class ActionOrigin(val knowledgeId: String, val revision: Int)
data class ActionDraft(
    val title: String,
    val trigger: String,
    val behavior: String,
    val expectedSignal: String,
    val reviewOn: LocalDate,
    val zoneId: String,
    val origin: ActionOrigin? = null,
)
enum class ActionStatus { OPEN, STOPPED }
enum class ActionExecution { UNKNOWN, NOT_EXECUTED, EXECUTED }
enum class ActionDecision { CONTINUE, ADJUST, STOP, DEFER }
data class ActionReview(
    val execution: ActionExecution,
    val observedText: String,
    val decision: ActionDecision,
    val reviewedOn: LocalDate,
)
data class StoredActionRevision(
    val id: String,
    val revision: Int,
    val draft: ActionDraft,
    val status: ActionStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val operationId: String? = null,
    val review: ActionReview? = null,
    /** The exact plan evaluated by this review, before continuing or adjusting it. */
    val reviewedDraft: ActionDraft? = null,
)
data class ActionHead(val id: String, val revision: Int)

object ActionValidation {
    const val MAX_REVISIONS = 10_000
    const val MAX_BYTES = 16L * 1024 * 1024
    // A review retains both plans and user observations, including worst-case JSON escaping.
    const val MAX_REVISION_BYTES = 131_072
    const val MAX_DELETIONS = 100_000

    fun draft(value: ActionDraft) {
        require(value.title.isNotBlank() && value.title.length <= 200)
        require(value.trigger.isNotBlank() && value.trigger.length <= 2_000)
        require(value.behavior.isNotBlank() && value.behavior.length <= 4_000)
        require(value.expectedSignal.isNotBlank() && value.expectedSignal.length <= 2_000)
        require(value.reviewOn.year in 1..9999)
        ZoneId.of(value.zoneId)
        value.origin?.let {
            require(it.knowledgeId.matches(Regex("[0-9a-f]{64}")) && it.revision in 1 until Int.MAX_VALUE)
        }
    }

    fun validate(value: StoredActionRevision) {
        SnapshotValidation.id(value.id)
        require(value.revision in 1 until Int.MAX_VALUE && value.updatedAt >= value.createdAt)
        draft(value.draft)
        require((value.operationId == null) == (value.review == null) && (value.review == null) == (value.reviewedDraft == null))
        if (value.revision == 1) require(value.review == null && value.status == ActionStatus.OPEN && value.createdAt == value.updatedAt)
        val review = value.review
        if (review == null) require(value.status == ActionStatus.OPEN)
        else {
            SnapshotValidation.id(requireNotNull(value.operationId))
            val previous = requireNotNull(value.reviewedDraft).also(::draft)
            require(review.observedText.isNotBlank() && review.observedText.length <= 4_000 && review.reviewedOn.year in 1..9999)
            require(review.reviewedOn <= value.updatedAt.atZone(ZoneId.of(previous.zoneId)).toLocalDate()) { "回看日期不能在未来" }
            if (review.decision == ActionDecision.STOP) {
                require(value.status == ActionStatus.STOPPED && value.draft == previous)
            } else {
                require(value.status == ActionStatus.OPEN && value.draft.reviewOn > review.reviewedOn) { "下一次回看日期须晚于本次回看" }
                if (review.decision != ActionDecision.ADJUST)
                    require(value.draft == previous.copy(reviewOn = value.draft.reviewOn)) { "继续或暂缓只能调整回看日期" }
            }
        }
    }

    fun validateCollection(revisions: List<StoredActionRevision>, heads: List<ActionHead>) {
        require(revisions.size <= MAX_REVISIONS && heads.size <= MAX_REVISIONS)
        require(revisions.map { it.id to it.revision }.distinct().size == revisions.size && heads.map { it.id }.distinct().size == heads.size)
        val groups = revisions.groupBy { it.id }
        require(groups.keys == heads.map { it.id }.toSet()) { "行动修订和当前指针不一致" }
        val headMap = heads.associateBy { it.id }
        var bytes = 0L
        groups.forEach { (id, values) ->
            val ordered = values.sortedBy { it.revision }
            require(ordered.map { it.revision } == (1..ordered.size).toList()) { "行动修订链不完整" }
            require(headMap[id]?.revision == ordered.last().revision)
            require(ordered.map { it.createdAt }.distinct().size == 1)
            require(ordered.mapNotNull { it.operationId }.distinct().size == ordered.count { it.operationId != null }) { "行动回看操作重复" }
            ordered.forEach { value ->
                validate(value)
                bytes += ActionCodec.encode(value).toByteArray(Charsets.UTF_8).size
                require(bytes <= MAX_BYTES) { "行动修订超过总保存上限" }
            }
            ordered.zipWithNext().forEach { (previous, next) -> validateTransition(previous, next) }
        }
    }

    fun validateTransition(previous: StoredActionRevision, next: StoredActionRevision) {
        require(previous.id == next.id && next.revision == previous.revision + 1 && previous.createdAt == next.createdAt &&
            next.updatedAt >= previous.updatedAt && previous.status == ActionStatus.OPEN) { "行动版本或状态转换无效" }
        if (next.review != null) require(next.reviewedDraft == previous.draft) { "行动回看须绑定当时的计划" }
    }
}
