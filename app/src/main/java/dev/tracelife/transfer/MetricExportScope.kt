package dev.tracelife.transfer

import dev.tracelife.storage.AnalysisQuery

/** Query containment matters even when a metric has no contributing source records. */
internal object MetricExportScope {
    fun contains(outer: ExportScope, metric: AnalysisQuery): Boolean =
        (outer.from == null || outer.from <= metric.from) &&
            (outer.through == null || outer.through >= metric.through) &&
            (outer.includeArchived || !metric.includeArchived) && outer.includeUnknown

    fun contains(outer: AnalysisQuery, metric: AnalysisQuery): Boolean =
        outer.from <= metric.from && outer.through >= metric.through && outer.zoneId == metric.zoneId &&
            (outer.includeArchived || !metric.includeArchived) &&
            (outer.recordIds == null || metric.recordIds?.let(outer.recordIds::containsAll) == true)
}
