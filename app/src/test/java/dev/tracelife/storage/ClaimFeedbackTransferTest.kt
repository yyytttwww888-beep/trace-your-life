package dev.tracelife.storage

import android.content.Context
import dev.tracelife.analysis.AnalysisProvider
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRunState
import dev.tracelife.analysis.BatchProposal
import dev.tracelife.analysis.CallPurpose
import dev.tracelife.analysis.CallStatus
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ClaimRef
import dev.tracelife.analysis.ClaimReview
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.analysis.EvidenceSelection
import dev.tracelife.analysis.ModelRequest
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.model.RecordDraft
import dev.tracelife.transfer.BackupArchive
import dev.tracelife.transfer.DataTransfer
import dev.tracelife.transfer.Deletion
import dev.tracelife.transfer.InvalidBackupException
import dev.tracelife.transfer.KnowledgeArchive
import dev.tracelife.transfer.ReadingExportOptions
import dev.tracelife.transfer.RepositorySnapshot
import dev.tracelife.transfer.SnapshotJsonCodec
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile

/** Portable user-feedback boundaries, with synthetic data and no network or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClaimFeedbackTransferTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var name: String
    private lateinit var workspace: File
    private val now = Instant.parse("2026-10-17T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val password = "synthetic-feedback-backup-passphrase".toCharArray()
    private var sequence = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        name = "synthetic-feedback-transfer-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, name)
        workspace = File(context.cacheDir, "synthetic-feedback-transfer-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(name)
        workspace.deleteRecursively()
    }

    @Test fun `encrypted backup roundtrip retains feedback independent text and permanent deletion markers`() {
        val value = fixture()
        val files = directory()
        val snapshot = repository.captureSnapshot(files).copy(deletedClaimFeedback = listOf(Deletion("b".repeat(64), 7, now)))
        val archive = File(workspace, "synthetic-feedback.tlb")
        BackupArchive.write(snapshot, files, archive, password)
        val restored = BackupArchive.read(archive, directory(), password)
        assertEquals(snapshot, restored)
        assertFalse(archive.readBytes().toString(Charsets.ISO_8859_1).contains(value.userText!!))
        val targetName = "synthetic-target-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, targetName).use { target ->
                target.restoreSnapshot(restored, directory(), target.currentGeneration())
                assertEquals(value, target.claimFeedback(value.id))
                assertEquals(8, target.claimFeedbackVersion("b".repeat(64)))
                assertNotEquals(value.source, target.analysisSourceStamp())
            }
        } finally { context.deleteDatabase(targetName) }
    }

    @Test fun `logical version two is readable but cannot carry feedback fields or mixed schema versions`() {
        fixture()
        val snapshot = repository.captureSnapshot(directory())
        val legacy = json(snapshot).apply {
            put("formatVersion", 2); put("sourceSchemaVersion", 3)
            remove("claimFeedback"); remove("deletedClaimFeedback")
            remove("knowledgeRevisions"); remove("knowledgeHeads"); remove("deletedKnowledge")
        }
        val expected = snapshot.copy(sourceSchemaVersion = 3, claimFeedback = emptyList(), deletedClaimFeedback = emptyList())
        assertEquals(expected, SnapshotJsonCodec.read(legacy.toString().byteInputStream()))
        legacy.put("claimFeedback", org.json.JSONArray())
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(legacy.toString().byteInputStream()) }
        val missing = json(snapshot).apply { remove("deletedClaimFeedback") }
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(missing.toString().byteInputStream()) }
        val mixed = json(snapshot).put("sourceSchemaVersion", 3)
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(mixed.toString().byteInputStream()) }
    }

    @Test fun `backup rejects feedback identity mismatch duplicates and references to another saved claim`() {
        val value = fixture()
        val snapshot = repository.captureSnapshot(directory())
        val changedHash = "a".repeat(64)
        val fakeRef = dev.tracelife.analysis.ClaimRef(value.batchKey, 0, changedHash)
        val wrongTarget = value.copy(id = AnalysisRunCodec.feedbackId(value.runId, fakeRef), claimSha256 = changedHash)
        listOf(
            snapshot.copy(claimFeedback = listOf(value.copy(id = "c".repeat(64)))),
            snapshot.copy(claimFeedback = listOf(value, value)),
            snapshot.copy(claimFeedback = listOf(wrongTarget)),
            snapshot.copy(deletedClaimFeedback = listOf(Deletion(value.id, 1, now))),
            snapshot.copy(deletedClaimFeedback = listOf(Deletion("invalid", 1, now))),
        ).forEach { invalid ->
            assertThrows(InvalidBackupException::class.java) { BackupArchive.write(invalid, directory(), File(workspace, "invalid-${sequence++}.tlb"), password) }
        }
        val unknown = json(snapshot)
        unknown.getJSONArray("claimFeedback").getJSONObject(0).put("modelWording", "synthetic forbidden field")
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(unknown.toString().byteInputStream()) }
    }

    @Test fun `user feedback changes invalidate portable restore preview and feedback only stores count as populated`() {
        val value = fixture()
        val transfer = DataTransfer(context, repository)
        val archive = File(workspace, "synthetic-preview.tlb")
        transfer.exportBackup(archive, password)
        val preview = transfer.previewBackup(archive, password)
        assertEquals(1, preview.claimFeedback)
        repository.saveClaimFeedback(value.copy(version = 2, decision = ClaimFeedbackDecision.REJECTED), 1, repository.analysisRun(value.runId)!!.version)
        assertThrows(StaleSnapshotException::class.java) { transfer.restoreBackup(archive, password, preview, true) }
        val beforeDelete = transfer.previewBackup(archive, password)
        repository.deleteClaimFeedback(value.id, 2)
        assertThrows(StaleSnapshotException::class.java) { transfer.restoreBackup(archive, password, beforeDelete, true) }
        // A different fixture database keeps just independent user text after source deletion.
        val targetName = "synthetic-feedback-only-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, targetName).use { target ->
                val snapshot = repository.captureSnapshot(directory()).copy(analysisRuns = emptyList(), records = emptyList(), changes = emptyList(),
                    claimFeedback = listOf(value), deletedClaimFeedback = emptyList())
                target.restoreSnapshot(snapshot, directory(), target.currentGeneration())
                val targetTransfer = DataTransfer(context, target)
                val targetPreview = targetTransfer.previewBackup(archive, password)
                assertTrue(targetPreview.targetHasData)
                assertThrows(IllegalStateException::class.java) { targetTransfer.restoreBackup(archive, password, targetPreview) }
            }
        } finally { context.deleteDatabase(targetName) }
    }

    @Test fun `reading package explicitly omits feedback and never exposes its independent text`() {
        val value = fixture()
        val snapshot = repository.captureSnapshot(directory())
        val output = File(workspace, "synthetic-reading.zip")
        KnowledgeArchive.write(snapshot, directory(), output, ReadingExportOptions())
        ZipFile(output).use { zip ->
            val completeness = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()).getJSONObject("completeness")
            assertFalse(completeness.getBoolean("claimFeedbackIncluded"))
            assertEquals(1, completeness.getInt("omittedClaimFeedback"))
            assertEquals("claim_feedback_not_included", completeness.getString("claimFeedbackGap"))
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                assertFalse(zip.getInputStream(entry).reader().readText().contains(value.userText!!))
            }
            assertTrue(zip.getInputStream(zip.getEntry("README.md")).reader().readText().contains("共省略 1 条"))
        }
    }

    @Test fun `deleting feedback erases echoed review wording and old backup restore cannot revive it`() {
        val value = fixture()
        val marker = requireNotNull(value.userText)
        val current = repository.analysisRun(value.runId)!!
        val state = AnalysisRunCodec.decode(current.payload)
        val extraction = state.calls.single()
        val ref = ClaimRef(value.batchKey, value.claimIndex, value.claimSha256)
        val review = extraction.copy(batchKey = "synthetic-review", purpose = CallPurpose.REVIEW, proposal = null,
            reviewTarget = ref, feedbackVersion = value.version,
            review = ClaimReview(ReviewVerdict.SUPPORTED, supportingEvidenceIds = extraction.evidence.map { it.id }, unknowns = listOf(marker)))
        repository.saveAnalysisRun(current.copy(version = current.version + 1,
            payload = AnalysisRunCodec.encode(state.copy(calls = state.calls + review))), current.version)
        val files = directory()
        val before = repository.captureSnapshot(files)
        assertTrue(before.analysisRuns.single().payload.contains(marker))
        repository.deleteClaimFeedback(value.id, value.version)
        fun assertErased() {
            val snapshot = repository.captureSnapshot(directory())
            assertTrue(snapshot.claimFeedback.isEmpty())
            val saved = AnalysisRunCodec.decode(snapshot.analysisRuns.single().payload).calls
            assertEquals(extraction, saved.first())
            assertEquals(CallStatus.FAILED, saved.last().status)
            assertEquals("FEEDBACK_DELETED", saved.last().failureCode)
            assertNull(saved.last().review)
            assertEquals(review.inputBytes, saved.last().inputBytes)
            assertFalse(json(snapshot).toString().contains(marker))
        }
        assertErased()
        repository.restoreSnapshot(before, files, repository.currentGeneration(), true)
        assertErased()
    }

    @Test fun `logical feedback format rejects oversize user text and exact aggregate byte overflow`() {
        val value = fixture()
        val snapshot = repository.captureSnapshot(directory())
        val oversized = json(snapshot)
        oversized.getJSONArray("claimFeedback").getJSONObject(0).put("userText", "x".repeat(2_001))
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(oversized.toString().byteInputStream()) }
        val many = (0 until 800).map { index ->
            val runId = "synthetic-removed-$index"
            val ref = dev.tracelife.analysis.ClaimRef(value.batchKey, 0, value.claimSha256)
            value.copy(id = AnalysisRunCodec.feedbackId(runId, ref), runId = runId, userText = "合".repeat(2_000))
        }
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { SnapshotJsonCodec.write(snapshot.copy(claimFeedback = many), output) }
        assertEquals(0, output.size())
        val incoming = json(snapshot)
        incoming.put("claimFeedback", org.json.JSONArray().apply {
            many.forEach { feedback -> put(json(snapshot.copy(claimFeedback = listOf(feedback))).getJSONArray("claimFeedback").getJSONObject(0)) }
        })
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(incoming.toString().byteInputStream()) }
    }

    @Test fun `same store restore always advances beyond imported source stamps and rejects overflow atomically`() {
        val base = repository.captureSnapshot(directory())
        val request = AnalysisRequest("合成问题", AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai"))
        val run = StoredAnalysisRun("synthetic-future-stamp", 1, SourceStamp(base.storeId, base.generation + 1), now, now, null,
            AnalysisRunCodec.encode(AnalysisRunState(request)))
        repository.restoreSnapshot(base.copy(analysisRuns = listOf(run)), directory(), repository.currentGeneration())
        assertTrue(repository.currentGeneration() > run.source.generation)
        assertEquals(dev.tracelife.analysis.AnalysisStatus.STALE, ControlledAnalysis(repository, clock).task(run.id).status)
        val before = repository.captureSnapshot(directory())
        assertThrows(IllegalArgumentException::class.java) {
            repository.restoreSnapshot(base.copy(generation = Long.MAX_VALUE), directory(), repository.currentGeneration(), true)
        }
        assertEquals(before.generation, repository.currentGeneration())
        assertEquals(before.analysisGeneration, repository.currentAnalysisGeneration())
        assertEquals(run, repository.analysisRun(run.id))
    }

    @Test fun `restoring a task before its extraction retains later independent feedback and other tasks`() {
        val value = fixture()
        val files = directory()
        val snapshot = repository.captureSnapshot(files)
        val run = snapshot.analysisRuns.single()
        val beforeExtraction = run.copy(version = 1, payload = AnalysisRunCodec.encode(AnalysisRunCodec.decode(run.payload).copy(calls = emptyList())))
        val other = beforeExtraction.copy(id = "synthetic-unrelated-task")
        val old = snapshot.copy(claimFeedback = emptyList(), analysisRuns = listOf(beforeExtraction, other))
        // The backup itself is structurally valid. Local user feedback references a later claim.
        repository.restoreSnapshot(old, files, repository.currentGeneration(), true)
        assertNull(repository.analysisRun(run.id))
        assertEquals(value, repository.claimFeedback(value.id))
        assertEquals(other, repository.analysisRun(other.id))
        assertEquals(listOf(value), repository.captureSnapshot(directory()).claimFeedback)
    }

    private fun fixture(): StoredClaimFeedback {
        repository.createRecord(RecordDraft("合成资料：今天完成一次学习练习。"), "synthetic-source")
        val analysis = ControlledAnalysis(repository, clock)
        val run = analysis.start(AnalysisRequest("合成问题：记录中的学习情况", AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai")), "synthetic-run")
        analysis.runBatch(run.stored.id, "synthetic-batch", listOf(EvidenceSelection("synthetic-source", "body")), object : AnalysisProvider {
            override val id = "synthetic-feedback-provider"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = request.payloadJson.length
            override fun complete(request: ModelRequest): BatchProposal {
                val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim("合成提案：用户自述完成一次练习。", listOf(id), ClaimKind.REPORTED_EXPERIENCE)))
            }
        })
        val task = analysis.task(run.stored.id)
        val ref = analysis.claimRef(task.stored.id, "synthetic-batch", 0)
        val value = StoredClaimFeedback(AnalysisRunCodec.feedbackId(task.stored.id, ref), 1, task.stored.id, ref.batchKey, ref.claimIndex,
            ref.claimSha256, task.stored.source, ClaimFeedbackDecision.CORRECTED, "合成用户独立文字标记：只完成了基础题，还没有掌握。", now, now)
        return repository.saveClaimFeedback(value, null, task.stored.version)
    }

    private fun json(snapshot: RepositorySnapshot) = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }
        .toString(Charsets.UTF_8.name()).let(::JSONObject)

    private fun directory() = File(workspace, "synthetic-${sequence++}").apply { check(mkdirs()) }
}
