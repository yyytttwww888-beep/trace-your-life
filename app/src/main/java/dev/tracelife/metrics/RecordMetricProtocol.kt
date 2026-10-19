package dev.tracelife.metrics

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.model.RecordRevision
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceDescriptor
import dev.tracelife.storage.SourceStamp
import dev.tracelife.storage.SourceTimeClass
import dev.tracelife.transfer.StaleSnapshotException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Collections

data class RecordMetricSource(val recordId: String, val revision: Int)
data class RecordCoverageValues(
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

/** A calculation proof, never a text fragment. The enclosing call records the calculation time. */
data class RecordMetricReceipt(
    val id: String,
    val source: SourceStamp,
    val scope: AnalysisQuery,
    val values: RecordCoverageValues,
    val sources: List<RecordMetricSource>,
)

/** Shared, strict record-coverage v1 arithmetic and proof format for runtime, restore and export. */
object RecordMetricProtocol {
    const val MAX_SOURCES = 10_000

    fun calculate(repository: LifeRepository, source: SourceStamp, scope: AnalysisQuery): RecordMetricReceipt {
        validateScope(scope)
        val fixedScope = scope.copy(recordIds = scope.recordIds?.let { Collections.unmodifiableSet(it.toSortedSet()) })
        val accumulator = RecordCoverageAccumulator(fixedScope)
        val manifest = mutableListOf<RecordMetricSource>()
        var offset = 0
        do {
            val page = repository.analysisSourcePage(source, fixedScope, offset, 100)
            require(page.total <= MAX_SOURCES) { "指标来源清单超过上限，请建立更小范围的任务" }
            page.items.forEach { item ->
                accumulator.add(item)
                manifest += RecordMetricSource(item.id, item.revision)
            }
            offset = page.nextOffset ?: break
        } while (true)
        if (repository.analysisSourceStamp() != source) throw StaleSnapshotException("指标计算期间来源已变化")
        val value = RecordMetricReceipt("", source, fixedScope, accumulator.values(),
            Collections.unmodifiableList(manifest.sortedBy { it.recordId }))
        return value.copy(id = identity(value)).also { validate(it) }
    }

    fun validateCurrent(repository: LifeRepository, value: RecordMetricReceipt) {
        validate(value)
        require(calculate(repository, value.source, value.scope) == value) { "指标与当前完整查询的计算不一致" }
    }

    /** Exact-current snapshots can additionally prove completeness against their whole record catalog. */
    fun validateSnapshot(value: RecordMetricReceipt, snapshotSource: SourceStamp, records: List<RecordRevision>) {
        validate(value, records = records)
        if (value.source != snapshotSource) return
        val scope = value.scope
        val expected = records.groupBy { it.id }.values.map { revisions -> revisions.maxBy { it.revision } }
            .filter { record ->
                val first = record.draft.occurrence.startDate
                (scope.includeArchived || !record.archived) && (scope.recordIds == null || record.id in scope.recordIds) &&
                    (first == null || first <= scope.through && record.draft.occurrence.endDate!! >= scope.from)
            }.sortedBy { it.id }.map { RecordMetricSource(it.id, it.revision) }
        require(value.sources == expected) { "当前快照的指标来源清单没有覆盖完整查询" }
    }

    /** Historical validation verifies recorded members, not authenticity of a past full database. */
    fun validate(value: RecordMetricReceipt, scope: AnalysisQuery? = null, source: SourceStamp? = null,
        records: List<RecordRevision>? = null) {
        validateScope(value.scope)
        AnalysisRunCodec.validId(value.source.storeId)
        require(value.source.generation >= 0 && (scope == null || value.scope == scope) && (source == null || value.source == source))
        require(value.sources.size <= MAX_SOURCES)
        require(value.sources.map { it.recordId } == value.sources.map { it.recordId }.distinct().sorted()) { "指标来源必须按 ID 排序且不重复" }
        value.sources.forEach { ref -> AnalysisRunCodec.validId(ref.recordId); require(ref.revision > 0) }
        value.scope.recordIds?.let { ids -> require(value.sources.all { it.recordId in ids }) }
        val values = value.values
        val days = days(value.scope)
        require(values.totalRecords == value.sources.size && values.daysInScope == days)
        val counts = listOf(values.totalRecords, values.exactDateRecords, values.intervalRecords,
            values.overlappingRecords, values.unknownTimeRecords, values.recordedDays)
        require(counts.all { it in 0..MAX_SOURCES })
        require(values.exactDateRecords.toLong() + values.intervalRecords + values.unknownTimeRecords == values.totalRecords.toLong())
        require(values.overlappingRecords <= values.intervalRecords)
        require(values.daily.size == days && values.daily.map { it.date } == (0 until days).map { value.scope.from.plusDays(it.toLong()) })
        require(values.daily.all { it.records in 0..MAX_SOURCES } && values.daily.sumOf { it.records.toLong() } == values.exactDateRecords.toLong())
        require(values.recordedDays == values.daily.count { it.records > 0 })
        require(values.missingExactIds == value.scope.recordIds.orEmpty() - value.sources.map { it.recordId }.toSet())
        require(value.id == identity(value)) { "指标身份与口径、范围、数值或来源清单不一致" }
        records?.let { originals ->
            val byRevision = originals.associateBy { it.id to it.revision }
            val accumulator = RecordCoverageAccumulator(value.scope)
            value.sources.forEach { ref ->
                val record = requireNotNull(byRevision[ref.recordId to ref.revision]) { "指标缺少原始来源修订" }
                accumulator.add(SourceDescriptor(record.id, record.revision, record.draft.occurrence,
                    record.archived, record.draft.topics, SourceTimeClass.UNKNOWN))
            }
            require(accumulator.values() == values) { "指标数值与来源修订重新计算的结果不一致" }
        }
    }

    fun map(value: RecordMetricReceipt): Map<String, Any?> = contentMap(value) + mapOf("id" to value.id)

    /** Only bounded numeric results and the manifest digest enter model input, never source bodies or all IDs. */
    fun modelMap(value: RecordMetricReceipt): Map<String, Any?> = (map(value) - "sources") + mapOf(
        "sourceType" to "PROGRAM_CALCULATED_METRIC",
        "sourceCount" to value.sources.size,
        "sourceManifestSha256" to AnalysisRunCodec.sha256(AnalysisRunCodec.json(sourceMap(value))),
        "units" to mapOf("records" to "record", "coverage" to "day"),
        "deduplicationRule" to "one_current_revision_per_record_id",
        "timeRule" to "original_date_labels_no_timezone_conversion",
        "intervalRule" to "intervals_and_unknown_dates_are_not_distributed_into_daily_counts",
        "missingRule" to "zero_means_no_exact_date_record_not_zero_activity",
        "interpretationBoundary" to "record_counts_are_not_growth_health_or_activity_metrics",
    )

    fun parse(raw: Any?): RecordMetricReceipt {
        val root = raw.obj("id", "definitionId", "definitionVersion", "source", "scope", "values", "sources")
        require(root.string("definitionId") == "record-coverage" && root.int("definitionVersion") == 1)
        val source = root["source"].obj("storeId", "generation")
        val scope = root["scope"].obj("from", "through", "zoneId", "includeArchived", "recordIds")
        val values = root["values"].obj("totalRecords", "exactDateRecords", "intervalRecords", "overlappingRecords",
            "unknownTimeRecords", "missingExactIds", "daysInScope", "recordedDays", "daily")
        return RecordMetricReceipt(root.string("id"), SourceStamp(source.string("storeId"), source.long("generation")),
            AnalysisQuery(LocalDate.parse(scope.string("from")), LocalDate.parse(scope.string("through")), scope.string("zoneId"),
                scope["includeArchived"] as? Boolean ?: error("指标归档范围无效"), scope["recordIds"]?.let { scope.strings("recordIds").uniqueSet() }),
            RecordCoverageValues(values.int("totalRecords"), values.int("exactDateRecords"), values.int("intervalRecords"),
                values.int("overlappingRecords"), values.int("unknownTimeRecords"), values.strings("missingExactIds").uniqueSet(),
                values.int("daysInScope"), values.int("recordedDays"), values.list("daily").map { rawDay ->
                    val day = rawDay.obj("date", "records")
                    DailyRecordCount(LocalDate.parse(day.string("date")), day.int("records"))
                }), root.list("sources").map { rawSource ->
                    val ref = rawSource.obj("recordId", "revision")
                    RecordMetricSource(ref.string("recordId"), ref.int("revision"))
                }).also { validate(it) }
    }

    private fun identity(value: RecordMetricReceipt): String = "metric-v1-" + AnalysisRunCodec.sha256(
        AnalysisRunCodec.json(mapOf("protocol" to "trace-life-metric-receipt-v1", "content" to contentMap(value))))

    private fun contentMap(value: RecordMetricReceipt): Map<String, Any?> = mapOf(
        "definitionId" to "record-coverage", "definitionVersion" to 1,
        "source" to mapOf("storeId" to value.source.storeId, "generation" to value.source.generation),
        "scope" to AnalysisRunCodec.scopeMap(value.scope), "values" to value.values.let { v -> mapOf(
            "totalRecords" to v.totalRecords, "exactDateRecords" to v.exactDateRecords, "intervalRecords" to v.intervalRecords,
            "overlappingRecords" to v.overlappingRecords, "unknownTimeRecords" to v.unknownTimeRecords,
            "missingExactIds" to v.missingExactIds.sorted(), "daysInScope" to v.daysInScope, "recordedDays" to v.recordedDays,
            "daily" to v.daily.map { mapOf("date" to it.date.toString(), "records" to it.records) }) },
        "sources" to sourceMap(value),
    )
    private fun sourceMap(value: RecordMetricReceipt) = value.sources.map { mapOf("recordId" to it.recordId, "revision" to it.revision) }

    internal fun days(scope: AnalysisQuery): Int = (ChronoUnit.DAYS.between(scope.from, scope.through) + 1).also {
        require(it in 1..366) { "记录趋势每次支持 1 至 366 天，请按期间分段" }
    }.toInt()

    internal fun validateScope(scope: AnalysisQuery) {
        require(scope.from.year in 1..9999 && scope.through.year in 1..9999)
        days(scope); ZoneId.of(scope.zoneId)
        scope.recordIds?.let { ids -> require(ids.size in 1..100); ids.forEach(AnalysisRunCodec::validId) }
    }

    private fun Any?.obj(vararg keys: String): Map<*, *> = (this as? Map<*, *> ?: error("指标对象无效")).also {
        require(it.keys == keys.toSet()) { "指标字段不完整或未知" }
    }
    private fun Map<*, *>.string(key: String) = this[key] as? String ?: error("指标文字无效")
    private fun Map<*, *>.long(key: String): Long = when (val value = this[key]) {
        is Long -> value
        is Int -> value.toLong()
        else -> error("指标整数无效")
    }
    private fun Map<*, *>.int(key: String) = long(key).also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }.toInt()
    private fun Map<*, *>.list(key: String) = this[key] as? List<*> ?: error("指标列表无效")
    private fun Map<*, *>.strings(key: String) = list(key).map { it as? String ?: error("指标文字列表无效") }
    private fun List<String>.uniqueSet(): Set<String> = toSet().also { require(it.size == size) }
}

/** One arithmetic implementation shared with ordinary app/dashboard metrics. */
internal class RecordCoverageAccumulator(private val scope: AnalysisQuery) {
    private val counts = IntArray(RecordMetricProtocol.days(scope))
    private val missing = scope.recordIds?.toMutableSet() ?: mutableSetOf()
    private var total = 0
    private var exact = 0
    private var intervals = 0
    private var overlap = 0
    private var unknown = 0

    fun add(record: SourceDescriptor) {
        require(scope.includeArchived || !record.archived)
        require(scope.recordIds == null || record.id in scope.recordIds)
        val first = record.occurrence.startDate
        val last = record.occurrence.endDate
        require(first == null || first <= scope.through && last!! >= scope.from) { "指标来源不属于范围" }
        missing.remove(record.id)
        total++
        when {
            first == null -> unknown++
            first == last -> { counts[ChronoUnit.DAYS.between(scope.from, first).toInt()]++; exact++ }
            else -> { intervals++; if (first < scope.from || last!! > scope.through) overlap++ }
        }
    }

    fun values() = RecordCoverageValues(total, exact, intervals, overlap, unknown,
        Collections.unmodifiableSet(missing.toSortedSet()), counts.size, counts.count { it > 0 },
        Collections.unmodifiableList(counts.mapIndexed { index, count -> DailyRecordCount(scope.from.plusDays(index.toLong()), count) }))
}
