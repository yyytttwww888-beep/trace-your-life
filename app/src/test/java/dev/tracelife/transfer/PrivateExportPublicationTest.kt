package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.LifeRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
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

/** Private export capability, source freshness, competing destinations and incomplete-file visibility. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PrivateExportPublicationTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var completed: CompletedExports
    private val clock = MutableClock()
    private val databaseName = "synthetic-private-export-${UUID.randomUUID()}.db"
    private val day = LocalDate.parse("2026-10-20")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, databaseName)
        completed = CompletedExports(context)
        repository.createRecord(RecordDraft("合成资料：私有导出范围。", Occurrence(day)), "source")
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
        PrivateExportDestination.root(context).deleteRecursively()
    }

    @Test fun `ordinary file APIs cannot write into an owned private destination`() {
        val target = PrivateExportDestination.reserve(context, "knowledge")
        val stage = stage(target)
        assertThrows(IllegalArgumentException::class.java) { DataTransfer(context, repository).exportReadingPackage(target.file) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.publishDerivedFile(repository.analysisSourceStamp(), repository.currentAnalysisGeneration(), null, stage, target.file)
        }
        assertFalse(target.file.exists())
        assertTrue(completed.list().isEmpty())
    }

    @Test fun `prepared files do not become visible until their checked atomic publication`() {
        val target = PrivateExportDestination.reserve(context, "knowledge")
        val stage = stage(target)
        target.prepare(stage)
        assertTrue(completed.list().isEmpty())
        assertNull(completed.get(target.file.name))
        repository.publishPrivateExport(repository.analysisSourceStamp(), repository.currentAnalysisGeneration(),
            clock.instant().plusSeconds(60), stage, target)
        assertEquals("Synthetic complete immutable bytes", requireNotNull(completed.get(target.file.name)).readText())
        assertFalse(stage.exists())
        assertEquals(64, completed.expectedHash(target.file.name).length)
        assertThrows(IllegalStateException::class.java) { target.publish(stage) }
    }

    @Test fun `changed source and expiry reject private publication without a visible completed artifact`() {
        val target = PrivateExportDestination.reserve(context, "knowledge")
        val stage = stage(target)
        target.prepare(stage)
        val original = repository.analysisSourceStamp()
        val analysisGeneration = repository.currentAnalysisGeneration()
        repository.updateRecord("source", 1, RecordDraft("合成资料：已修改。", Occurrence(day)))
        assertThrows(StaleSnapshotException::class.java) {
            repository.publishPrivateExport(original, analysisGeneration, null, stage, target)
        }
        assertFalse(target.file.exists())
        assertThrows(StaleSnapshotException::class.java) {
            repository.publishPrivateExport(repository.analysisSourceStamp(), repository.currentAnalysisGeneration(), clock.instant(), stage, target)
        }
        assertTrue(completed.list().isEmpty())
        assertTrue(stage.isFile)
    }

    @Test fun `even unexpected competing content is rejected without replacing it`() {
        val target = PrivateExportDestination.reserve(context, "knowledge")
        val stage = stage(target)
        target.prepare(stage)
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val until = clock.instant().plusSeconds(60)
        clock.onNextInstant = { target.file.writeText("Synthetic independently-created contents") }
        assertThrows(IllegalStateException::class.java) {
            repository.publishPrivateExport(source, generation, until, stage, target)
        }
        assertEquals("Synthetic independently-created contents", target.file.readText())
        assertTrue(completed.list().isEmpty())
    }

    @Test fun `a different staging file cannot use a prepared capability`() {
        val target = PrivateExportDestination.reserve(context, "knowledge")
        val stage = stage(target)
        target.prepare(stage)
        val other = File(target.file.parentFile, "other.pending").apply { writeText("Synthetic different bytes") }
        assertThrows(IllegalStateException::class.java) {
            repository.publishPrivateExport(repository.analysisSourceStamp(), repository.currentAnalysisGeneration(), null, other, target)
        }
        assertFalse(target.file.exists())
    }

    @Test fun `analysis generation and clock rollback independently prevent private publication`() {
        val target = PrivateExportDestination.reserve(context, "knowledge")
        val stage = stage(target)
        target.prepare(stage)
        assertThrows(StaleSnapshotException::class.java) {
            repository.publishPrivateExport(repository.analysisSourceStamp(), repository.currentAnalysisGeneration() - 1, null, stage, target)
        }
        assertThrows(StaleSnapshotException::class.java) {
            repository.publishPrivateExport(repository.analysisSourceStamp(), repository.currentAnalysisGeneration(), null, stage, target,
                validFrom = clock.instant().plusSeconds(1))
        }
        assertTrue(completed.list().isEmpty())
        assertTrue(stage.isFile)
    }

    @Test fun `receipt removal or truncation removes completed copy from delivery choices`() {
        val (_, file) = completed.create("knowledge") { DataTransfer(context, repository, clock).exportReadingPackage(it) }
        assertNotNull(completed.get(file.name))
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 1))
        assertNull(completed.get(file.name))
        file.writeBytes(bytes)
        assertNotNull(completed.get(file.name))
        File(file.parentFile, PrivateExportDestination.RECEIPT).delete()
        assertNull(completed.get(file.name))
    }

    private fun stage(target: PrivateExportDestination) = File(target.file.parentFile, "synthetic.pending")
        .apply { writeText("Synthetic complete immutable bytes") }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-20T03:00:00Z")) : Clock() {
        var onNextInstant: (() -> Unit)? = null
        override fun instant(): Instant {
            val callback = onNextInstant
            onNextInstant = null
            callback?.invoke()
            return now
        }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
