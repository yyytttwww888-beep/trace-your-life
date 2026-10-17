package dev.tracelife.transfer

import dev.tracelife.actions.ActionValidation
import dev.tracelife.actions.StoredActionRevision

/** User-selected plans are not evidence of execution, growth, or a valid originating conclusion. */
internal object ActionExport {
    data class Selection(
        val entries: List<StoredActionRevision>,
        val sourceHeads: Int,
        val omittedHistoricalRevisions: Int,
        val omittedByReason: Map<String, Int>,
    ) { val omitted get() = sourceHeads - entries.size }

    fun select(snapshot: RepositorySnapshot, options: ReadingExportOptions): Selection {
        ActionValidation.validateCollection(snapshot.actionRevisions, snapshot.actionHeads)
        val revisions = snapshot.actionRevisions.associateBy { it.id to it.revision }
        val deleted = snapshot.deletedActions.map { it.id }.toSet()
        val omitted = linkedMapOf<String, Int>()
        val entries = snapshot.actionHeads.sortedBy { it.id }.mapNotNull { head ->
            val value = revisions.getValue(head.id to head.revision)
            val reason = when {
                options.kind == ReadingPackageKind.EVIDENCE -> "evidence_package"
                head.id in deleted -> "deleted"
                value.updatedAt > snapshot.capturedAt -> "not_yet_current"
                options.scope.topics != null -> "action_topics_unavailable"
                options.scope.from != null && value.draft.reviewOn < options.scope.from -> "review_date"
                options.scope.through != null && value.draft.reviewOn > options.scope.through -> "review_date"
                else -> null
            }
            if (reason == null) value else { omitted[reason] = omitted.getOrDefault(reason, 0) + 1; null }
        }
        return Selection(entries, snapshot.actionHeads.size, snapshot.actionRevisions.size - snapshot.actionHeads.size, omitted)
    }
}
