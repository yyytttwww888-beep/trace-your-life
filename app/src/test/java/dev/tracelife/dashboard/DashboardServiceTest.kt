package dev.tracelife.dashboard

import android.content.Context
import dev.tracelife.analysis.AnalysisFeedback
import dev.tracelife.analysis.AnalysisProvider
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.BatchProposal
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ClaimReview
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.analysis.ModelRequest
import dev.tracelife.analysis.PreparedModelRequest
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.analysis.ReviewCompletion
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.analysis.SemanticReviewProvider
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.metrics.RecordMetrics
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.StoredKnowledgeRevision
import dev.tracelife.transfer.StaleSnapshotException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic records and synthetic model substitutes; this suite never calls a model API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DashboardServiceTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var service: DashboardService
    private val name = "synthetic-dashboard-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val today = LocalDate.parse("2026-10-19")
    private val request = DashboardRequest(zoneId = "Asia/Shanghai")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        service = DashboardService(repository, clock)
    }

    @After fun cleanup() { repository.close(); context.deleteDatabase(name) }

    @Test fun `default health cards stay unavailable while global coverage is explicitly records`() {
        create("sleep-note", today, topics = setOf("sleep"))
        val view = service.open(request)
        assertEquals(listOf(DashboardMetric.SLEEP_DURATION, DashboardMetric.EXERCISE_MINUTES, DashboardMetric.DIET_MEALS), view.cards.map { it.metric })
        assertEquals(listOf(DashboardCardState.SOURCE_NOT_CONNECTED, DashboardCardState.SOURCE_NOT_CONNECTED,
            DashboardCardState.STRUCTURED_INPUT_UNAVAILABLE), view.cards.map { it.state })
        assertTrue(view.cards.all { it.current == null })
        assertEquals("record-coverage", view.current.metric.definitionId)
        assertEquals(1, view.current.metric.values.exactDateRecords)
        assertEquals(0, view.insightCount)
        assertEquals(0, view.actionCount)
        assertEquals(DashboardComparisonState.CURRENT_WINDOW_IN_PROGRESS, view.comparison.state)
        assertNull(view.comparison.exactDateRecordsDelta)
        assertNull(view.comparison.recordedDaysDelta)
    }

    @Test fun `seven and thirty day periods are contiguous and daily with equal prior windows`() {
        for (window in listOf(DashboardWindow.DAYS_7, DashboardWindow.DAYS_30)) {
            val view = service.open(request.copy(window = window))
            assertEquals(window.days, view.current.points.size)
            assertEquals(window.days, view.previous.points.size)
            assertEquals(today, view.current.metric.scope.through)
            assertEquals(today.minusDays(window.days.toLong() - 1), view.current.metric.scope.from)
            assertEquals(view.current.metric.scope.from.minusDays(1), view.previous.metric.scope.through)
            assertEquals(view.current.metric.scope.from.minusDays(window.days.toLong()), view.previous.metric.scope.from)
            assertTrue(view.current.points.all { it.days == 1 && it.from == it.through })
            assertEquals(listOf(today), view.current.points.filter { it.inProgress }.map { it.from })
            assertFalse(view.previous.points.any { it.inProgress })
            assertEquals(view.current.metric.source, view.previous.metric.source)
        }
    }

    @Test fun `ninety day chart uses clipped natural weeks without losing dates or contributions`() {
        create("first", LocalDate.parse("2026-07-22"))
        create("last", today)
        val view = service.open(request.copy(window = DashboardWindow.DAYS_90))
        assertEquals(LocalDate.parse("2026-07-22"), view.current.points.first().from)
        assertEquals(LocalDate.parse("2026-07-26"), view.current.points.first().through)
        assertEquals(5, view.current.points.first().days)
        assertEquals(today, view.current.points.last().from)
        assertEquals(today, view.current.points.last().through)
        assertEquals(1, view.current.points.last().days)
        assertEquals(90, view.current.points.sumOf { it.days })
        assertEquals(2, view.current.points.sumOf { it.exactDateRecords })
        assertEquals(2, view.current.points.sumOf { it.recordedDays })
        assertTrue(view.current.points.zipWithNext().all { (a, b) -> a.through.plusDays(1) == b.from })
        assertEquals(1, view.current.points.count { it.inProgress })
        assertEquals(setOf("first"), service.sources(view, pointIndex = 0).items.map { it.id }.toSet())
        assertEquals(setOf("last"), service.sources(view, pointIndex = view.current.points.lastIndex).items.map { it.id }.toSet())
    }

    @Test fun `historical comparisons expose only exact date record and recorded day differences`() {
        create("prior", today.minusDays(10))
        create("now-a", today.minusDays(3))
        create("now-b", today.minusDays(3))
        create("now-c", today.minusDays(2))
        create("unknown", null)
        create("interval", today.minusDays(5), today.minusDays(1))
        val view = service.open(request.copy(through = today.minusDays(1)))
        assertEquals(DashboardComparisonState.COMPLETE_RECORDED_WINDOWS, view.comparison.state)
        assertEquals(2, view.comparison.exactDateRecordsDelta)
        assertEquals(1, view.comparison.recordedDaysDelta)
        assertEquals(1, view.current.metric.values.unknownTimeRecords)
        assertEquals(1, view.previous.metric.values.unknownTimeRecords)
        assertEquals(1, view.current.metric.values.intervalRecords)
        assertFalse(view.current.points.any { it.inProgress })
        val same = RecordMetrics(repository, clock).calculate(view.current.metric.scope)
        assertEquals(same.daily, view.current.metric.values.daily)
        assertEquals(same.totalRecords, view.current.metric.values.totalRecords)
        assertEquals(same.recordedDays, view.current.metric.values.recordedDays)
    }

    @Test fun `daily source pages exclude intervals and unknowns and category pages retain them`() {
        val first = today.minusDays(6)
        create("exact-a", first); create("exact-b", first)
        create("range", first, first.plusDays(2))
        create("overlap", first.minusDays(1), first)
        create("unknown", null)
        create("outside", first.minusDays(20))
        val view = service.open(request)
        assertEquals(setOf("exact-a", "exact-b"), service.sources(view, pointIndex = 0).items.map { it.id }.toSet())
        assertEquals(2, service.sources(view, category = DashboardSourceCategory.INTERVAL).total)
        assertEquals(listOf("overlap"), service.sources(view, category = DashboardSourceCategory.OVERLAPPING_INTERVAL).items.map { it.id })
        assertEquals(listOf("unknown"), service.sources(view, category = DashboardSourceCategory.UNKNOWN_TIME).items.map { it.id })
        assertEquals(5, service.sources(view).total)
        assertEquals(0, service.sources(view, pointIndex = 1).total)
        assertThrows(IllegalArgumentException::class.java) {
            service.sources(view, category = DashboardSourceCategory.UNKNOWN_TIME, pointIndex = 0)
        }
        assertThrows(IllegalArgumentException::class.java) { service.sources(view, pointIndex = 7) }
    }

    @Test fun `source navigation is bounded registered and invalidated after source modification`() {
        repeat(105) { create("record-$it", today) }
        create("outside", today.minusDays(100))
        val view = service.open(request)
        val first = service.sources(view, pointIndex = 6, limit = 100)
        val last = service.sources(view, pointIndex = 6, offset = first.nextOffset!!, limit = 100)
        assertEquals(105, first.total)
        assertEquals(105, (first.items + last.items).map { it.id }.distinct().size)
        assertNull(last.nextOffset)
        assertTrue(service.sources(view, offset = Int.MAX_VALUE).items.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { service.sources(view, limit = 101) }
        assertThrows(IllegalArgumentException::class.java) { service.sources(view, offset = -1) }
        assertThrows(IllegalArgumentException::class.java) { service.sourceFields(view, "outside") }
        assertThrows(IllegalArgumentException::class.java) { service.sourceText(view, "outside", "body") }
        assertTrue(service.sourceFields(view, "record-0").any { it.key == "body" })
        assertEquals("合成资料", service.sourceText(view, "record-0", "body", maxCodePoints = 4).text)
        assertThrows(IllegalArgumentException::class.java) { service.sourceText(view, "record-0", "body", maxCodePoints = 4097) }
        val before = repository.record("record-0")!!
        repository.updateRecord(before.id, before.revision, before.draft.copy(body = "合成更正"))
        assertThrows(StaleSnapshotException::class.java) { service.sourceFields(view, "record-0") }
        assertThrows(StaleSnapshotException::class.java) { service.sourceText(view, "record-0", "body") }
        assertThrows(StaleSnapshotException::class.java) { service.sources(view) }
    }

    @Test fun `archived records remain an explicit request condition`() {
        create("active", today); create("archived", today)
        repository.setArchived("archived", 1, true)
        assertEquals(1, service.open(request).current.metric.values.totalRecords)
        val included = service.open(request.copy(includeArchived = true))
        assertEquals(2, included.current.metric.values.totalRecords)
        assertTrue(included.current.metric.scope.includeArchived)
        assertTrue(service.sources(included).items.single { it.id == "archived" }.archived)
    }

    @Test fun `request card order is retained and caller mutation cannot change an issued snapshot`() {
        create("topic", today, topics = setOf("合成主题"))
        val selected = mutableListOf(DashboardMetric.RECORD_COVERAGE, DashboardMetric.DIET_MEALS)
        val view = service.open(request.copy(cards = selected))
        selected.clear()
        assertEquals(listOf(DashboardMetric.RECORD_COVERAGE, DashboardMetric.DIET_MEALS), view.cards.map { it.metric })
        assertSame(view.current, view.cards.first().current)
        assertThrows(UnsupportedOperationException::class.java) { (view.request.cards as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (view.cards as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (view.current.points as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (view.current.metric.values.daily as MutableList<*>).clear() }
        val sources = service.sources(view)
        assertThrows(UnsupportedOperationException::class.java) { (sources.items as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (sources.items.single().topics as MutableSet<*>).clear() }
        assertEquals(1, service.sources(view).total)
    }

    @Test fun `invalid card lists future dates timezone and lower date boundary are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { service.open(request.copy(cards = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { service.open(request.copy(cards = DashboardMetric.entries)) }
        assertThrows(IllegalArgumentException::class.java) {
            service.open(request.copy(cards = listOf(DashboardMetric.RECORD_COVERAGE, DashboardMetric.RECORD_COVERAGE)))
        }
        assertThrows(IllegalArgumentException::class.java) { service.open(request.copy(through = today.plusDays(1))) }
        assertThrows(java.time.DateTimeException::class.java) { service.open(request.copy(zoneId = "invalid/zone")) }
        assertThrows(IllegalArgumentException::class.java) { service.open(request.copy(through = LocalDate.of(1, 1, 1))) }
    }

    @Test fun `cards are frozen before clock callbacks can change the caller list`() {
        val cards = mutableListOf(DashboardMetric.RECORD_COVERAGE)
        clock.afterReads = 1
        clock.onRead = { cards.clear() }
        val view = service.open(request.copy(cards = cards))
        assertTrue(cards.isEmpty())
        assertEquals(listOf(DashboardMetric.RECORD_COVERAGE), view.request.cards)
        assertEquals(listOf(DashboardMetric.RECORD_COVERAGE), view.cards.map { it.metric })
    }

    @Test fun `leap days and daylight saving do not change date label arithmetic`() {
        clock.now = Instant.parse("2024-03-11T04:30:00Z")
        val view = service.open(DashboardRequest(DashboardWindow.DAYS_30, "America/New_York"))
        assertEquals(LocalDate.parse("2024-02-11"), view.current.metric.scope.from)
        assertEquals(LocalDate.parse("2024-03-11"), view.current.metric.scope.through)
        assertTrue(view.current.points.any { it.from == LocalDate.parse("2024-02-29") })
        assertEquals(Instant.parse("2024-03-12T04:00:00Z"), view.validUntil)
        clock.now = Instant.parse("2024-03-10T05:30:00Z")
        val dst = service.open(DashboardRequest(zoneId = "America/New_York"))
        assertEquals(Instant.parse("2024-03-11T04:00:00Z"), dst.validUntil)
    }

    @Test fun `midnight and backwards clock invalidate without repository writes`() {
        val view = service.open(request)
        assertEquals(Instant.parse("2026-10-19T16:00:00Z"), view.validUntil)
        clock.now = view.validUntil
        assertThrows(StaleSnapshotException::class.java) { service.sources(view) }
        clock.now = view.evaluatedAt.minusSeconds(1)
        assertThrows(StaleSnapshotException::class.java) { service.assertCurrent(view) }
    }

    @Test fun `source write during final snapshot check cannot issue a mixed dashboard`() {
        clock.afterReads = 2
        clock.onRead = { create("racing-source", today) }
        assertThrows(StaleSnapshotException::class.java) { service.open(request) }
        assertEquals(1, service.open(request).current.metric.values.totalRecords)
    }

    @Test fun `only current whole-range metric insight matches and previous explanations stay separate`() {
        create("record", today)
        val scope = AnalysisQuery(today.minusDays(6), today, request.zoneId)
        val full = publish("full", scope)
        publish("subset", scope.copy(recordIds = setOf("record")))
        publish("archived", scope.copy(includeArchived = true))
        publish("zone", scope.copy(zoneId = "UTC"))
        publish("overlap", scope.copy(from = scope.from.minusDays(1)))
        val previous = publish("previous", scope.copy(from = scope.from.minusDays(7), through = scope.from.minusDays(1)))
        val view = service.open(request)
        assertEquals(1, view.insightCount)
        assertEquals(1, view.previousInsightCount)
        assertEquals(full.id, service.insights(view).items.single().id)
        assertEquals(view.current.metric.id, service.insights(view).items.single().metricId)
        assertEquals(previous.id, service.insights(view, DashboardPeriodSelection.PREVIOUS).items.single().id)
        assertEquals(1, service.insights(view, offset = 1).total)
        assertTrue(service.insights(view, offset = 1).items.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { service.insights(view, limit = 101) }
    }

    @Test fun `metric explanation expiry is a fence even on a historical chart`() {
        val scope = AnalysisQuery(today.minusDays(7), today.minusDays(1), request.zoneId)
        val expiry = clock.now.plusSeconds(60)
        publish("expiring", scope, expiry)
        val view = service.open(request.copy(through = today.minusDays(1)))
        assertEquals(1, view.insightCount)
        assertEquals(expiry, view.validUntil)
        clock.now = expiry
        assertThrows(StaleSnapshotException::class.java) { service.insights(view) }
        assertEquals(0, service.open(request.copy(through = today.minusDays(1))).insightCount)
    }

    @Test fun `feedback and knowledge deletion invalidate snapshots and remove current explanations`() {
        val scope = AnalysisQuery(today.minusDays(6), today, request.zoneId)
        val saved = publish("selected", scope)
        val view = service.open(request)
        val analysis = ControlledAnalysis(repository, clock)
        val feedback = AnalysisFeedback(repository, clock)
        val value = feedback.forClaim("selected", saved.claimRef)!!.feedback
        feedback.update(value.id, value.version, analysis.task("selected").stored.version,
            ClaimFeedbackDecision.CORRECTED, "合成纠正：这里只代表记录覆盖。")
        assertThrows(StaleSnapshotException::class.java) { service.insights(view) }
        assertEquals(0, service.open(request).insightCount)
        val after = service.open(request)
        KnowledgeLibrary(repository, clock).delete(saved.id, saved.revision)
        assertThrows(StaleSnapshotException::class.java) { service.assertCurrent(after) }
    }

    private fun create(id: String, first: LocalDate?, last: LocalDate? = first, topics: Set<String> = emptySet()) {
        repository.createRecord(RecordDraft("合成资料：只用于看板功能验证。", Occurrence(first, last), topics = topics), id)
    }

    private fun publish(id: String, scope: AnalysisQuery, expiresAt: Instant? = null): StoredKnowledgeRevision {
        val analysis = ControlledAnalysis(repository, clock)
        val provider = Provider()
        analysis.start(AnalysisRequest("合成任务：说明记录覆盖。", scope, expiresAt = expiresAt), id)
        analysis.analyzeMetrics(id, "metric", provider)
        val claim = analysis.claimRef(id, "metric", 0)
        AnalysisFeedback(repository, clock).respond(id, claim, analysis.task(id).stored.version, ClaimFeedbackDecision.CONFIRMED)
        analysis.reviewClaim(id, "review", claim, provider = provider)
        return KnowledgeLibrary(repository, clock).publish(id, claim, "review", KnowledgeCategory.INSIGHT, analysis.task(id).stored.version)
    }

    private class Provider : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-dashboard-metric"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        override fun complete(request: ModelRequest): BatchProposal {
            val id = JSONObject(request.payloadJson).getJSONArray("metricEvidence").getJSONObject(0).getString("id")
            return BatchProposal(listOf(ProposedClaim("合成说明：统计仅代表已保存记录的日期覆盖。", listOf(id), ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val id = JSONObject(request.payloadJson).getJSONArray("metricEvidence").getJSONObject(0).getString("id")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(id)))
        }
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T01:00:00Z")) : Clock() {
        var onRead: (() -> Unit)? = null
        var afterReads = 1
        override fun instant(): Instant {
            if (--afterReads == 0) onRead?.also { onRead = null }?.invoke()
            return now
        }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
