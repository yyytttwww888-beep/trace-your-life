package dev.tracelife.dashboard

import dev.tracelife.metrics.RecordCoverageValues
import dev.tracelife.metrics.RecordMetricReceipt
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.SourceDescriptor
import dev.tracelife.storage.SourceStamp
import java.time.Instant
import java.time.LocalDate

enum class DashboardWindow(val days: Int) { DAYS_7(7), DAYS_30(30), DAYS_90(90) }
enum class DashboardMetric { SLEEP_DURATION, EXERCISE_MINUTES, DIET_MEALS, RECORD_COVERAGE }
enum class DashboardCardState { AVAILABLE, SOURCE_NOT_CONNECTED, STRUCTURED_INPUT_UNAVAILABLE }
enum class DashboardPeriodSelection { CURRENT, PREVIOUS }
enum class DashboardSourceCategory { ALL, EXACT_DATE, INTERVAL, OVERLAPPING_INTERVAL, UNKNOWN_TIME }
enum class DashboardActionSection { ALL, DUE, UPCOMING, STOPPED }
enum class DashboardComparisonState { COMPLETE_RECORDED_WINDOWS, CURRENT_WINDOW_IN_PROGRESS }

data class DashboardRequest(
    val window: DashboardWindow = DashboardWindow.DAYS_7,
    val zoneId: String,
    val through: LocalDate? = null,
    val cards: List<DashboardMetric> = listOf(DashboardMetric.SLEEP_DURATION,
        DashboardMetric.EXERCISE_MINUTES, DashboardMetric.DIET_MEALS),
    val includeArchived: Boolean = false,
)

/** Counts describe saved exact-date records, never activity or health. */
data class DashboardPoint(
    val from: LocalDate,
    val through: LocalDate,
    val days: Int,
    val exactDateRecords: Int,
    val recordedDays: Int,
    val inProgress: Boolean,
)

/** The complete local source manifest is available only through bounded source pages. */
class DashboardMetricSummary internal constructor(receipt: RecordMetricReceipt) {
    val id: String = receipt.id
    val definitionId: String = "record-coverage"
    val definitionVersion: Int = 1
    val source: SourceStamp = receipt.source
    val scope: AnalysisQuery = receipt.scope
    val values: RecordCoverageValues = receipt.values
    val recordUnit: String = "record"
    val coverageUnit: String = "day"
    val missingRule: String = "zero_means_no_exact_date_record_not_zero_activity"
    val deduplicationRule: String = "one_current_revision_per_record_id"
    val intervalRule: String = "intervals_and_unknown_dates_are_not_distributed_into_daily_counts"
    val timeRule: String = "original_date_labels_no_timezone_conversion"
}

class DashboardPeriod internal constructor(
    internal val receipt: RecordMetricReceipt,
    val points: List<DashboardPoint>,
    internal val sourceIndex: List<SourceDescriptor>,
) {
    val metric: DashboardMetricSummary = DashboardMetricSummary(receipt)
}

data class DashboardCard(
    val metric: DashboardMetric,
    val state: DashboardCardState,
    val current: DashboardPeriod?,
)

data class DashboardComparison(
    val state: DashboardComparisonState,
    val exactDateRecordsDelta: Int?,
    val recordedDaysDelta: Int?,
)

data class DashboardPage<T>(val items: List<T>, val total: Int, val nextOffset: Int?)
data class DashboardInsight(val id: String, val revision: Int, val text: String, val metricId: String)
internal data class DashboardInsightRef(val id: String, val revision: Int, val metricId: String)

/** Rebuildable app view, never a model tool or a persistent source of truth.
 * Current/previous always describe global record coverage; health cards remain independent. */
class DashboardSnapshot internal constructor(
    val id: String,
    val request: DashboardRequest,
    val source: SourceStamp,
    val analysisGeneration: Long,
    val evaluatedAt: Instant,
    val validUntil: Instant,
    val current: DashboardPeriod,
    val previous: DashboardPeriod,
    val comparison: DashboardComparison,
    val cards: List<DashboardCard>,
    internal val currentInsightIndex: List<DashboardInsightRef>,
    internal val previousInsightIndex: List<DashboardInsightRef>,
    internal val actionIndex: List<DashboardActionItem>,
    internal val markerIndex: List<DashboardActionMarker>,
) {
    val insightCount: Int get() = currentInsightIndex.size
    val previousInsightCount: Int get() = previousInsightIndex.size
    val actionCount: Int get() = actionIndex.size
    val markerCount: Int get() = markerIndex.size
}
