package dev.tracelife.storage

import android.content.Context
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
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
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic file bytes and records; publication never includes personal files or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DerivedFilePublicationTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var workspace: File
    private val databaseName = "synthetic-derived-publication-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val day = LocalDate.parse("2026-10-17")
    private val content = "合成完整导出文件：指标与材料。".toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, databaseName)
        workspace = File(context.cacheDir, "synthetic-derived-files-${UUID.randomUUID()}").apply { mkdirs() }
        repository.createRecord(RecordDraft("合成发布依据。", Occurrence(day)), "source")
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
        workspace.deleteRecursively()
    }

    @Test fun `successful publication preserves complete bytes through caller staging cleanup without changing generations`() {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val stage = stage("success")
        val destination = File(workspace, "success.json")

        repository.publishDerivedFile(source, generation, clock.instant().plusSeconds(60), stage, destination)

        assertArrayEquals(content, destination.readBytes())
        assertTrue("The caller retains ownership of staging cleanup", stage.exists())
        assertTrue(stage.delete())
        assertArrayEquals(content, destination.readBytes())
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(generation, repository.currentAnalysisGeneration())
    }

    @Test fun `another source identity or a changed source generation cannot publish old bytes`() {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val stage = stage("stale-source")
        val destination = File(workspace, "stale-source.json")
        assertThrows(StaleSnapshotException::class.java) {
            repository.publishDerivedFile(source.copy(storeId = "another-synthetic-store"), generation, null, stage, destination)
        }
        assertFalse(destination.exists())

        repository.updateRecord("source", 1, RecordDraft("合成发布前改动。", Occurrence(day)))
        assertThrows(StaleSnapshotException::class.java) {
            repository.publishDerivedFile(source, generation, null, stage, destination)
        }
        assertFalse(destination.exists())
        assertArrayEquals(content, stage.readBytes())
    }

    @Test fun `analysis changes invalidate a derived export even when original records did not change`() {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val stage = stage("stale-analysis")
        val destination = File(workspace, "stale-analysis.json")
        ControlledAnalysis(repository, clock).start(
            AnalysisRequest("合成新分析任务", AnalysisQuery(day, day, "Asia/Shanghai")), "analysis",
        )
        assertEquals(source, repository.analysisSourceStamp())
        assertTrue(repository.currentAnalysisGeneration() > generation)

        assertThrows(StaleSnapshotException::class.java) {
            repository.publishDerivedFile(source, generation, null, stage, destination)
        }

        assertFalse(destination.exists())
        assertArrayEquals(content, stage.readBytes())
    }

    @Test fun `expiry is exclusive and rejects publication at the boundary without database writes`() {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val expiresAt = clock.instant().plusSeconds(1)
        clock.now = expiresAt.minusNanos(1)
        val before = File(workspace, "before-expiry.json")
        repository.publishDerivedFile(source, generation, expiresAt, stage("before-expiry"), before)
        assertArrayEquals(content, before.readBytes())

        clock.now = expiresAt
        val expiredStage = stage("at-expiry")
        val expiredDestination = File(workspace, "at-expiry.json")
        assertThrows(StaleSnapshotException::class.java) {
            repository.publishDerivedFile(source, generation, expiresAt, expiredStage, expiredDestination)
        }

        assertFalse(expiredDestination.exists())
        assertArrayEquals(content, expiredStage.readBytes())
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(generation, repository.currentAnalysisGeneration())
    }

    @Test fun `a destination appearing after the initial check is never overwritten`() {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val stage = stage("competing-target")
        val destination = File(workspace, "competing-target.json")
        val expiresAt = clock.instant().plusSeconds(60)
        val competingContent = "synthetic independently created file".toByteArray(Charsets.UTF_8)
        var competitorCreated = false
        // The expiry clock is read inside publication, after the initial destination check.
        clock.onNextInstant = {
            assertFalse(destination.exists())
            destination.writeBytes(competingContent)
            competitorCreated = true
        }

        assertThrows(FileAlreadyExistsException::class.java) {
            repository.publishDerivedFile(source, generation, expiresAt, stage, destination)
        }

        assertTrue(competitorCreated)
        assertArrayEquals(competingContent, destination.readBytes())
        assertArrayEquals(content, stage.readBytes())
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(generation, repository.currentAnalysisGeneration())
    }

    private fun stage(name: String) = File(workspace, "$name.pending").apply { writeBytes(content) }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-17T03:00:00Z")) : Clock() {
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
