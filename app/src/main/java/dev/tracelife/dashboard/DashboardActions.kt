package dev.tracelife.dashboard

import dev.tracelife.actions.ActionDecision
import dev.tracelife.actions.ActionExecution
import dev.tracelife.actions.ActionOrigin
import dev.tracelife.actions.ActionOriginState
import dev.tracelife.actions.ActionStatus
import dev.tracelife.actions.ActionValidation
import dev.tracelife.actions.StoredActionRevision
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/** An absent review is different from a review that explicitly could not establish execution. */
enum class DashboardActionExecution { NOT_REVIEWED, UNKNOWN, NOT_EXECUTED, EXECUTED }

data class DashboardActionReviewRef(
    val actionId: String,
    val revision: Int,
    val operationId: String,
    val reviewedOn: LocalDate,
    val zoneId: String,
    val execution: ActionExecution,
    val decision: ActionDecision,
)

/** Current global plans: a chart window never hides an overdue plan outside that window. */
data class DashboardActionItem(
    val id: String,
    val revision: Int,
    val title: String,
    val status: ActionStatus,
    val reviewOn: LocalDate,
    val zoneId: String,
    val due: Boolean,
    val originState: ActionOriginState,
    val latestExecution: DashboardActionExecution,
    val latestReview: DashboardActionReviewRef?,
)

enum class DashboardActionMarkerKind { ADOPTED, REVIEWED }

/** Adoption has an instant; a review has only the original plan's local date label.
 * Neither kind asserts that a plan began, succeeded, or caused a change in a metric. */
data class DashboardActionMarker(
    val kind: DashboardActionMarkerKind,
    val actionId: String,
    val revision: Int,
    val title: String,
    val date: LocalDate,
    val zoneId: String,
    val occurredAt: Instant?,
    val operationId: String?,
    val execution: ActionExecution?,
    val decision: ActionDecision?,
)

internal data class DashboardActionCollection(
    val items: List<DashboardActionItem>,
    val markers: List<DashboardActionMarker>,
    val validUntil: Instant?,
)

/** Bounded metadata only. Independent plan and observation bodies remain in revision leaves. */
internal class DashboardActionCollector(private val repository: LifeRepository, private val clock: Clock) {
    fun collect(
        source: SourceStamp,
        analysisGeneration: Long,
        evaluatedAt: Instant,
        scope: AnalysisQuery,
    ): DashboardActionCollection {
        assertSource(source, analysisGeneration)
        val knowledge = KnowledgeLibrary(repository, Clock.fixed(evaluatedAt, clock.zone))
        val originCache = mutableMapOf<ActionOrigin, Pair<ActionOriginState, Instant?>>()
        val items = mutableListOf<DashboardActionItem>()
        val markers = mutableListOf<DashboardActionMarker>()
        var validUntil: Instant? = null
        var revisionsRead = 0
        var cursor = 0

        fun includeExpiry(expiry: Instant?) {
            if (expiry != null && (validUntil == null || expiry < requireNotNull(validUntil))) validUntil = expiry
        }

        do {
            val heads = repository.actionHeads(cursor, PAGE_SIZE)
            heads.forEach { head ->
                require(items.size < ActionValidation.MAX_REVISIONS) { "看板行动目录超过保存上限" }
                val current = repository.actionRevision(head.id, head.revision)
                    ?: throw StaleSnapshotException("看板行动在读取期间变化")
                val actionZone = ZoneId.of(current.draft.zoneId)
                includeExpiry(evaluatedAt.atZone(actionZone).toLocalDate().plusDays(1).atStartOfDay(actionZone).toInstant())
                val origin = current.draft.origin?.let { identity ->
                    originCache.getOrPut(identity) {
                        val view = knowledge.get(identity.knowledgeId, identity.revision)
                        when {
                            view == null -> ActionOriginState.UNKNOWN to null
                            view.isCurrentRevision && view.evidenceState == KnowledgeEvidenceState.CURRENT ->
                                ActionOriginState.CURRENT to view.value.expiresAt
                            else -> ActionOriginState.STALE to null
                        }
                    }
                } ?: (ActionOriginState.NOT_LINKED to null)
                includeExpiry(origin.second)

                var latestReview: DashboardActionReviewRef? = null
                var historyOffset = 0
                var expectedRevision = head.revision
                do {
                    val history = repository.actionHistory(head.id, historyOffset, PAGE_SIZE)
                    history.forEach { value ->
                        require(++revisionsRead <= ActionValidation.MAX_REVISIONS) { "看板行动修订超过保存上限" }
                        if (value.revision != expectedRevision--)
                            throw StaleSnapshotException("看板行动修订在读取期间变化")
                        if (value.revision == 1) {
                            val adopted = value.createdAt.atZone(ZoneId.of(scope.zoneId))
                            if (inScope(adopted.toLocalDate(), scope)) markers += DashboardActionMarker(
                                DashboardActionMarkerKind.ADOPTED, value.id, value.revision, value.draft.title,
                                adopted.toLocalDate(), scope.zoneId, value.createdAt, null, null, null,
                            )
                        }
                        reviewRef(value)?.let { review ->
                            if (latestReview == null) latestReview = review
                            if (inScope(review.reviewedOn, scope)) markers += DashboardActionMarker(
                                DashboardActionMarkerKind.REVIEWED, value.id, value.revision,
                                requireNotNull(value.reviewedDraft).title, review.reviewedOn, review.zoneId,
                                null, review.operationId, review.execution, review.decision,
                            )
                        }
                    }
                    historyOffset += history.size
                } while (history.size == PAGE_SIZE)
                if (expectedRevision != 0) throw StaleSnapshotException("看板行动修订不完整")

                items += DashboardActionItem(
                    current.id, current.revision, current.draft.title, current.status,
                    current.draft.reviewOn, current.draft.zoneId,
                    current.status == ActionStatus.OPEN && current.draft.reviewOn <= evaluatedAt.atZone(actionZone).toLocalDate(),
                    origin.first,
                    latestReview?.execution?.let { DashboardActionExecution.valueOf(it.name) } ?: DashboardActionExecution.NOT_REVIEWED,
                    latestReview,
                )
            }
            cursor += heads.size
        } while (heads.size == PAGE_SIZE)
        assertSource(source, analysisGeneration)
        return DashboardActionCollection(
            Collections.unmodifiableList(items.sortedWith(compareBy<DashboardActionItem> { it.reviewOn }.thenBy { it.id })),
            Collections.unmodifiableList(markers.sortedWith(compareBy<DashboardActionMarker> { it.date }
                .thenBy { it.kind }.thenBy { it.actionId }.thenBy { it.revision })),
            validUntil,
        )
    }

    private fun reviewRef(value: StoredActionRevision): DashboardActionReviewRef? = value.review?.let { review ->
        DashboardActionReviewRef(value.id, value.revision, requireNotNull(value.operationId), review.reviewedOn,
            requireNotNull(value.reviewedDraft).zoneId, review.execution, review.decision)
    }

    private fun assertSource(source: SourceStamp, generation: Long) {
        if (repository.analysisSourceStamp() != source || repository.currentAnalysisGeneration() != generation)
            throw StaleSnapshotException("看板行动或其来源已变化，请重新打开看板")
    }

    private fun inScope(date: LocalDate, scope: AnalysisQuery) = date >= scope.from && date <= scope.through

    private companion object { const val PAGE_SIZE = 100 }
}
