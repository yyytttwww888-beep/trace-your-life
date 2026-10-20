package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.actions.*
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SnapshotValidation
import org.json.JSONArray
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
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile

/** Synthetic plans, reviews and passphrases only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActionTransferTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private val time = Instant.parse("2026-10-17T06:00:00Z")
    private val date = LocalDate.parse("2026-10-17")
    private val clock = Clock.fixed(time, ZoneOffset.UTC)
    private val password = "synthetic-action-backup-passphrase".toCharArray()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        directory = File(context.cacheDir, "synthetic-actions-transfer-${UUID.randomUUID()}").apply { check(mkdir()) }
    }
    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun `logical and encrypted backup preserve full action chains independent of origin availability`() {
        val first = action("synthetic-action", origin = ActionOrigin("a".repeat(64), 1))
        val second = first.copy(revision = 2, draft = first.draft.copy(reviewOn = date.plusDays(1)),
            operationId = "synthetic-review", review = ActionReview(ActionExecution.UNKNOWN, "合成回看：尚不确定是否执行", ActionDecision.DEFER, date),
            reviewedDraft = first.draft)
        val snapshot = snapshot(listOf(first, second), listOf(Deletion("synthetic-deleted-action", 2, time)))
        val bytes = encode(snapshot)
        val encoded = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(10, encoded.getInt("formatVersion"))
        assertEquals(11, encoded.getInt("sourceSchemaVersion"))
        assertEquals(snapshot, SnapshotJsonCodec.read(bytes.inputStream()))
        val attachments = File(directory, "attachments").apply { check(mkdir()) }
        val destination = File(directory, "actions.tlb")
        BackupArchive.write(snapshot, attachments, destination, password)
        assertEquals(snapshot, BackupArchive.read(destination, File(directory, "restored"), password))
    }

    @Test fun `legacy formats omit action fields and mixed versions or malformed action payloads are rejected`() {
        val current = snapshot(emptyList())
        (1..4).forEach { version ->
            val json = JSONObject(encode(current).toString(Charsets.UTF_8)).apply {
                put("formatVersion", version); put("sourceSchemaVersion", version + 1); remove("dashboardPreferences"); remove("historicalComparisons"); remove("historicalComparisonDeletions"); remove("experienceThreads"); remove("experienceChanges"); remove("deletedExperienceThreads"); remove("conversations"); remove("conversationTurns"); remove("deletedConversations")
                remove("actionRevisions"); remove("actionHeads"); remove("deletedActions")
                remove("profileRevisions"); remove("profileHeads"); remove("deletedProfiles")
                if (version < 4) { remove("knowledgeRevisions"); remove("knowledgeHeads"); remove("deletedKnowledge") }
                if (version < 3) { remove("claimFeedback"); remove("deletedClaimFeedback") }
                if (version < 2) { remove("analysisRuns"); remove("analysisGeneration"); remove("deletedAnalysisRuns") }
            }
            assertEquals(current.copy(sourceSchemaVersion = version + 1), SnapshotJsonCodec.read(json.toString().byteInputStream()))
            json.put("actionRevisions", JSONArray())
            assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(json.toString().byteInputStream()) }
        }
        val valid = JSONObject(encode(snapshot(listOf(action("synthetic-action")))).toString(Charsets.UTF_8))
        val missing = JSONObject(valid.toString()).apply { remove("deletedActions") }
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(missing.toString().byteInputStream()) }
        val extra = JSONObject(valid.toString()).apply {
            val payload = JSONObject(getJSONArray("actionRevisions").getString(0)).put("unexpected", "synthetic")
            put("actionRevisions", JSONArray().put(payload.toString()))
        }
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(extra.toString().byteInputStream()) }
        val oversized = JSONObject(valid.toString()).put("actionRevisions", JSONArray().put("x".repeat(ActionValidation.MAX_REVISION_BYTES + 1)))
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(oversized.toString().byteInputStream()) }
    }

    @Test fun `invalid action chains or simultaneous tombstones fail before backup output`() {
        val first = action("synthetic-action")
        val valid = snapshot(listOf(first))
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(valid.copy(
            deletedActions = listOf(Deletion(first.id, first.revision, time)))) }
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(valid.copy(
            actionHeads = listOf(ActionHead(first.id, 2)))) }
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(valid.copy(sourceSchemaVersion = 5)) }
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { SnapshotJsonCodec.write(valid.copy(actionRevisions = emptyList()), output) }
        assertEquals(0, output.size())
    }

    @Test fun `reading package routes only current plans and never copies review or replaced plan text`() {
        val first = action("synthetic-adjusted", title = "NEVER_EXPORT_REVIEWED_PLAN")
        val next = first.copy(revision = 2, draft = first.draft.copy(title = "合成新计划", reviewOn = date.plusDays(1)),
            operationId = "synthetic-adjust", review = ActionReview(ActionExecution.EXECUTED, "NEVER_EXPORT_REVIEW_TEXT", ActionDecision.ADJUST, date),
            reviewedDraft = first.draft)
        val other = action("synthetic-other", title = "合成另一个计划", reviewOn = date.plusDays(1))
        val outside = action("synthetic-outside", title = "NEVER_EXPORT_OUTSIDE_DATE", reviewOn = date.plusDays(2))
        val source = snapshot(listOf(first, next, other, outside))
        val entries = reading(source, ReadingExportOptions(scope = ExportScope(from = date.plusDays(1), through = date.plusDays(1)), pageSize = 1))
        assertTrue(entries.keys.any { it == "actions/all/page-002.md" })
        assertTrue(entries.keys.any { it.startsWith("indexes/action-ids/") && it.endsWith(".jsonl") })
        assertFalse(entries.values.any { "NEVER_EXPORT" in it })
        val manifest = JSONObject(entries.getValue("manifest.json"))
        assertEquals(2, manifest.getJSONObject("actions").getInt("included"))
        assertEquals(1, manifest.getJSONObject("actions").getJSONObject("omittedByReason").getInt("review_date"))
        assertFalse(manifest.getJSONObject("actions").getBoolean("executionEvidence"))
        assertFalse(manifest.getJSONObject("completeness").getBoolean("actionReviewsIncluded"))
        val jsonRows = entries.filterKeys { it.startsWith("data/actions/") && it.endsWith(".jsonl") }.values
            .flatMap { it.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList() }
        assertEquals(setOf("synthetic-adjusted", "synthetic-other"), jsonRows.map { it.getString("id") }.toSet())
        assertTrue(jsonRows.all { !it.has("review") && !it.has("reviewedDraft") && !it.getBoolean("originalEvidence") })
        entries.filterKeys { it.endsWith(".md") }.forEach { (sourcePath, text) ->
            Regex("\\[[^]]*]\\(([^)]+)\\)").findAll(text).forEach {
                val target = (Paths.get(sourcePath).parent ?: Paths.get("")).resolve(it.groupValues[1]).normalize().toString()
                assertTrue("$sourcePath -> $target", target in entries)
            }
        }
    }

    @Test fun `evidence and topic packages omit all action text explicitly`() {
        val source = snapshot(listOf(action("synthetic-action", title = "NEVER_EXPORT_ACTION_TITLE")))
        listOf(ReadingExportOptions(kind = ReadingPackageKind.EVIDENCE, question = "合成问题"),
            ReadingExportOptions(scope = ExportScope(topics = setOf("work")))).forEach { options ->
            val entries = reading(source, options)
            assertFalse(entries.keys.any { it.startsWith("actions/") || it.startsWith("data/actions/") || it.startsWith("indexes/action-ids/") })
            assertFalse(entries.values.any { "NEVER_EXPORT_ACTION_TITLE" in it })
            val metadata = JSONObject(entries.getValue("manifest.json")).getJSONObject("actions")
            assertEquals(0, metadata.getInt("included"))
            assertEquals(1, metadata.getInt("omitted"))
        }
    }

    @Test fun `action only target is populated and action edits invalidate backup preview`() {
        val sourceName = "synthetic-action-source-${UUID.randomUUID()}.db"
        val targetName = "synthetic-action-target-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, sourceName).use { source -> LifeRepository(context, clock, targetName).use { target ->
                ActionExperiments(source, clock).create(action("unused").draft, "synthetic-source")
                val destination = File(directory, "source.tlb")
                DataTransfer(context, source, clock).exportBackup(destination, password)
                val local = ActionExperiments(target, clock).create(action("unused").draft, "synthetic-local").value
                val transfer = DataTransfer(context, target, clock)
                val preview = transfer.previewBackup(destination, password)
                assertEquals(1, preview.actions)
                assertTrue(preview.targetHasData)
                assertThrows(IllegalStateException::class.java) { transfer.restoreBackup(destination, password, preview) }
                ActionExperiments(target, clock).update(local.id, local.revision, local.draft.copy(title = "合成后来修改"))
                assertThrows(StaleSnapshotException::class.java) { transfer.restoreBackup(destination, password, preview, replaceExisting = true) }
            } }
        } finally { context.deleteDatabase(sourceName); context.deleteDatabase(targetName) }
    }

    private fun action(id: String, title: String = "合成学习实验", reviewOn: LocalDate = date,
        origin: ActionOrigin? = null) = StoredActionRevision(id, 1,
        ActionDraft(title, "合成情境：开始学习时", "合成行为：记录一个问题", "合成信号：能写出下一步", reviewOn, "Asia/Shanghai", origin),
        ActionStatus.OPEN, time, time)
    private fun snapshot(actions: List<StoredActionRevision>, deleted: List<Deletion> = emptyList()) = RepositorySnapshot(
        "synthetic-snapshot", "synthetic-store", 0, time, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        actionRevisions = actions, actionHeads = actions.groupBy { it.id }.map { (id, values) -> ActionHead(id, values.maxOf { it.revision }) },
        deletedActions = deleted,
    )
    private fun encode(snapshot: RepositorySnapshot) = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toByteArray()
    private fun reading(snapshot: RepositorySnapshot, options: ReadingExportOptions): Map<String, String> {
        val destination = File(directory, "reading-${UUID.randomUUID()}.zip")
        val attachments = File(directory, "attachments-${UUID.randomUUID()}").apply { check(mkdir()) }
        KnowledgeArchive.write(snapshot, attachments, destination, options)
        return ZipFile(destination).use { zip -> zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() }
        } }
    }
}
