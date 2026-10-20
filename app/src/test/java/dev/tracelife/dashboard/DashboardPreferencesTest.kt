package dev.tracelife.dashboard

import android.content.Context
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.profile.ProfileDimension
import dev.tracelife.storage.LifeDatabase
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SnapshotValidation
import dev.tracelife.transfer.*
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.*
import java.util.UUID

/** Synthetic settings only; no model API or personal health data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DashboardPreferencesTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var service: DashboardPreferencesService
    private val name = "synthetic-preferences-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private lateinit var workspace: File
    private val custom = DashboardPreferences("Asia/Shanghai", DashboardWindow.DAYS_30,
        listOf(DashboardMetric.RECORD_COVERAGE, DashboardMetric.SLEEP_DURATION),
        listOf(ProfileDimension.ENERGY_AND_RECOVERY), true)

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        workspace = File(context.cacheDir, "synthetic-prefs-${UUID.randomUUID()}").apply { check(mkdirs()) }
        repository = LifeRepository(context, clock, name)
        connect()
    }
    private fun connect() { service = DashboardPreferencesService(repository, DashboardService(repository, clock), "UTC") }
    @After fun cleanup() { repository.close(); context.deleteDatabase(name); workspace.deleteRecursively() }

    @Test fun `defaults are read only and saved ordered preferences survive reopening`() {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        assertEquals(0, service.read().revision)
        assertNull(repository.dashboardPreferences())
        assertEquals(generation, repository.currentAnalysisGeneration())
        val chosen = service.save(0, custom)
        assertEquals(1, chosen.revision)
        assertEquals(source, repository.analysisSourceStamp())
        assertTrue(repository.currentAnalysisGeneration() > generation)
        repository.close(); repository = LifeRepository(context, clock, name); connect()
        assertEquals(chosen, service.read())
        val view = service.open(LocalDate.parse("2026-10-18"))
        assertEquals(custom.cards, view.request.cards)
        assertEquals(custom.window, view.request.window)
        assertEquals(custom.zoneId, view.request.zoneId)
        assertTrue(view.request.includeArchived)
        assertEquals(DashboardCardState.SOURCE_NOT_CONNECTED, view.cards.last().state)
    }

    @Test fun `concurrent writers cannot silently replace settings and no-op does not advance revisions`() {
        val first = service.save(0, custom)
        val generation = repository.currentAnalysisGeneration()
        assertEquals(first, service.save(first.revision, custom))
        assertEquals(generation, repository.currentAnalysisGeneration())
        LifeRepository(context, clock, name).use { other ->
            assertThrows(RevisionConflictException::class.java) { other.saveDashboardPreferences(0, custom.copy(includeArchived = false)) }
        }
        val view = service.open()
        service.reset(1)
        assertThrows(StaleSnapshotException::class.java) { DashboardService(repository, clock).assertCurrent(view) }
        assertEquals(2, service.read().revision)
        clock.now = clock.now.minusSeconds(1)
        assertThrows(IllegalArgumentException::class.java) { service.save(2, custom) }
    }

    @Test fun `save freezes mutable inputs and rejects invalid choices before any write`() {
        val cards = mutableListOf(DashboardMetric.RECORD_COVERAGE)
        service.save(0, custom.copy(cards = cards)); cards.clear()
        assertEquals(listOf(DashboardMetric.RECORD_COVERAGE), service.read().value.cards)
        val before = service.read()
        listOf(custom.copy(cards = emptyList()), custom.copy(cards = listOf(DashboardMetric.DIET_MEALS, DashboardMetric.DIET_MEALS)),
            custom.copy(focusDimensions = emptyList()), custom.copy(zoneId = "bad-zone")).forEach { invalid ->
            assertThrows(Exception::class.java) { service.save(before.revision, invalid) }
        }
        assertEquals(before, service.read())
    }

    @Test fun `encrypted backup roundtrip restores preferences on new device but preserves local reset`() {
        val saved = service.save(0, custom)
        val source = snapshot()
        val bytes = ByteArrayOutputStream().also { SnapshotJsonCodec.write(source, it) }.toByteArray()
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(8, json.getInt("formatVersion")); assertEquals(9, json.getInt("sourceSchemaVersion"))
        assertEquals(source, SnapshotJsonCodec.read(bytes.inputStream()))
        val archive = File(workspace, "synthetic.tlb")
        val password = "synthetic-settings-test-passphrase".toCharArray()
        BackupArchive.write(source, directory(), archive, password)
        val decoded = BackupArchive.read(archive, File(workspace, "unpacked"), password)
        val targetName = "synthetic-target-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, targetName).use { target ->
                target.restoreSnapshot(decoded, directory(), target.currentGeneration())
                val preferences = DashboardPreferencesService(target, defaultZoneId = "UTC")
                assertEquals(saved, preferences.read())
                val reset = preferences.reset(saved.revision)
                val epoch = target.localRestoreEpoch()
                target.restoreSnapshot(source, directory(), target.currentGeneration(), true, target.currentAnalysisGeneration())
                assertEquals(reset, preferences.read())
                assertEquals(epoch + 1, target.localRestoreEpoch())
            }
        } finally { context.deleteDatabase(targetName) }
    }

    @Test fun `legacy backups have no preferences and mixed or malformed formats are rejected`() {
        val current = snapshot()
        val encoded = ByteArrayOutputStream().also { SnapshotJsonCodec.write(current, it) }.toString(Charsets.UTF_8)
        val old = JSONObject(encoded).put("formatVersion", 6).put("sourceSchemaVersion", 7).apply { remove("dashboardPreferences") }
        assertEquals(current.copy(sourceSchemaVersion = 7), SnapshotJsonCodec.read(old.toString().byteInputStream()))
        old.put("dashboardPreferences", JSONObject.NULL)
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(old.toString().byteInputStream()) }
        val missing = JSONObject(encoded).apply { remove("dashboardPreferences") }
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(missing.toString().byteInputStream()) }
        val value = service.save(0, custom)
        val settings = StoredDashboardPreferences(value.revision, value.value, requireNotNull(value.updatedAt))
        val payload = DashboardPreferencesCodec.encode(settings)
        listOf(payload.replace("\"revision\":1", "\"revision\":1.0"),
            payload.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            payload.dropLast(1) + ",\"revision\":2}",
            payload.dropLast(1) + ",\"secret\":\"synthetic\"}", payload + "{}").forEach { bad ->
            val malformed = JSONObject(encoded).put("dashboardPreferences", bad)
            assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(malformed.toString().byteInputStream()) }
        }
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(snapshot().copy(sourceSchemaVersion = 7)) }
    }

    @Test fun `schema seven upgrade retains record identity and source generations`() {
        val record = repository.createRecord(RecordDraft("合成迁移记录"), "synthetic-record")
        val source = repository.analysisSourceStamp(); val generation = repository.currentAnalysisGeneration()
        repository.close()
        LifeDatabase(context, name).use { helper -> helper.writableDatabase.apply {
            dev.tracelife.storage.removeVersionNineForMigration(this)
            execSQL("DROP TABLE dashboard_preferences")
            execSQL("ALTER TABLE store_state DROP COLUMN restore_epoch")
            version = 7
        } }
        repository = LifeRepository(context, clock, name); connect()
        assertEquals(source, repository.analysisSourceStamp()); assertEquals(generation, repository.currentAnalysisGeneration())
        assertEquals(record, repository.record(record.id)); assertEquals(0L, repository.localRestoreEpoch())
        assertNull(repository.dashboardPreferences()); service.save(0, custom)
        assertEquals(9, snapshot().sourceSchemaVersion)
    }
    private fun directory() = File(workspace, UUID.randomUUID().toString()).apply { check(mkdir()) }
    private fun snapshot() = repository.captureSnapshot(directory())
    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T06:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
}
