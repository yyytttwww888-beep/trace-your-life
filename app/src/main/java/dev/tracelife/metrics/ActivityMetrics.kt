package dev.tracelife.metrics

import dev.tracelife.dashboard.DashboardPoint
import dev.tracelife.model.Occurrence
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceDescriptor
import dev.tracelife.storage.SourceStamp
import dev.tracelife.templates.ActivityLogTemplate
import java.time.LocalDate
import java.util.Collections

enum class ActivityDomain(val label: String) { WORK("工作"), LEARNING("学习") }
enum class ActivityMeasure(val label: String, val unit: String) { MINUTES("已记录投入", "分钟"), PRACTICES("已记录实践", "次"), OUTCOMES("成果原文", "") }

data class ActivityMetricEntry(
    val recordId: String,
    val revision: Int,
    val occurrence: Occurrence,
    val domain: ActivityDomain?,
    val minutes: Int?,
    val practices: Int?,
    val hasOutcome: Boolean,
    val issues: List<String>,
) {
    val exactDate: LocalDate? get() = occurrence.startDate?.takeIf { it == occurrence.endDate }
    fun value(measure: ActivityMeasure): Int? = when (measure) {
        ActivityMeasure.MINUTES -> minutes
        ActivityMeasure.PRACTICES -> practices
        ActivityMeasure.OUTCOMES -> null
    }
}

data class ActivityMetricPoint(
    val from: LocalDate, val through: LocalDate, val days: Int, val inProgress: Boolean,
    val minutes: Int?, val practices: Int?, val minutesRecordedDays: Int, val practicesRecordedDays: Int,
) {
    fun value(measure: ActivityMeasure): Int? = when (measure) {
        ActivityMeasure.MINUTES -> minutes
        ActivityMeasure.PRACTICES -> practices
        ActivityMeasure.OUTCOMES -> null
    }
}

data class ActivityMetricSeries(
    val domain: ActivityDomain, val points: List<ActivityMetricPoint>,
    val minutes: Int?, val practices: Int?, val minutesRecordedDays: Int, val practicesRecordedDays: Int,
)

/** Rebuildable explicit self-report values. Null is missing; zero must have been entered. */
class ActivityMetricPeriod internal constructor(
    val scope: AnalysisQuery,
    val series: List<ActivityMetricSeries>,
    internal val entries: List<ActivityMetricEntry>,
) {
    val definitionId: String = "explicit-activity-v1"
    val definitionVersion: Int = 1
    val sourceNature: String = "user_reported"
    val missingRule: String = "missing_is_null_explicit_zero_only_no_activity_inference"
    val deduplicationRule: String = "one_current_revision_per_record_id_user_avoids_duplicate_activity_entries"
    val intervalRule: String = "interval_and_unknown_dates_not_distributed_or_added_to_exact_date_totals"
    val undatedEntries: Int = entries.count { it.occurrence.startDate == null }
    val intervalEntries: Int = entries.count { it.occurrence.startDate != null && it.exactDate == null }
    val entriesNeedingCorrection: Int = entries.count { it.issues.isNotEmpty() }
}

internal class ActivityMetrics(private val repository: LifeRepository) {
    fun calculate(source: SourceStamp, scope: AnalysisQuery, sources: List<SourceDescriptor>, points: List<DashboardPoint>): ActivityMetricPeriod {
        val index = sources.associateBy { it.id }
        val entries = sources.chunked(100).flatMap { page ->
            repository.activityMetricInputs(source, scope, page.map { it.id }).map { input ->
                val record = requireNotNull(index[input.recordId])
                check(record.revision == input.revision)
                val issues = mutableListOf<String>()
                val domain = if (input.supported) ActivityDomain.entries.singleOrNull { it.label == input.domain.trim() } else null
                if (!input.supported) issues += "模板问题已改变，未按旧口径统计"
                else if (domain == null) issues += "领域须填写工作或学习"
                fun number(text: String, max: Int, label: String): Int? {
                    if (domain == null) return null
                    return try { ActivityLogTemplate.number(text, max, label) } catch (failure: IllegalArgumentException) {
                        issues += requireNotNull(failure.message); null
                    }
                }
                val minutes = number(input.minutes, 1440, "投入分钟")
                val practices = number(input.practices, 10000, "实践次数")
                ActivityMetricEntry(input.recordId, input.revision, record.occurrence, domain,
                    minutes, practices, input.hasOutcome && domain != null, freeze(issues))
            }
        }.sortedWith(compareByDescending<ActivityMetricEntry> { it.occurrence.startDate }.thenBy { it.recordId })
        val series = ActivityDomain.entries.map { domain ->
            val dated = entries.filter { it.domain == domain && it.exactDate != null }
            val values = points.map { point ->
                val contributors = dated.filter { requireNotNull(it.exactDate) in point.from..point.through }
                ActivityMetricPoint(point.from, point.through, point.days, point.inProgress,
                    nullableSum(contributors.mapNotNull { it.minutes }), nullableSum(contributors.mapNotNull { it.practices }),
                    contributors.filter { it.minutes != null }.map { it.exactDate }.distinct().size,
                    contributors.filter { it.practices != null }.map { it.exactDate }.distinct().size)
            }
            ActivityMetricSeries(domain, freeze(values), nullableSum(values.mapNotNull { it.minutes }),
                nullableSum(values.mapNotNull { it.practices }), values.sumOf { it.minutesRecordedDays }, values.sumOf { it.practicesRecordedDays })
        }
        return ActivityMetricPeriod(scope, freeze(series), freeze(entries))
    }

    private fun nullableSum(values: List<Int>): Int? = values.takeIf { it.isNotEmpty() }?.sum()
    private fun <T> freeze(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
}
