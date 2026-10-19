package dev.tracelife.dashboard

import android.content.Context
import dev.tracelife.actions.ActionDecision
import dev.tracelife.actions.ActionDraft
import dev.tracelife.actions.ActionExecution
import dev.tracelife.actions.ActionExperiments
import dev.tracelife.actions.ActionReview
import dev.tracelife.storage.LifeRepository
import dev.tracelife.transfer.StaleSnapshotException
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

/** Synthetic user plans exercised only through public dashboard navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DashboardActionNavigationTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var actions: ActionExperiments
    private lateinit var dashboards: DashboardService
    private val name = "synthetic-dashboard-navigation-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val day = LocalDate.parse("2026-10-19")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        actions = ActionExperiments(repository, clock)
        dashboards = DashboardService(repository, clock)
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name) }

    @Test fun `navigation pages global sections and reads only registered current review and marker revisions`() {
        clock.now = Instant.parse("2026-10-01T03:00:00Z")
        actions.create(draft().copy(reviewOn = day.minusDays(18)), "old")
        clock.now = Instant.parse("2026-10-02T03:00:00Z")
        val reviewed = actions.review("old", 1, "old-review", ActionReview(ActionExecution.EXECUTED,
            "合成窗口外回看原文。", ActionDecision.CONTINUE, day.minusDays(17)), day.minusDays(16))
        clock.now = Instant.parse("2026-10-03T03:00:00Z")
        val edited = actions.update("old", 2, reviewed.value.draft.copy(title = "合成窗口外普通编辑"))
        clock.now = Instant.parse("2026-10-19T03:00:00Z")
        val current = actions.update("old", 3, edited.value.draft.copy(title = "合成当前计划"))

        val initial = actions.create(draft().copy(title = "合成回看前计划"), "recent")
        val observedText = "合成实际回看观察原文。"
        actions.review("recent", 1, "recent-review", ActionReview(ActionExecution.UNKNOWN, observedText,
            ActionDecision.ADJUST, day), adjustedDraft = initial.value.draft.copy(title = "合成调整后计划", reviewOn = day.plusDays(2)))
        actions.create(draft(), "stopped")
        actions.stop("stopped", 1, "stop", "合成停止观察。")

        val snapshot = dashboards.open(DashboardRequest(zoneId = "Asia/Shanghai"))
        val first = dashboards.actions(snapshot, limit = 1)
        val second = dashboards.actions(snapshot, offset = requireNotNull(first.nextOffset), limit = 1)
        val third = dashboards.actions(snapshot, offset = requireNotNull(second.nextOffset), limit = 1)
        assertEquals(3, first.total)
        assertEquals(setOf("old", "recent", "stopped"), (first.items + second.items + third.items).map { it.id }.toSet())
        assertNull(third.nextOffset)
        assertEquals(listOf("old"), dashboards.actions(snapshot, DashboardActionSection.DUE).items.map { it.id })
        assertEquals(listOf("recent"), dashboards.actions(snapshot, DashboardActionSection.UPCOMING).items.map { it.id })
        assertEquals(listOf("stopped"), dashboards.actions(snapshot, DashboardActionSection.STOPPED).items.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { dashboards.actions(snapshot, limit = 101) }
        assertTrue(dashboards.actions(snapshot, offset = Int.MAX_VALUE).items.isEmpty())

        // The global panel retains an old latest review, but does not grant every historical edit.
        assertEquals(current.value, dashboards.action(snapshot, "old"))
        assertEquals(reviewed.value, dashboards.action(snapshot, "old", revision = 2))
        assertEquals(DashboardActionExecution.EXECUTED, dashboards.actions(snapshot, DashboardActionSection.DUE).items.single().latestExecution)
        assertThrows(IllegalArgumentException::class.java) { dashboards.action(snapshot, "old", revision = 3) }
        assertThrows(IllegalArgumentException::class.java) { dashboards.action(snapshot, "old", revision = 1) }
        assertThrows(IllegalArgumentException::class.java) { dashboards.action(snapshot, "not-registered") }

        val markers = dashboards.markers(snapshot).items
        assertFalse(markers.any { it.actionId == "old" })
        val marker = markers.single { it.actionId == "recent" && it.kind == DashboardActionMarkerKind.REVIEWED }
        val markedReview = dashboards.action(snapshot, marker.actionId, marker.revision)
        assertEquals(initial.value.draft, markedReview.reviewedDraft)
        assertEquals(observedText, markedReview.review!!.observedText)
        val adoption = markers.single { it.actionId == "recent" && it.kind == DashboardActionMarkerKind.ADOPTED }
        assertEquals(initial.value, dashboards.action(snapshot, adoption.actionId, adoption.revision))
        assertEquals(1, dashboards.markers(snapshot, limit = 1).items.size)
        assertThrows(IllegalArgumentException::class.java) { dashboards.markers(snapshot, limit = 101) }

        actions.update("old", 4, current.value.draft.copy(title = "合成打开看板后编辑"))
        assertAllNavigationStale(snapshot, "old")
    }

    @Test fun `earlier midnight in action timezone expires every public action navigation`() {
        clock.now = Instant.parse("2026-10-19T00:30:00Z")
        actions.create(draft().copy(zoneId = "America/Los_Angeles"), "west")
        val snapshot = dashboards.open(DashboardRequest(zoneId = "Asia/Shanghai"))
        assertEquals(Instant.parse("2026-10-19T07:00:00Z"), snapshot.validUntil)
        assertFalse(dashboards.actions(snapshot).items.single().due)
        clock.now = snapshot.validUntil.minusSeconds(1)
        assertEquals("west", dashboards.action(snapshot, "west").id)
        assertEquals(1, dashboards.markers(snapshot).total)

        clock.now = snapshot.validUntil
        assertAllNavigationStale(snapshot, "west")
        val refreshed = dashboards.open(DashboardRequest(zoneId = "Asia/Shanghai"))
        assertEquals(listOf("west"), dashboards.actions(refreshed, DashboardActionSection.DUE).items.map { it.id })
        assertEquals(Instant.parse("2026-10-19T16:00:00Z"), refreshed.validUntil)
    }

    private fun assertAllNavigationStale(snapshot: DashboardSnapshot, id: String) {
        assertThrows(StaleSnapshotException::class.java) { dashboards.actions(snapshot) }
        assertThrows(StaleSnapshotException::class.java) { dashboards.action(snapshot, id) }
        assertThrows(StaleSnapshotException::class.java) { dashboards.markers(snapshot) }
    }

    private fun draft() = ActionDraft("合成行动", "合成触发", "合成用户独立做法。", "合成期望信号", day, "Asia/Shanghai")

    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T03:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
