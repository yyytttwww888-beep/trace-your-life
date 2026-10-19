package dev.tracelife.transfer

import dev.tracelife.actions.ActionEvidenceProtocol
import dev.tracelife.actions.ActionEvidenceReceipt
import dev.tracelife.actions.ActionEvidencePlan
import dev.tracelife.actions.StoredActionRevision
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.StoredKnowledgeRevision
import dev.tracelife.storage.AnalysisQuery

/** A saved proof routes to the exact projection of its immutable action revision, never its head. */
internal object ActionEvidenceExport {
    data class Entry(val receipt: ActionEvidenceReceipt, val source: StoredActionRevision,
        val knowledgeIds: List<String>)

    /** Reuse immutable source hashes and scope plans across all heads in one export. */
    class Validator(private val snapshot: RepositorySnapshot) {
        private val deleted = snapshot.deletedActions.map { it.id }.toSet()
        private val sources = snapshot.actionRevisions.associateBy { it.id to it.revision }
        private val retained = snapshot.actionRevisions.filter { it.id !in deleted }
        private val plans = mutableMapOf<AnalysisQuery, ActionEvidencePlan>()
        private val validated = mutableSetOf<ActionEvidenceReceipt>()

        fun omission(value: StoredKnowledgeRevision, scope: ExportScope): String? {
            val plan = value.actionPlan ?: return null
            if (plan.entries.any { it.actionId in deleted }) return "action_deleted"
            // Verify saved receipts before deciding currentness: corrupt immutable sources fail closed.
            (plan.entries + KnowledgeValidation.allActionEvidence(value)).distinct().forEach { receipt ->
                if (validated.add(receipt)) {
                    val original = requireNotNull(sources[receipt.actionId to receipt.revision]) { "行动证据缺少原始修订" }
                    require(ActionEvidenceProtocol.receipt(original, receipt.kind) == receipt) { "行动证据与原始修订不匹配" }
                }
            }
            if (plans.getOrPut(value.scope) { ActionEvidenceProtocol.plan(value.scope, retained) } != plan)
                return "action_evidence_changed"
            if (scope.topics != null) return "action_topics_unavailable"
            if (scope.from?.let { it > value.scope.from } == true || scope.through?.let { it < value.scope.through } == true)
                return "action_scope_not_contained"
            if (plan.entries.any { sources.getValue(it.actionId to it.revision).updatedAt > snapshot.capturedAt })
                return "action_not_yet_saved"
            return null
        }
    }

    fun select(snapshot: RepositorySnapshot, knowledge: PublishedKnowledgeExport.Selection): List<Entry> {
        val sources = snapshot.actionRevisions.associateBy { it.id to it.revision }
        val uses = knowledge.entries.flatMap { entry -> KnowledgeValidation.allActionEvidence(entry.value)
            .map { it to entry.value.id } }
        return uses.groupBy { it.first.id }.toSortedMap().values.map { group ->
            val receipt = group.first().first
            require(group.all { it.first == receipt }) { "行动证据身份冲突" }
            val source = requireNotNull(sources[receipt.actionId to receipt.revision]) { "行动原始修订缺失" }
            require(ActionEvidenceProtocol.receipt(source, receipt.kind) == receipt) { "行动原始修订校验失败" }
            Entry(receipt, source, group.map { it.second }.distinct().sorted())
        }
    }
}
