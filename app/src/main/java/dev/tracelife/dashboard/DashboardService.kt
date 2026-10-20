package dev.tracelife.dashboard

import dev.tracelife.actions.ActionStatus
import dev.tracelife.actions.StoredActionRevision
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeFilter
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.metrics.RecordMetricProtocol
import dev.tracelife.metrics.RecordMetricReceipt
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceDescriptor
import dev.tracelife.storage.SourceField
import dev.tracelife.storage.SourceFragment
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Collections
import java.util.UUID

/** Trusted, bounded application reads. No model calls or source-body scans occur when opening. */
class DashboardService(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    fun open(request: DashboardRequest): DashboardSnapshot {
        val input = request.copy(cards = freeze(request.cards))
        require(input.cards.size in 1..3 && input.cards.distinct().size == input.cards.size) {
            "看板须选择 1 至 3 个不重复的指标"
        }
        val zone = ZoneId.of(input.zoneId)
        val at = clock.instant()
        val today = at.atZone(zone).toLocalDate()
        val through = input.through ?: today
        require(through.year in 1..9999 && through <= today) { "看板截止日必须是已到达的有效日期" }
        val from = through.minusDays(input.window.days.toLong() - 1)
        val previousFrom = from.minusDays(input.window.days.toLong())
        require(previousFrom.year in 1..9999) { "看板前期比较超出支持日期范围" }
        val fixed = input.copy(through = through)
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val scope = AnalysisQuery(from, through, fixed.zoneId, fixed.includeArchived)
        val previousScope = scope.copy(from = previousFrom, through = from.minusDays(1))
        val current = period(source, scope, fixed.window, today)
        val previous = period(source, previousScope, fixed.window, today)
        val complete = through < today
        val comparison = DashboardComparison(
            if (complete) DashboardComparisonState.COMPLETE_RECORDED_WINDOWS else DashboardComparisonState.CURRENT_WINDOW_IN_PROGRESS,
            if (complete) current.metric.values.exactDateRecords - previous.metric.values.exactDateRecords else null,
            if (complete) current.metric.values.recordedDays - previous.metric.values.recordedDays else null,
        )
        var validUntil = today.plusDays(1).atStartOfDay(zone).toInstant()
        val currentInsights = mutableListOf<DashboardInsightRef>()
        val previousInsights = mutableListOf<DashboardInsightRef>()
        val knowledge = KnowledgeLibrary(repository, Clock.fixed(at, clock.zone))
        var visited = 0
        knowledge.scanCurrent(KnowledgeFilter(from = previousScope.from, through = through,
            category = KnowledgeCategory.INSIGHT)) { view ->
            require(++visited <= MAX_KNOWLEDGE) { "看板洞察目录超过上限" }
            val value = view.value
            val metric = value.metricEvidence.singleOrNull() ?: return@scanCurrent
            val index = when (metric) {
                current.receipt -> currentInsights
                previous.receipt -> previousInsights
                else -> return@scanCurrent
            }
            // Matching a metric receipt also matches source, whole range, definition and arithmetic.
            if (value.scope != metric.scope || value.evidence.isNotEmpty() || value.reductionProof != null) return@scanCurrent
            index += DashboardInsightRef(value.id, value.revision, metric.id)
            value.expiresAt?.let { if (it < validUntil) validUntil = it }
        }
        val actions = DashboardActionCollector(repository, clock).collect(source, generation, at, scope)
        actions.validUntil?.let { if (it < validUntil) validUntil = it }
        val cards = fixed.cards.map { metric ->
            when (metric) {
                DashboardMetric.RECORD_COVERAGE -> DashboardCard(metric, DashboardCardState.AVAILABLE, current)
                DashboardMetric.DIET_MEALS -> DashboardCard(metric, DashboardCardState.STRUCTURED_INPUT_UNAVAILABLE, null)
                else -> DashboardCard(metric, DashboardCardState.SOURCE_NOT_CONNECTED, null)
            }
        }
        return DashboardSnapshot(UUID.randomUUID().toString(), fixed, source, generation, at, validUntil,
            current, previous, comparison, freeze(cards), freeze(currentInsights), freeze(previousInsights),
            freeze(actions.items), freeze(actions.markers),
            dev.tracelife.metrics.ActivityMetrics(repository).calculate(source, scope, current.sourceIndex, current.points),
            dev.tracelife.metrics.ActivityMetrics(repository).calculate(source, previousScope, previous.sourceIndex, previous.points)).also(::assertCurrent)
    }

    fun activitySources(snapshot: DashboardSnapshot, period: DashboardPeriodSelection = DashboardPeriodSelection.CURRENT,
        domain: dev.tracelife.metrics.ActivityDomain? = null, measure: dev.tracelife.metrics.ActivityMeasure? = null,
        pointIndex: Int? = null, correctionsOnly: Boolean = false, offset: Int = 0, limit: Int = 25): DashboardPage<dev.tracelife.metrics.ActivityMetricEntry> = current(snapshot) {
        val selected = if (period == DashboardPeriodSelection.CURRENT) snapshot.activityCurrent else snapshot.activityPrevious
        val point = pointIndex?.let { index ->
            require(index in selected.series.first().points.indices) { "活动图表点不在看板中" }
            selected.series.first().points[index]
        }
        page(selected.entries.filter { entry ->
            (domain == null || entry.domain == domain) && (!correctionsOnly || entry.issues.isNotEmpty()) &&
                (point == null || entry.exactDate?.let { it in point.from..point.through } == true) &&
                when (measure) {
                    dev.tracelife.metrics.ActivityMeasure.OUTCOMES -> entry.hasOutcome
                    null -> true
                    else -> entry.value(measure) != null
                }
        }, offset, limit)
    }

    /** Clock fences matter even when no database writes occurred. */
    fun assertCurrent(snapshot: DashboardSnapshot) {
        val now = clock.instant()
        if (repository.analysisSourceStamp() != snapshot.source ||
            repository.currentAnalysisGeneration() != snapshot.analysisGeneration ||
            now < snapshot.evaluatedAt || !now.isBefore(snapshot.validUntil))
            throw StaleSnapshotException("看板的资料、行动、说明或日期已变化，请重新读取")
    }

    fun sources(snapshot: DashboardSnapshot, period: DashboardPeriodSelection = DashboardPeriodSelection.CURRENT,
        category: DashboardSourceCategory = DashboardSourceCategory.ALL, pointIndex: Int? = null,
        offset: Int = 0, limit: Int = 25): DashboardPage<SourceDescriptor> = current(snapshot) {
        val selected = period(snapshot, period)
        val point = pointIndex?.let {
            require(it in selected.points.indices) { "图表点不在看板中" }
            require(category == DashboardSourceCategory.ALL || category == DashboardSourceCategory.EXACT_DATE) {
                "图表点只包含精确日期的贡献记录"
            }
            selected.points[it]
        }
        page(selected.sourceIndex.filter { record ->
            val first = record.occurrence.startDate
            val last = record.occurrence.endDate
            val matches = when (category) {
                DashboardSourceCategory.ALL -> true
                DashboardSourceCategory.EXACT_DATE -> first != null && first == last
                DashboardSourceCategory.INTERVAL -> first != null && first != last
                DashboardSourceCategory.OVERLAPPING_INTERVAL -> first != null && first != last &&
                    (first < selected.metric.scope.from || requireNotNull(last) > selected.metric.scope.through)
                DashboardSourceCategory.UNKNOWN_TIME -> first == null
            }
            matches && (point == null || first != null && first == last && first >= point.from && first <= point.through)
        }, offset, limit)
    }

    fun sourceFields(snapshot: DashboardSnapshot, recordId: String,
        period: DashboardPeriodSelection = DashboardPeriodSelection.CURRENT): List<SourceField> = current(snapshot) {
        val selected = registeredSource(snapshot, period, recordId)
        freeze(repository.analysisSourceFields(snapshot.source, selected.metric.scope, recordId))
    }

    fun sourceText(snapshot: DashboardSnapshot, recordId: String, field: String, start: Int = 0,
        maxCodePoints: Int = 1_024, period: DashboardPeriodSelection = DashboardPeriodSelection.CURRENT): SourceFragment = current(snapshot) {
        val selected = registeredSource(snapshot, period, recordId)
        repository.analysisSourceFragment(snapshot.source, selected.metric.scope, recordId, field, start, maxCodePoints).also {
            if (selected.sourceIndex.single { item -> item.id == recordId }.revision != it.revision)
                throw StaleSnapshotException("看板原文修订已经变化")
        }
    }

    fun insights(snapshot: DashboardSnapshot, period: DashboardPeriodSelection = DashboardPeriodSelection.CURRENT,
        offset: Int = 0, limit: Int = 25): DashboardPage<DashboardInsight> = current(snapshot) {
        val selected = period(snapshot, period)
        val refs = if (period == DashboardPeriodSelection.CURRENT) snapshot.currentInsightIndex else snapshot.previousInsightIndex
        val chosen = page(refs, offset, limit)
        val library = KnowledgeLibrary(repository, Clock.fixed(snapshot.evaluatedAt, clock.zone))
        val items = chosen.items.map { ref ->
            val view = library.get(ref.id, ref.revision)
            if (view == null || !view.isCurrentRevision || view.evidenceState != KnowledgeEvidenceState.CURRENT ||
                view.value.metricEvidence.singleOrNull() != selected.receipt)
                throw StaleSnapshotException("看板指标说明已经变化")
            DashboardInsight(ref.id, ref.revision, view.value.claim.text, ref.metricId)
        }
        DashboardPage(freeze(items), chosen.total, chosen.nextOffset)
    }

    fun actions(snapshot: DashboardSnapshot, section: DashboardActionSection = DashboardActionSection.ALL,
        offset: Int = 0, limit: Int = 25): DashboardPage<DashboardActionItem> = current(snapshot) {
        page(snapshot.actionIndex.filter { item -> when (section) {
            DashboardActionSection.ALL -> true
            DashboardActionSection.DUE -> item.status == ActionStatus.OPEN && item.due
            DashboardActionSection.UPCOMING -> item.status == ActionStatus.OPEN && !item.due
            DashboardActionSection.STOPPED -> item.status == ActionStatus.STOPPED
        } }, offset, limit)
    }

    fun markers(snapshot: DashboardSnapshot, offset: Int = 0, limit: Int = 25): DashboardPage<DashboardActionMarker> =
        current(snapshot) { page(snapshot.markerIndex, offset, limit) }

    fun action(snapshot: DashboardSnapshot, id: String, revision: Int? = null): StoredActionRevision = current(snapshot) {
        val item = requireNotNull(snapshot.actionIndex.singleOrNull { it.id == id }) { "行动不在看板中" }
        val selected = revision ?: item.revision
        require(selected == item.revision || selected == item.latestReview?.revision ||
            snapshot.markerIndex.any { it.actionId == id && it.revision == selected }) { "行动修订未在看板登记" }
        repository.actionRevision(id, selected) ?: throw StaleSnapshotException("看板行动已经变化")
    }

    private fun period(source: SourceStamp, scope: AnalysisQuery, window: DashboardWindow, today: LocalDate): DashboardPeriod {
        val receipt = RecordMetricProtocol.calculate(repository, source, scope)
        val records = mutableListOf<SourceDescriptor>()
        var offset = 0
        do {
            val page = repository.analysisSourcePage(source, scope, offset, 100)
            require(page.total <= RecordMetricProtocol.MAX_SOURCES) { "看板来源超过上限" }
            records += page.items.map { it.copy(topics = Collections.unmodifiableSet(it.topics.toSet())) }
            offset = page.nextOffset ?: break
        } while (true)
        val points = mutableListOf<DashboardPoint>()
        var from = scope.from
        while (from <= scope.through) {
            val through = if (window == DashboardWindow.DAYS_90)
                minOf(from.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), scope.through) else from
            val daily = receipt.values.daily.filter { it.date >= from && it.date <= through }
            points += DashboardPoint(from, through, (ChronoUnit.DAYS.between(from, through) + 1).toInt(),
                daily.sumOf { it.records }, daily.count { it.records > 0 }, today in from..through)
            from = through.plusDays(1)
        }
        return DashboardPeriod(receipt, freeze(points), freeze(records))
    }

    private fun registeredSource(snapshot: DashboardSnapshot, selection: DashboardPeriodSelection, id: String): DashboardPeriod =
        period(snapshot, selection).also { require(it.sourceIndex.any { item -> item.id == id }) { "记录不在看板范围中" } }

    private fun period(snapshot: DashboardSnapshot, selection: DashboardPeriodSelection) =
        if (selection == DashboardPeriodSelection.CURRENT) snapshot.current else snapshot.previous

    private fun <T> current(snapshot: DashboardSnapshot, block: () -> T): T {
        assertCurrent(snapshot)
        return block().also { assertCurrent(snapshot) }
    }

    private fun <T> page(values: List<T>, offset: Int, limit: Int): DashboardPage<T> {
        require(offset >= 0 && limit in 1..100) { "看板分页范围无效" }
        val end = minOf(values.size.toLong(), offset.toLong() + limit).toInt()
        val items = if (offset >= values.size) emptyList() else values.subList(offset, end)
        return DashboardPage(freeze(items), values.size, end.takeIf { offset < values.size && it < values.size })
    }

    private fun <T> freeze(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

    companion object { private const val MAX_KNOWLEDGE = 10_000 }
}
