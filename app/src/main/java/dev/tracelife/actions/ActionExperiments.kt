package dev.tracelife.actions

import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class ActionOriginState { NOT_LINKED, CURRENT, STALE, UNKNOWN }
data class ActionView(
    val value: StoredActionRevision,
    val isCurrentRevision: Boolean,
    val originState: ActionOriginState,
    val due: Boolean,
    val evaluatedAt: Instant,
)
data class ActionFilter(val status: ActionStatus? = null, val dueOnly: Boolean = false)
data class ActionIndexItem(
    val id: String, val revision: Int, val title: String, val status: ActionStatus,
    val reviewOn: LocalDate, val zoneId: String, val due: Boolean, val originState: ActionOriginState,
)
data class ActionPage(
    val source: SourceStamp, val analysisGeneration: Long, val evaluatedAt: Instant,
    val items: List<ActionIndexItem>, val total: Int, val nextOffset: Int?,
    val filter: ActionFilter, val pageSize: Int,
)

/** User commands only. Models cannot adopt actions or turn a suggestion into user-authored text. */
class ActionExperiments(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    fun create(draft: ActionDraft, id: String = UUID.randomUUID().toString()): ActionView {
        ActionValidation.draft(draft)
        val now = clock.instant()
        return view(repository.saveAction(StoredActionRevision(id, 1, draft, ActionStatus.OPEN, now, now), null), now)
    }

    fun update(id: String, expectedRevision: Int, draft: ActionDraft): ActionView {
        val previous = requireNotNull(repository.actionRevision(id, expectedRevision)) { "行动不存在或已删除" }
        val now = clock.instant()
        return view(repository.saveAction(previous.copy(revision = next(expectedRevision), draft = draft, updatedAt = now,
            operationId = null, review = null, reviewedDraft = null), expectedRevision), now)
    }

    /** Every review has an explicit retry identity. Replaying it never appends a second result. */
    fun review(
        id: String,
        expectedRevision: Int,
        operationId: String,
        review: ActionReview,
        nextReviewOn: LocalDate? = null,
        adjustedDraft: ActionDraft? = null,
    ): ActionView {
        val previous = requireNotNull(repository.actionRevision(id, expectedRevision)) { "行动不存在或已删除" }
        val now = clock.instant()
        val draft = when (review.decision) {
            ActionDecision.STOP -> {
                require(nextReviewOn == null && adjustedDraft == null) { "停止行动无需下一轮计划" }
                previous.draft
            }
            ActionDecision.ADJUST -> {
                requireNotNull(adjustedDraft) { "调整须明确提交下一轮计划" }.also {
                    require(nextReviewOn == null || nextReviewOn == it.reviewOn)
                }
            }
            else -> {
                require(adjustedDraft == null) { "改变做法须选择调整" }
                previous.draft.copy(reviewOn = requireNotNull(nextReviewOn) { "继续或暂缓须选择下一次回看日期" })
            }
        }
        // The same persisted review remains retryable after its next review date has passed.
        val replay = repository.actionOperation(id, operationId) != null
        if (review.decision != ActionDecision.STOP && !replay)
            require(draft.reviewOn > now.atZone(ZoneId.of(draft.zoneId)).toLocalDate()) { "下一次回看须在当前当地日期之后" }
        val value = StoredActionRevision(id, next(expectedRevision), draft,
            if (review.decision == ActionDecision.STOP) ActionStatus.STOPPED else ActionStatus.OPEN,
            previous.createdAt, now, operationId, review, previous.draft)
        return view(repository.saveAction(value, expectedRevision), now)
    }

    fun stop(id: String, expectedRevision: Int, operationId: String, observedText: String,
        execution: ActionExecution = ActionExecution.UNKNOWN): ActionView {
        val previous = requireNotNull(repository.actionRevision(id, expectedRevision)) { "行动不存在或已删除" }
        val date = repository.actionOperation(id, operationId)?.review?.reviewedOn
            ?: clock.instant().atZone(ZoneId.of(previous.draft.zoneId)).toLocalDate()
        return review(id, expectedRevision, operationId, ActionReview(execution, observedText, ActionDecision.STOP, date))
    }

    fun get(id: String, revision: Int? = null): ActionView? = consistent { _, _, now ->
        repository.actionRevision(id, revision)?.let { view(it, now) }
    }

    fun history(id: String, offset: Int = 0, limit: Int = 25): List<ActionView> = consistent { _, _, now ->
        repository.actionHistory(id, offset, limit).map { view(it, now) }
    }

    fun list(filter: ActionFilter = ActionFilter(), limit: Int = 25): ActionPage = page(filter, 0, limit)
    fun next(page: ActionPage): ActionPage = page(page.filter, requireNotNull(page.nextOffset) { "已到行动列表末页" }, page.pageSize, page)
    fun delete(id: String, expectedRevision: Int) = repository.deleteAction(id, expectedRevision)

    private fun page(filter: ActionFilter, offset: Int, limit: Int, previous: ActionPage? = null): ActionPage {
        require(offset >= 0 && limit in 1..100)
        return consistent { source, generation, now ->
            if (previous != null && (previous.source != source || previous.analysisGeneration != generation))
                throw StaleSnapshotException("行动列表已变化，请从首页重新读取")
            val at = previous?.evaluatedAt ?: now
            val items = mutableListOf<ActionIndexItem>()
            var total = 0
            var cursor = 0
            do {
                val heads = repository.actionHeads(cursor, 100)
                heads.forEach { head ->
                    val value = requireNotNull(repository.actionRevision(head.id, head.revision))
                    val due = due(value, at)
                    if (filter.status != null && value.status != filter.status || filter.dueOnly && !due) return@forEach
                    if (total >= offset && items.size < limit) items += ActionIndexItem(value.id, value.revision,
                        value.draft.title, value.status, value.draft.reviewOn, value.draft.zoneId, due, originState(value, at))
                    total++
                }
                cursor += heads.size
            } while (heads.size == 100)
            ActionPage(source, generation, at, items, total, (offset.toLong() + items.size).takeIf { it < total }?.toInt(), filter, limit)
        }
    }

    private fun view(value: StoredActionRevision, at: Instant): ActionView = ActionView(value,
        repository.actionRevision(value.id)?.revision == value.revision, originState(value, at), due(value, at), at)

    private fun due(value: StoredActionRevision, at: Instant): Boolean = value.status == ActionStatus.OPEN &&
        value.draft.reviewOn <= at.atZone(ZoneId.of(value.draft.zoneId)).toLocalDate()

    private fun originState(value: StoredActionRevision, at: Instant): ActionOriginState {
        val origin = value.draft.origin ?: return ActionOriginState.NOT_LINKED
        val knowledge = KnowledgeLibrary(repository, Clock.fixed(at, clock.zone)).get(origin.knowledgeId, origin.revision)
            ?: return ActionOriginState.UNKNOWN
        return if (knowledge.isCurrentRevision && knowledge.evidenceState == KnowledgeEvidenceState.CURRENT)
            ActionOriginState.CURRENT else ActionOriginState.STALE
    }

    private fun <T> consistent(block: (SourceStamp, Long, Instant) -> T): T {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val result = block(source, generation, clock.instant())
        if (repository.analysisSourceStamp() != source || repository.currentAnalysisGeneration() != generation)
            throw StaleSnapshotException("行动或其来源在读取期间变化")
        return result
    }
    private fun next(expected: Int): Int {
        require(expected in 1 until Int.MAX_VALUE - 1)
        return expected + 1
    }
}
