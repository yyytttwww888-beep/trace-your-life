package dev.tracelife.overview

import android.app.Application
import android.content.Context
import dev.tracelife.storage.LifeRepository
import dev.tracelife.transfer.KnowledgeArchive
import dev.tracelife.transfer.ReadingExportOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile

/** Archiving historical source material is not a new statement of the user's current goals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DirectionOrderingRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var overview: PersonalOverview
    private val database = "synthetic-direction-order-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val date = LocalDate.parse("2026-10-20")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        overview = PersonalOverview(repository, clock)
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `archiving an older direction keeps the later statement current in app and reading package`() {
        val old = overview.save(PersonalDirection(goals = "合成过去目标", statedOn = date), creationId = "old")
        clock.advance()
        val latest = overview.save(PersonalDirection(goals = "合成现在目标", statedOn = date), creationId = "latest")
        clock.advance()
        val archived = repository.setArchived(old.id, old.revision, true)
        assertEquals(latest.id, overview.open().direction?.record?.id)
        assertTrue(exportOverview().contains("合成现在目标"))
        clock.advance()
        repository.setArchived(old.id, archived.revision, false)
        assertEquals(latest.id, overview.open().direction?.record?.id)
        assertTrue(exportOverview().contains("合成现在目标"))
        assertFalse(exportOverview().contains("合成过去目标"))
    }

    @Test fun `a real direction edit changes priority but later archival does not restore an older statement`() {
        val old = overview.save(PersonalDirection(goals = "合成旧目标", statedOn = date), creationId = "old")
        clock.advance()
        overview.save(PersonalDirection(goals = "合成另一目标", statedOn = date), creationId = "other")
        clock.advance()
        val revised = overview.save(PersonalDirection(goals = "合成明确重新选择的目标", statedOn = date), old.id, old.revision)
        assertEquals(revised.id, overview.open().direction?.record?.id)
        assertTrue(exportOverview().contains("合成明确重新选择的目标"))
        clock.advance()
        repository.setArchived(revised.id, revised.revision, true)
        assertNull(overview.open().direction)
        assertFalse(exportOverview().contains("合成另一目标"))
    }

    private fun exportOverview(): String {
        val attachments = temporary.newFolder()
        val snapshot = repository.captureSnapshot(attachments)
        val zip = File(temporary.root, "${UUID.randomUUID()}.zip")
        KnowledgeArchive.write(snapshot, attachments, zip, ReadingExportOptions())
        return ZipFile(zip).use { it.getInputStream(it.getEntry("overview.md")).bufferedReader().readText() }
    }
    private class MutableClock : Clock() {
        private var now = Instant.parse("2026-10-20T04:00:00Z")
        fun advance() { now = now.plusSeconds(1) }
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
}
