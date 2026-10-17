package dev.tracelife.metrics

import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourcePage
import dev.tracelife.transfer.StaleSnapshotException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.util.Collections

/** App/report provenance, not an EvidenceReceipt accepted by the model citation protocol. */
class RecordMetricEvidence internal constructor(val id: String, val report: RecordMetricsReport) {
    val recordUnit: String = "record"
    val coverageUnit: String = "day"
    val missingRule: String = "zero_means_no_exact_date_record_not_zero_activity"
    val deduplicationRule: String = "one_current_revision_per_record_id"
    val intervalRule: String = "intervals_and_unknown_dates_are_not_distributed_into_daily_counts"
}

/** Deterministic calculation plus a stable, versioned identity and bounded source drill-down. */
class RecordMetricEvidenceService(
    private val repository: LifeRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun calculate(scope: AnalysisQuery): RecordMetricEvidence =
        issue(RecordMetrics(repository, clock).calculate(freeze(scope)))

    /** Recompute supplied values rather than granting provenance to an arbitrary caller's totals. */
    fun describe(report: RecordMetricsReport): RecordMetricEvidence {
        val candidate = freeze(report)
        requireCurrent(candidate)
        val actual = RecordMetrics(repository, clock).calculate(candidate.scope)
        requireCurrent(candidate)
        require(actual.copy(calculatedAt = candidate.calculatedAt) == candidate) { "指标值与当前口径的程序计算不一致" }
        return issue(candidate)
    }

    fun sources(evidence: RecordMetricEvidence, offset: Int = 0, limit: Int = 25): SourcePage {
        requireCurrent(evidence.report)
        return RecordMetrics(repository, clock).sources(evidence.report, offset, limit)
    }

    private fun requireCurrent(report: RecordMetricsReport) {
        if (repository.analysisSourceStamp() != report.source) throw StaleSnapshotException("指标来源已变化，请重新计算")
    }

    private fun issue(report: RecordMetricsReport): RecordMetricEvidence {
        val frozen = freeze(report)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun field(value: String) {
                val encoded = value.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
            field("trace-life-record-metric-evidence-v1")
            field(frozen.definitionId)
            output.writeInt(frozen.definitionVersion)
            field(frozen.source.storeId)
            output.writeLong(frozen.source.generation)
            field(frozen.scope.from.toString())
            field(frozen.scope.through.toString())
            field(frozen.scope.zoneId)
            output.writeBoolean(frozen.scope.includeArchived)
            output.writeInt(frozen.scope.recordIds?.size ?: -1)
            frozen.scope.recordIds?.sorted()?.forEach(::field)
            listOf(frozen.totalRecords, frozen.exactDateRecords, frozen.intervalRecords,
                frozen.overlappingRecords, frozen.unknownTimeRecords, frozen.daysInScope,
                frozen.recordedDays).forEach(output::writeInt)
            output.writeInt(frozen.missingExactIds.size)
            frozen.missingExactIds.sorted().forEach(::field)
            output.writeInt(frozen.daily.size)
            frozen.daily.forEach { field(it.date.toString()); output.writeInt(it.records) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        requireCurrent(frozen)
        return RecordMetricEvidence("metric-v1-$digest", frozen)
    }

    private fun freeze(scope: AnalysisQuery) = scope.copy(
        recordIds = scope.recordIds?.let { Collections.unmodifiableSet(it.toSortedSet()) },
    )

    private fun freeze(report: RecordMetricsReport) = report.copy(scope = freeze(report.scope),
        missingExactIds = Collections.unmodifiableSet(report.missingExactIds.toSortedSet()),
        daily = Collections.unmodifiableList(report.daily.toList()))
}
