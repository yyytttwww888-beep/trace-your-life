package dev.tracelife.metrics

import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourcePage
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
        val days = ChronoUnit.DAYS.between(scope.from, scope.through) + 1
        require(days in 1..366) { "记录趋势每次支持 1 至 366 天，请按期间分段" }
        val stamp = repository.analysisSourceStamp()
        val counts = IntArray(days.toInt())
        var offset = 0
        var exact = 0
        var intervals = 0
        var unknown = 0
        var overlap = 0
        var total = 0
        val requested = scope.recordIds?.toMutableSet() ?: mutableSetOf()
        do {
            val page = repository.analysisSourcePage(stamp, scope, offset, 100)
            total = page.total
            unknown = page.unknown
            overlap = page.overlap
            page.items.forEach { record ->
                requested.remove(record.id)
                val first = record.occurrence.startDate
                val last = record.occurrence.endDate
                when {
                    first == null -> Unit
                    first == last -> { counts[ChronoUnit.DAYS.between(scope.from, first).toInt()]++; exact++ }
                    else -> intervals++
                }
            }
            offset = page.nextOffset ?: break
        } while (true)
        if (repository.analysisSourceStamp() != stamp) throw StaleSnapshotException("计算期间来源已变化")
        return RecordMetricsReport(source = stamp, scope = scope, calculatedAt = clock.instant(), totalRecords = total,
            exactDateRecords = exact, intervalRecords = intervals, overlappingRecords = overlap, unknownTimeRecords = unknown,
            missingExactIds = requested, daysInScope = counts.size, recordedDays = counts.count { it > 0 },
            daily = counts.mapIndexed { index, count -> DailyRecordCount(scope.from.plusDays(index.toLong()), count) })
    }

    /** Page back to the same source snapshot; changed records require recalculation. */
    fun sources(report: RecordMetricsReport, offset: Int = 0, limit: Int = 25): SourcePage {
        require(report.definitionId == "record-coverage" && report.definitionVersion == 1)
        return repository.analysisSourcePage(report.source, report.scope, offset, limit)
    }
}
