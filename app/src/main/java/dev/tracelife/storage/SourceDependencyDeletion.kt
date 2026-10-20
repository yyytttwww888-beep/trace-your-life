package dev.tracelife.storage

import dev.tracelife.analysis.AnalysisRunCodec

/** Actual model input receipts, including failed/in-flight calls and whole metric manifests.
 * Merely sharing a query range requires invalidation, not erasure of unrelated machine text. */
internal object SourceDependencyDeletion {
    fun dependsOnRecords(run: StoredAnalysisRun, recordIds: Set<String>): Boolean {
        val state = AnalysisRunCodec.decode(run.payload)
        return state.calls.any { call -> call.evidence.any { it.recordId in recordIds } ||
            call.metricEvidence.any { metric -> metric.sources.any { it.recordId in recordIds } } }
    }

    fun dependsOnRecords(value: StoredKnowledgeRevision, recordIds: Set<String>): Boolean =
        KnowledgeValidation.allSourceRevisions(value).any { it.first in recordIds }
}
