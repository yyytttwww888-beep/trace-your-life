package dev.tracelife.metrics

import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourcePage
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Collections

data class DailyRecordCount(val date: LocalDate, val records: Int)

/** Zero means no exact-date record, never zero sleep, activity, learning or growth. */
data class RecordMetricsReport(
    val definitionId: String = "record-coverage",
    val definitionVersion: Int = 1,
    val source: SourceStamp,
    val scope: AnalysisQuery,
    val calculatedAt: Instant,
    val totalRecords: Int,
    val exactDateRecords: Int,
    val intervalRecords: Int,
    val overlappingRecords: Int,
    val unknownTimeRecords: Int,
    val missingExactIds: Set<String>,
    val daysInScope: Int,
    val recordedDays: Int,
    val daily: List<DailyRecordCount>,
)

/** Full-scope metadata enumeration, independent of AI output or knowledge publication counts. */
class RecordMetrics(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    fun calculate(scope: AnalysisQuery): RecordMetricsReport {
        RecordMetricProtocol.validateScope(scope)
        val fixedScope = scope.copy(recordIds = scope.recordIds?.let { Collections.unmodifiableSet(it.toSortedSet()) })
        val stamp = repository.analysisSourceStamp()
        val accumulator = RecordCoverageAccumulator(fixedScope)
        var offset = 0
        do {
            val page = repository.analysisSourcePage(stamp, fixedScope, offset, 100)
            page.items.forEach(accumulator::add)
            offset = page.nextOffset ?: break
        } while (true)
        if (repository.analysisSourceStamp() != stamp) throw StaleSnapshotException("计算期间来源已变化")
        val values = accumulator.values()
        return RecordMetricsReport(source = stamp, scope = fixedScope, calculatedAt = clock.instant(), totalRecords = values.totalRecords,
            exactDateRecords = values.exactDateRecords, intervalRecords = values.intervalRecords, overlappingRecords = values.overlappingRecords,
            unknownTimeRecords = values.unknownTimeRecords, missingExactIds = values.missingExactIds, daysInScope = values.daysInScope,
            recordedDays = values.recordedDays, daily = values.daily)
    }

    /** Page back to the same source snapshot; changed records require recalculation. */
    fun sources(report: RecordMetricsReport, offset: Int = 0, limit: Int = 25): SourcePage {
        require(report.definitionId == "record-coverage" && report.definitionVersion == 1)
        return repository.analysisSourcePage(report.source, report.scope, offset, limit)
    }
}
