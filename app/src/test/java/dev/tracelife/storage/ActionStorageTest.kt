package dev.tracelife.storage

import android.content.Context
import dev.tracelife.actions.*
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.transfer.Deletion
import dev.tracelife.transfer.InvalidBackupException
import dev.tracelife.transfer.RepositorySnapshot
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Complete user action chains are synthetic and restored through the real SQLite repository. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActionStorageTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var actions: ActionExperiments
    private lateinit var workspace: File
    private val name = "synthetic-action-storage-${UUID.randomUUID()}.db"
    private val now = Instant.parse("2026-10-17T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-17")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        actions = ActionExperiments(repository, clock)
        workspace = File(context.cacheDir, "synthetic-action-transfer-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name); workspace.deleteRecursively() }

    @Test fun `old backups preserve the whole local stopped chain even over higher imported revisions`() {
        actions.create(draft(), "action")
        val old = snapshot()
        val local = actions.stop("action", 1, "local-stop", "合成用户本地决定：停止。").value
        val importedTwo = old.actionRevisions.single().copy(revision = 2, draft = draft().copy(title = "合成备份第二版"), updatedAt = now.plusSeconds(1))
        val importedThree = importedTwo.copy(revision = 3, draft = draft().copy(title = "合成备份第三版"), updatedAt = now.plusSeconds(2))
        val higher = old.copy(actionRevisions = old.actionRevisions + importedTwo + importedThree, actionHeads = listOf(ActionHead("action", 3)))

        restore(higher)

        assertEquals(local, actions.get("action")!!.value)
        assertEquals(listOf(local, old.actionRevisions.single()), actions.history("action").map { it.value })
        restore(old)
        assertEquals(local, actions.get("action")!!.value)
    }

    @Test fun `deletions from either side win over backup and local actions without resurrecting identity`() {
        actions.create(draft(), "deleted-local")
        actions.create(draft(), "deleted-incoming")
        val old = snapshot()
        actions.delete("deleted-local", 1)
        val incoming = old.copy(actionRevisions = old.actionRevisions.filterNot { it.id == "deleted-incoming" },
            actionHeads = old.actionHeads.filterNot { it.id == "deleted-incoming" },
            deletedActions = listOf(Deletion("deleted-incoming", 1, now)))

        restore(incoming)
        assertTrue(repository.actionHeads().isEmpty())
        assertEquals(setOf("deleted-local", "deleted-incoming"), snapshot().deletedActions.map { it.id }.toSet())
        restore(old)
        assertTrue(repository.actionHeads().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { actions.create(draft(), "deleted-local") }
        assertThrows(IllegalArgumentException::class.java) { actions.create(draft(), "deleted-incoming") }
    }

    @Test fun `restoring before actions existed preserves later user plans and orphan origins`() {
        val before = snapshot()
        val value = StoredActionRevision("orphan", 1, draft().copy(origin = ActionOrigin("a".repeat(64), 1)), ActionStatus.OPEN, now, now)
        repository.restoreSnapshot(before.copy(actionRevisions = listOf(value), actionHeads = listOf(ActionHead(value.id, 1))),
            directory(), repository.currentGeneration())
        assertEquals(ActionOriginState.UNKNOWN, actions.get("orphan")!!.originState)
        restore(before)
        assertEquals(value, actions.get("orphan")!!.value)
        assertEquals(ActionOriginState.UNKNOWN, actions.get("orphan")!!.originState)
    }

    @Test fun `source deletion and replacement leave independent review results intact`() {
        repository.createRecord(RecordDraft("合成后来删除的原始资料。", Occurrence(day)), "source")
        actions.create(draft(), "action")
        val old = snapshot()
        val reviewed = actions.review("action", 1, "user-review",
            ActionReview(ActionExecution.UNKNOWN, "合成独立用户文字：资料不足，继续观察。", ActionDecision.DEFER, day), day.plusDays(1)).value
        repository.deleteRecord("source", 1)

        restore(old)

        assertNull(repository.record("source"))
        assertEquals(reviewed, actions.get("action")!!.value)
        assertEquals("合成独立用户文字：资料不足，继续观察。", actions.get("action")!!.value.review!!.observedText)
    }

    @Test fun `combined restore capacity is checked before either store state or user chain changes`() {
        val empty = snapshot()
        val large = draft().copy(trigger = "合".repeat(2_000), behavior = "合".repeat(4_000), expectedSignal = "合".repeat(2_000))
        fun rows(prefix: String) = List(400) { StoredActionRevision("$prefix-$it", 1, large, ActionStatus.OPEN, now, now) }
        val local = rows("local")
        val incoming = rows("incoming")
        repository.restoreSnapshot(empty.copy(actionRevisions = local, actionHeads = local.map { ActionHead(it.id, 1) }), directory(), repository.currentGeneration())
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()

        assertThrows(IllegalArgumentException::class.java) {
            repository.restoreSnapshot(empty.copy(actionRevisions = incoming, actionHeads = incoming.map { ActionHead(it.id, 1) }),
                directory(), source.generation, true, generation)
        }

        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(generation, repository.currentAnalysisGeneration())
        assertEquals(local.toSet(), snapshot().actionRevisions.toSet())
        assertEquals(400, actions.list().total)
    }

    @Test fun `invalid incoming review history is rejected before local content can be replaced`() {
        val initial = actions.create(draft(), "action").value
        val before = snapshot()
        val incompatible = initial.copy(revision = 2, draft = draft().copy(reviewOn = day.plusDays(1)),
            operationId = "review", review = ActionReview(ActionExecution.EXECUTED, "合成观察。", ActionDecision.ADJUST, day),
            reviewedDraft = draft().copy(behavior = "合成错误的旧计划。"))
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        assertThrows(InvalidBackupException::class.java) {
            restore(before.copy(actionRevisions = listOf(initial, incompatible), actionHeads = listOf(ActionHead("action", 2))))
        }
        assertEquals(initial, actions.get("action")!!.value)
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(generation, repository.currentAnalysisGeneration())
    }

    @Test fun `action changes invalidate captured export and restore tokens without staling source tasks`() {
        val before = snapshot()
        val analysis = ControlledAnalysis(repository, clock)
        val run = analysis.start(AnalysisRequest("合成资料分析", AnalysisQuery(day, day, "Asia/Shanghai")), "run")
        val token = snapshot()
        actions.create(draft(), "action")
        val stage = File(workspace, "export.pending").apply { writeText("synthetic complete bytes") }
        val destination = File(workspace, "export.json")
        assertThrows(StaleSnapshotException::class.java) { repository.publishSnapshot(token, stage, destination) }
        assertThrows(StaleSnapshotException::class.java) {
            repository.restoreSnapshot(before, directory(), token.generation, true, token.analysisGeneration)
        }
        assertEquals(run.stored.source, repository.analysisSourceStamp())
        assertEquals(run.status, analysis.task("run").status)
        assertFalse(destination.exists())
        assertTrue(stage.exists())
    }

    @Test fun `version five migrates to current schema while preserving records tasks identities and generations`() {
        val record = repository.createRecord(RecordDraft("合成旧版本原文。", Occurrence(day)), "source")
        val run = ControlledAnalysis(repository, clock).start(AnalysisRequest("合成迁移分析", AnalysisQuery(day, day, "Asia/Shanghai")), "run")
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        repository.close()
        LifeDatabase(context, name).use { helper -> helper.writableDatabase.apply {
            dev.tracelife.storage.removeVersionNineForMigration(this)
            execSQL("DROP TABLE dashboard_preferences")
            execSQL("ALTER TABLE store_state DROP COLUMN restore_epoch")
            execSQL("DROP TABLE profile_heads")
            execSQL("DROP TABLE profile_revisions")
            execSQL("DROP TABLE deleted_profiles")
            execSQL("DROP TABLE action_heads")
            execSQL("DROP TABLE action_revisions")
            execSQL("DROP TABLE deleted_actions")
            version = 5
        } }
        repository = LifeRepository(context, clock, name)
        actions = ActionExperiments(repository, clock)
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(generation, repository.currentAnalysisGeneration())
        assertEquals(record, repository.record(record.id))
        assertEquals(run.stored, repository.analysisRun("run"))
        assertEquals(9, snapshot().sourceSchemaVersion)
        assertTrue(repository.actionHeads().isEmpty())
        actions.create(draft(), "after-upgrade")
        assertEquals(source, repository.analysisSourceStamp())
        assertTrue(repository.currentAnalysisGeneration() > generation)
    }

    private fun snapshot() = repository.captureSnapshot(directory())
    private fun restore(snapshot: RepositorySnapshot) = repository.restoreSnapshot(snapshot, directory(), repository.currentGeneration(), true,
        repository.currentAnalysisGeneration())
    private fun directory() = File(workspace, UUID.randomUUID().toString()).apply { check(mkdir()) }
    private fun draft() = ActionDraft("合成行动", "合成触发", "合成用户选择的做法", "合成可观察信号", day, "Asia/Shanghai")
}
