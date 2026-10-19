package dev.tracelife.transfer

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRunState
import dev.tracelife.model.RecordRevision
import dev.tracelife.metrics.RecordMetricProtocol
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.SourceStamp
import dev.tracelife.storage.StoredKnowledgeRevision

/** Select saved publication proofs without promoting them to new original evidence. */
internal object PublishedKnowledgeExport {
    data class Entry(val value: StoredKnowledgeRevision, val feedbackDecision: ClaimFeedbackDecision?)
    data class Selection(
        val entries: List<Entry>,
        val sourceHeads: Int,
        val omittedHistoricalRevisions: Int,
        val omittedByReason: Map<String, Int>,
    ) {
        val omitted get() = sourceHeads - entries.size
    }

    fun select(
        snapshot: RepositorySnapshot,
        currentRecords: List<RecordRevision>,
        includedRecords: List<RecordRevision>,
        options: ReadingExportOptions,
    ): Selection {
        KnowledgeValidation.validateCollection(snapshot.knowledgeRevisions, snapshot.knowledgeHeads)
        val revisions = snapshot.knowledgeRevisions.associateBy { it.id to it.revision }
        val deleted = snapshot.deletedKnowledge.map { it.id }.toSet()
        val records = currentRecords.associateBy { it.id }
        val included = includedRecords.map { it.id }.toSet()
        val feedback = snapshot.claimFeedback.associateBy { it.id }
        val deletedFeedback = snapshot.deletedClaimFeedback.associateBy { it.id }
        val runs = snapshot.analysisRuns.associateBy { it.id }
        val runStates = mutableMapOf<String, AnalysisRunState>()
        fun reviewCurrent(value: StoredKnowledgeRevision): Boolean {
            val run = runs[value.runId] ?: return KnowledgeValidation.reviewMatchesState(value, null)
            if (run.source != value.source) return false
            val state = runStates.getOrPut(run.id) { AnalysisRunCodec.decode(run.payload) }
            return KnowledgeValidation.reviewMatchesState(value, state)
        }
        val stamp = SourceStamp(snapshot.storeId, snapshot.generation)
        val validatedMetrics = mutableSetOf<String>()
        val omitted = linkedMapOf<String, Int>()
        val selected = snapshot.knowledgeHeads.sortedBy { it.id }.mapNotNull { head ->
            val value = revisions.getValue(head.id to head.revision)
            val feedbackId = AnalysisRunCodec.feedbackId(value.runId, value.claimRef)
            val response = feedback[feedbackId]
            val deletion = deletedFeedback[feedbackId]
            val token = deletion?.let { it.lastRevision.toLong() + 1 } ?: response?.version?.toLong() ?: 0L
            val dependencies = KnowledgeValidation.feedbackDependencies(value)
            val allSources = KnowledgeValidation.allSourceRevisions(value)
            val dependencyDeleted = dependencies.any { AnalysisRunCodec.feedbackId(value.runId, it.claim) in deletedFeedback }
            val dependencyChanged = dependencies.any { dependency ->
                val current = feedback[AnalysisRunCodec.feedbackId(value.runId, dependency.claim)]
                (current?.version ?: 0) != dependency.version || current != null &&
                    (current.source != value.source || current.decision != ClaimFeedbackDecision.CONFIRMED)
            }
            val reason = when {
                options.kind == ReadingPackageKind.EVIDENCE -> "evidence_package"
                value.actionPlan != null -> "action_evidence_not_in_reading_package"
                value.id in deleted -> "deleted"
                value.source != stamp -> "stale_source"
                value.publishedAt > snapshot.capturedAt -> "not_yet_published"
                value.expiresAt != null && value.expiresAt <= snapshot.capturedAt -> "expired"
                deletion != null -> "feedback_deleted"
                dependencyDeleted -> "parent_feedback_deleted"
                value.feedbackVersion.toLong() != token || response != null && response.source != value.source -> "feedback_changed"
                response != null && response.decision != ClaimFeedbackDecision.CONFIRMED -> "feedback_not_accepted"
                dependencyChanged -> "parent_feedback_changed"
                value.category == KnowledgeCategory.INSIGHT && response?.decision != ClaimFeedbackDecision.CONFIRMED -> "insight_unconfirmed"
                !reviewCurrent(value) -> "review_changed"
                options.scope.topics != null && value.topics.none { it in options.scope.topics } -> "topic"
                allSources.any { (id, revision) -> records[id]?.revision != revision } -> "evidence_not_current"
                value.metricEvidence.any { !MetricExportScope.contains(options.scope, it.scope) } -> "metric_scope_not_contained"
                allSources.any { (id, _) -> id !in included } -> "evidence_out_of_scope"
                else -> null
            }
            if (reason != null) {
                omitted[reason] = omitted.getOrDefault(reason, 0) + 1
                null
            } else {
                KnowledgeValidation.validateEvidence(value, allSources.map { (id, _) -> records.getValue(id) }.distinctBy { it.id })
                value.metricEvidence.forEach { metric ->
                    if (validatedMetrics.add(metric.id)) RecordMetricProtocol.validateSnapshot(metric, stamp, currentRecords)
                }
                Entry(value, response?.decision)
            }
        }
        return Selection(selected, snapshot.knowledgeHeads.size,
            snapshot.knowledgeRevisions.size - snapshot.knowledgeHeads.size, omitted)
    }
}
