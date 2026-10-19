package dev.tracelife.dashboard

import android.content.Context
import dev.tracelife.actions.ActionDecision
import dev.tracelife.actions.ActionDraft
import dev.tracelife.actions.ActionExecution
import dev.tracelife.actions.ActionExperiments
import dev.tracelife.actions.ActionOrigin
import dev.tracelife.actions.ActionOriginState
import dev.tracelife.actions.ActionReview
import dev.tracelife.actions.ActionStatus
import dev.tracelife.analysis.AnalysisProvider
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.BatchProposal
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ClaimReview
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.analysis.EvidenceSelection
import dev.tracelife.analysis.ModelRequest
import dev.tracelife.analysis.PreparedModelRequest
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.analysis.ReviewCompletion
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.analysis.SemanticReviewProvider
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.LifeRepository
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

/** Synthetic plans and synthetic review fixtures. No personal data, network, or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DashboardActionsTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var actions: ActionExperiments
    private val name = "synthetic-dashboard-actions-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val day = LocalDate.parse("2026-10-19")
    private val scope = AnalysisQuery(day.minusDays(6), day, "Asia/Shanghai")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        actions = ActionExperiments(repository, clock)
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name) }

    @Test fun `ordinary edits retain latest actual review and never reviewed differs from unknown`() {
        actions.create(draft(), "unreviewed")
        actions.create(draft(), "unknown")
        val unknown = actions.review("unknown", 1, "unknown-review", review(ActionExecution.UNKNOWN), day.plusDays(2))
        actions.update("unknown", 2, unknown.value.draft.copy(title = "合成后来编辑"))
        actions.create(draft(), "executed")
        val executed = actions.review("executed", 1, "executed-review", review(ActionExecution.EXECUTED), day.plusDays(3))
        actions.update("executed", 2, executed.value.draft.copy(title = "合成执行后编辑"))

        val items = collect().items.associateBy { it.id }
        assertEquals(DashboardActionExecution.NOT_REVIEWED, items.getValue("unreviewed").latestExecution)
        assertNull(items.getValue("unreviewed").latestReview)
        assertEquals(DashboardActionExecution.UNKNOWN, items.getValue("unknown").latestExecution)
        assertEquals(2, items.getValue("unknown").latestReview!!.revision)
        assertEquals(DashboardActionExecution.EXECUTED, items.getValue("executed").latestExecution)
        assertEquals("合成执行后编辑", items.getValue("executed").title)
        assertEquals("executed-review", items.getValue("executed").latestReview!!.operationId)
    }

    @Test fun `global current panel does not hide old overdue plans and stopped plans are not due`() {
        actions.create(draft().copy(reviewOn = day.minusMonths(2)), "overdue")
        actions.create(draft(), "stopped")
        actions.stop("stopped", 1, "stop", "合成用户决定停止。", ActionExecution.NOT_EXECUTED)
        actions.create(draft().copy(reviewOn = day.plusMonths(2)), "future")
        val olderWindow = AnalysisQuery(day.minusDays(30), day.minusDays(24), scope.zoneId)

        val result = collect(olderWindow)
        assertEquals(listOf("overdue", "stopped", "future"), result.items.map { it.id })
        assertTrue(result.items[0].due)
        assertEquals(ActionStatus.STOPPED, result.items[1].status)
        assertFalse(result.items[1].due)
        assertEquals(DashboardActionExecution.NOT_EXECUTED, result.items[1].latestExecution)
        assertFalse(result.items[2].due)
        assertTrue(result.markers.isEmpty())
    }

    @Test fun `each action uses its timezone for due and earliest midnight bounds the snapshot`() {
        clock.now = Instant.parse("2026-10-19T00:30:00Z")
        actions.create(draft().copy(zoneId = "Asia/Shanghai"), "east")
        actions.create(draft().copy(zoneId = "America/Los_Angeles"), "west")
        val result = collect()
        assertTrue(result.items.single { it.id == "east" }.due)
        assertFalse(result.items.single { it.id == "west" }.due)
        assertEquals(Instant.parse("2026-10-19T07:00:00Z"), result.validUntil)
        clock.now = Instant.parse("2026-10-19T07:00:00Z")
        assertTrue(collect().items.all { it.due })
    }

    @Test fun `action midnight uses calendar transition across daylight saving`() {
        clock.now = Instant.parse("2026-11-01T04:30:00Z")
        actions.create(draft().copy(zoneId = "America/New_York", reviewOn = LocalDate.parse("2026-11-01")), "dst")
        val result = collect(AnalysisQuery(LocalDate.parse("2026-10-26"), LocalDate.parse("2026-11-01"), "UTC"))
        assertEquals(Instant.parse("2026-11-02T05:00:00Z"), result.validUntil)
    }

    @Test fun `adoption is an instant while review keeps old plan date and zone after adjustment`() {
        clock.now = Instant.parse("2026-10-19T00:30:00Z")
        actions.create(draft().copy(title = "合成旧计划", zoneId = "America/Los_Angeles"), "action")
        actions.review("action", 1, "adjust", ActionReview(ActionExecution.UNKNOWN, "合成补记回看。",
            ActionDecision.ADJUST, day.minusDays(1)), adjustedDraft = draft().copy(title = "合成新计划",
            zoneId = "Asia/Shanghai", reviewOn = day.plusDays(2)))

        val result = collect()
        val adopted = result.markers.single { it.kind == DashboardActionMarkerKind.ADOPTED }
        assertEquals(day, adopted.date)
        assertEquals(scope.zoneId, adopted.zoneId)
        assertEquals(clock.now, adopted.occurredAt)
        assertNull(adopted.execution)
        val reviewed = result.markers.single { it.kind == DashboardActionMarkerKind.REVIEWED }
        assertEquals(day.minusDays(1), reviewed.date)
        assertEquals("America/Los_Angeles", reviewed.zoneId)
        assertEquals("合成旧计划", reviewed.title)
        assertNull(reviewed.occurredAt)
        assertEquals(2, reviewed.revision)
        assertEquals("Asia/Shanghai", result.items.single().zoneId)
        assertEquals("America/Los_Angeles", result.items.single().latestReview!!.zoneId)
        assertEquals(listOf(DashboardActionMarkerKind.REVIEWED),
            collect(scope.copy(from = day.minusDays(1), through = day.minusDays(1))).markers.map { it.kind })
    }

    @Test fun `history pagination finds an actual review older than one hundred later edits`() {
        actions.create(draft(), "action")
        var saved = actions.review("action", 1, "old-review", review(ActionExecution.EXECUTED), day.plusDays(1))
        repeat(101) { index -> saved = actions.update("action", saved.value.revision, saved.value.draft.copy(title = "合成编辑 $index")) }
        val result = collect()
        assertEquals(103, result.items.single().revision)
        assertEquals(2, result.items.single().latestReview!!.revision)
        assertEquals(DashboardActionExecution.EXECUTED, result.items.single().latestExecution)
        assertEquals(2, result.markers.size)
        assertEquals(setOf(1, 2), result.markers.map { it.revision }.toSet())
    }

    @Test fun `collector traverses multiple head pages and freezes metadata inventories`() {
        repeat(101) { index -> actions.create(draft(), "synthetic-${index.toString().padStart(3, '0')}") }
        val result = collect()
        assertEquals(101, result.items.size)
        assertEquals(101, result.markers.size)
        assertEquals("synthetic-000", result.items.first().id)
        assertEquals("synthetic-100", result.items.last().id)
        assertThrows(UnsupportedOperationException::class.java) { (result.items as MutableList<DashboardActionItem>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.markers as MutableList<DashboardActionMarker>).clear() }
    }

    @Test fun `source or action modifications reject already selected generations`() {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        actions.create(draft(), "action")
        val collector = DashboardActionCollector(repository, clock)
        assertThrows(StaleSnapshotException::class.java) { collector.collect(source, generation, clock.now, scope) }
        val newGeneration = repository.currentAnalysisGeneration()
        repository.createRecord(RecordDraft("合成新增记录。", Occurrence(day)), "source")
        assertThrows(StaleSnapshotException::class.java) { collector.collect(source, newGeneration, clock.now, scope) }
    }

    @Test fun `valid origin expiry bounds snapshot while invalid and deleted origins preserve independent plans`() {
        val expiry = clock.now.plusSeconds(600)
        val origin = publishOrigin(expiry)
        val selected = draft().copy(origin = origin)
        actions.create(selected, "action")
        val current = collect()
        assertEquals(ActionOriginState.CURRENT, current.items.single().originState)
        assertEquals(expiry, current.validUntil)

        clock.now = expiry
        assertEquals(ActionOriginState.STALE, collect().items.single().originState)
        assertEquals(selected, actions.get("action")!!.value.draft)
        repository.updateRecord("source", 1, RecordDraft("合成来源修订。", Occurrence(day)))
        assertEquals(ActionOriginState.STALE, collect().items.single().originState)
        repository.deleteRecord("source", 2)
        assertEquals(ActionOriginState.UNKNOWN, collect().items.single().originState)
        assertEquals(selected, actions.get("action")!!.value.draft)
        actions.delete("action", 1)
        assertTrue(collect().items.isEmpty())
        assertTrue(collect().markers.isEmpty())
    }

    private fun collect(query: AnalysisQuery = scope) = DashboardActionCollector(repository, clock).collect(
        repository.analysisSourceStamp(), repository.currentAnalysisGeneration(), clock.now, query,
    )

    private fun draft() = ActionDraft("合成行动", "合成触发", "合成独立计划正文", "合成期望信号", day, "Asia/Shanghai")
    private fun review(execution: ActionExecution) = ActionReview(execution, "合成用户回看正文。", ActionDecision.CONTINUE, day)

    private fun publishOrigin(expiry: Instant): ActionOrigin {
        repository.createRecord(RecordDraft("合成记录：我尝试了分步骤练习。", Occurrence(day)), "source")
        val analysis = ControlledAnalysis(repository, clock)
        analysis.start(AnalysisRequest("合成来源整理", scope, expiresAt = expiry), "run")
        analysis.runBatch("run", "extract", listOf(EvidenceSelection("source", "body")), object : AnalysisProvider {
            override val id = "synthetic-dashboard-extract"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = 100
            override fun complete(request: ModelRequest): BatchProposal {
                val receipt = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim("合成陈述：用户自述尝试分步骤练习。", listOf(receipt), ClaimKind.REPORTED_EXPERIENCE)))
            }
        })
        val claim = analysis.claimRef("run", "extract", 0)
        analysis.reviewClaim("run", "review", claim, provider = object : SemanticReviewProvider {
            override val id = "synthetic-dashboard-review"
            override val contextWindowTokens = 100_000
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                val receipt = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(receipt)))
            }
        })
        val knowledge = KnowledgeLibrary(repository, clock).publish("run", claim, "review", KnowledgeCategory.OBSERVATION,
            analysis.task("run").stored.version)
        return ActionOrigin(knowledge.id, knowledge.revision)
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T03:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
