package dev.tracelife.dashboard

import dev.tracelife.profile.ProfileDimension
import dev.tracelife.storage.LifeRepository
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/** Presentation choices, never metric definitions or inferred health values. */
data class DashboardPreferences(
    val zoneId: String,
    val window: DashboardWindow = DashboardWindow.DAYS_7,
    val cards: List<DashboardMetric> = listOf(DashboardMetric.SLEEP_DURATION,
        DashboardMetric.EXERCISE_MINUTES, DashboardMetric.DIET_MEALS),
    val focusDimensions: List<ProfileDimension> = listOf(ProfileDimension.ENERGY_AND_RECOVERY),
    val includeArchived: Boolean = false,
) {
    fun validated(): DashboardPreferences {
        require(zoneId.length <= 100)
        ZoneId.of(zoneId)
        require(cards.size in 1..3 && cards.distinct().size == cards.size) { "选择 1 至 3 个不重复指标" }
        require(focusDimensions.size in 1..ProfileDimension.entries.size && focusDimensions.distinct().size == focusDimensions.size) {
            "关注维度不能为空或重复"
        }
        return copy(cards = Collections.unmodifiableList(ArrayList(cards)),
            focusDimensions = Collections.unmodifiableList(ArrayList(focusDimensions)))
    }
}

data class StoredDashboardPreferences(val revision: Int, val value: DashboardPreferences, val updatedAt: Instant)
data class DashboardPreferencesView(val revision: Int, val value: DashboardPreferences, val updatedAt: Instant?)

class DashboardPreferencesService(private val repository: LifeRepository,
    private val dashboard: DashboardService = DashboardService(repository),
    private val defaultZoneId: String = ZoneId.systemDefault().id) {
    init { ZoneId.of(defaultZoneId) }

    fun read(): DashboardPreferencesView = repository.dashboardPreferences()?.let {
        DashboardPreferencesView(it.revision, it.value, it.updatedAt)
    } ?: DashboardPreferencesView(0, DashboardPreferences(defaultZoneId).validated(), null)

    fun save(expectedRevision: Int, value: DashboardPreferences): DashboardPreferencesView =
        repository.saveDashboardPreferences(expectedRevision, value).let { DashboardPreferencesView(it.revision, it.value, it.updatedAt) }

    /** Reset is an explicit new decision; an older backup cannot resurrect the former choices. */
    fun reset(expectedRevision: Int): DashboardPreferencesView = save(expectedRevision, DashboardPreferences(defaultZoneId))

    fun open(through: LocalDate? = null): DashboardSnapshot {
        val before = read()
        val snapshot = dashboard.open(DashboardRequest(before.value.window, before.value.zoneId,
            through, before.value.cards, before.value.includeArchived))
        if (read() != before) throw StaleSnapshotException("看板偏好已变化，请重新打开")
        dashboard.assertCurrent(snapshot)
        return snapshot
    }
}
