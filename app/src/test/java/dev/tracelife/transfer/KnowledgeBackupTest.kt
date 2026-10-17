package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.analysis.AnalysisCall
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRunState
import dev.tracelife.analysis.BatchProposal
import dev.tracelife.analysis.CallPurpose
import dev.tracelife.analysis.CallStatus
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ClaimRef
import dev.tracelife.analysis.ClaimReview
import dev.tracelife.analysis.EvidenceReceipt
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.KnowledgeHead
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.StoredAnalysisRun
import dev.tracelife.storage.StoredKnowledgeRevision
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** All records, publications, passwords and model responses are synthetic; no network is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KnowledgeBackupTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var databaseName: String
    private lateinit var workspace: File
    private val now = Instant.parse("2026-10-17T04:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val password = "synthetic-knowledge-backup-password".toCharArray()
    private var sequence = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "synthetic-knowledge-backup-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, databaseName)
        workspace = File(context.cacheDir, "synthetic-knowledge-backup-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
        workspace.deleteRecursively()
    }

    @Test fun `immutable publication codec preserves all evidence and rejects permissive JSON shapes`() {
        val value = fixture()
        val payload = KnowledgeCodec.encode(value)
        assertEquals(value, KnowledgeCodec.decode(payload))
        listOf(
            payload.replace("\"revision\":1", "\"revision\":1,\"revision\":1"),
            payload.replace("\"revision\":1", "\"revision\":\"1\""),
            payload.replace("\"revision\":1", "\"revision\":1.0"),
            payload.replace("\"includeArchived\":false", "\"includeArchived\":0"),
            payload.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            payload.dropLast(1) + ",\"unknown\":true}",
            payload + "{}",
            JSONObject(payload).apply { getJSONObject("review").put("untrustedFact", "synthetic") }.toString(),
            JSONObject(payload).put("topics", JSONArray(listOf("learning", "learning"))).toString(),
            JSONObject(payload).put("revision", Long.MAX_VALUE).toString(),
        ).forEach { invalid -> assertThrows(Exception::class.java) { KnowledgeCodec.decode(invalid) } }
    }

    @Test fun `publication codec refuses oversized UTF8 and excessively nested JSON before accepting data`() {
        val payload = KnowledgeCodec.encode(fixture())
        assertThrows(IllegalArgumentException::class.java) { KnowledgeCodec.decode(payload + " ".repeat(256 * 1024)) }
        val huge = JSONObject(payload).put("unknown", "合".repeat(90_000)).toString()
        assertTrue(huge.length < 256 * 1024)
        assertThrows(IllegalArgumentException::class.java) { KnowledgeCodec.decode(huge) }
        val nested = "[".repeat(14) + "0" + "]".repeat(14)
        assertThrows(IllegalArgumentException::class.java) { KnowledgeCodec.decode(nested) }
    }

    @Test fun `encrypted backup preserves publication history heads and deletion without requiring parent task`() {
        val first = fixture()
        val second = first.copy(revision = 2, topics = setOf("learning", "practice"))
        val files = directory()
        val snapshot = repository.captureSnapshot(files).copy(analysisRuns = emptyList(),
            knowledgeRevisions = listOf(first, second), knowledgeHeads = listOf(KnowledgeHead(first.id, 2)),
            deletedKnowledge = listOf(Deletion("b".repeat(64), 3, now)))
        val archive = File(workspace, "synthetic-history.tlb")
        BackupArchive.write(snapshot, files, archive, password)
        val restored = BackupArchive.read(archive, directory(), password)
        assertEquals(snapshot, restored)
        assertFalse(archive.readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-review-provider"))
        val targetName = "synthetic-knowledge-target-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, targetName).use { target ->
                target.restoreSnapshot(restored, directory(), target.currentGeneration())
                assertEquals(second, target.knowledgeRevision(first.id))
                assertEquals(listOf(second, first), target.knowledgeHistory(first.id))
                assertEquals(snapshot.deletedKnowledge, target.captureSnapshot(directory()).deletedKnowledge)
                assertNotEquals(first.source, target.analysisSourceStamp())
            }
        } finally { context.deleteDatabase(targetName) }
    }

    @Test fun `version three remains readable and cannot carry version four knowledge fields`() {
        fixture()
        val snapshot = repository.captureSnapshot(directory())
        val old = json(snapshot).apply {
            put("formatVersion", 3); put("sourceSchemaVersion", 4)
            remove("knowledgeRevisions"); remove("knowledgeHeads"); remove("deletedKnowledge")
            remove("actionRevisions"); remove("actionHeads"); remove("deletedActions")
        }
        assertEquals(snapshot.copy(sourceSchemaVersion = 4), SnapshotJsonCodec.read(old.toString().byteInputStream()))
        old.put("knowledgeRevisions", JSONArray())
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(old.toString().byteInputStream()) }
        listOf("knowledgeRevisions", "knowledgeHeads", "deletedKnowledge").forEach { missing ->
            val incomplete = json(snapshot).apply { remove(missing) }
            assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(incomplete.toString().byteInputStream()) }
        }
        val mixed = json(snapshot).put("sourceSchemaVersion", 4)
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(mixed.toString().byteInputStream()) }
    }

    @Test fun `backup rejects absent changed and out of range original evidence without creating archive`() {
        val value = fixture()
        val snapshot = repository.captureSnapshot(directory()).copy(analysisRuns = emptyList(),
            knowledgeRevisions = listOf(value), knowledgeHeads = listOf(KnowledgeHead(value.id, 1)))
        val source = snapshot.records.single()
        val outsideScope = source.copy(draft = source.draft.copy(occurrence = Occurrence(LocalDate.parse("2025-01-01"), zoneId = "UTC")))
        listOf(
            snapshot.copy(records = emptyList(), changes = emptyList()),
            snapshot.copy(records = listOf(source.copy(draft = source.draft.copy(body = "合成篡改正文")))),
            snapshot.copy(records = listOf(outsideScope)),
        ).forEach(::rejectBackup)
    }

    @Test fun `backup rejects missing duplicate and older heads incomplete revisions and deletion conflicts`() {
        val value = fixture()
        val head = KnowledgeHead(value.id, 1)
        val snapshot = repository.captureSnapshot(directory()).copy(knowledgeRevisions = listOf(value), knowledgeHeads = listOf(head))
        listOf(
            snapshot.copy(knowledgeHeads = emptyList()),
            snapshot.copy(knowledgeHeads = listOf(head, head)),
            snapshot.copy(knowledgeRevisions = listOf(value, value.copy(revision = 2))),
            snapshot.copy(knowledgeRevisions = listOf(value.copy(revision = 2)), knowledgeHeads = listOf(head.copy(revision = 2))),
            snapshot.copy(deletedKnowledge = listOf(Deletion(value.id, 1, now))),
            snapshot.copy(deletedKnowledge = listOf(Deletion("invalid", 1, now))),
            snapshot.copy(deletedKnowledge = List(2) { Deletion("c".repeat(64), 1, now) }),
        ).forEach(::rejectBackup)
    }

    @Test fun `aggregate publication bytes are bounded before writing and while reading snapshots`() {
        val base = fixture()
        val unknowns = List(20) { "合".repeat(2_000) }
        val large = base.copy(review = base.review.copy(unknowns = unknowns))
        val values = List(150) { index ->
            val runId = "synthetic-size-$index"
            large.copy(id = KnowledgeValidation.idFor(runId, large.claimRef), runId = runId)
        }
        assertTrue(values.sumOf { KnowledgeCodec.encode(it).toByteArray(Charsets.UTF_8).size.toLong() } > KnowledgeValidation.MAX_BYTES)
        val snapshot = repository.captureSnapshot(directory())
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotJsonCodec.write(snapshot.copy(knowledgeRevisions = values, knowledgeHeads = values.map { KnowledgeHead(it.id, 1) }), output)
        }
        assertEquals(0, output.size())
        val incoming = json(snapshot).put("knowledgeRevisions", JSONArray(values.map(KnowledgeCodec::encode)))
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(incoming.toString().byteInputStream()) }
    }

    @Test fun `backup rejects knowledge retained alongside permanently erased task or feedback`() {
        val value = fixture()
        val snapshot = repository.captureSnapshot(directory()).copy(analysisRuns = emptyList(),
            knowledgeRevisions = listOf(value), knowledgeHeads = listOf(KnowledgeHead(value.id, 1)))
        rejectBackup(snapshot.copy(deletedAnalysisRuns = listOf(Deletion(value.runId, 1, now))))
        val feedbackId = AnalysisRunCodec.feedbackId(value.runId, value.claimRef)
        rejectBackup(snapshot.copy(deletedClaimFeedback = listOf(Deletion(feedbackId, 1, now))))
    }

    @Test fun `publication and deletion invalidate restore previews without changing source generation`() {
        val value = fixture()
        val transfer = DataTransfer(context, repository)
        val archive = File(workspace, "synthetic-preview.tlb")
        transfer.exportBackup(archive, password)
        val preview = transfer.previewBackup(archive, password)
        val generation = repository.currentGeneration()
        repository.publishKnowledge(value, null, repository.analysisRun(value.runId)!!.version)
        assertEquals(generation, repository.currentGeneration())
        assertThrows(StaleSnapshotException::class.java) { transfer.restoreBackup(archive, password, preview, true) }
        val withKnowledge = File(workspace, "synthetic-published.tlb")
        transfer.exportBackup(withKnowledge, password)
        val publishedPreview = transfer.previewBackup(withKnowledge, password)
        assertEquals(1, publishedPreview.knowledge)
        assertTrue(publishedPreview.targetHasData)
        repository.deleteKnowledge(value.id, 1)
        assertThrows(StaleSnapshotException::class.java) { transfer.restoreBackup(withKnowledge, password, publishedPreview, true) }
    }

    private fun fixture(): StoredKnowledgeRevision {
        val record = repository.createRecord(RecordDraft("合成记录：今天完成一次学习练习。",
            Occurrence(LocalDate.parse("2026-10-17"), zoneId = "Asia/Shanghai"), setOf("learning")), "synthetic-source")
        val text = record.draft.body
        val count = text.codePointCount(0, text.length)
        val hash = AnalysisRunCodec.sha256(text)
        val receipt = EvidenceReceipt(AnalysisRunCodec.evidenceId(record.id, 1, "body", 0, count, hash), record.id, 1, "body", 0, count, count, hash)
        val claim = ProposedClaim("合成结论：用户自述今天完成一次练习。", listOf(receipt.id), ClaimKind.REPORTED_EXPERIENCE)
        val ref = ClaimRef("synthetic-extraction", 0, AnalysisRunCodec.claimSha256(claim))
        val extraction = AnalysisCall(ref.batchKey, 1, "synthetic-extraction-provider", "1".repeat(64), 100, 100,
            listOf(receipt), now, CallStatus.SUCCEEDED, now, proposal = BatchProposal(listOf(claim), listOf("合成缺口：未评价掌握程度")))
        val review = extraction.copy(batchKey = "synthetic-review", providerId = "synthetic-review-provider", proposal = null,
            purpose = CallPurpose.REVIEW, reviewTarget = ref, review = ClaimReview(ReviewVerdict.SUPPORTED, listOf(receipt.id)))
        val scope = AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai")
        val run = StoredAnalysisRun("synthetic-analysis", 1, repository.analysisSourceStamp(), now, now, null,
            AnalysisRunCodec.encode(AnalysisRunState(AnalysisRequest("合成问题：学习记录说明什么", scope), listOf(extraction, review))))
        repository.saveAnalysisRun(run, null)
        return KnowledgeValidation.fromRun(run, ref, review.batchKey, KnowledgeCategory.OBSERVATION, setOf("learning"), 1, now, now)
    }

    private fun rejectBackup(snapshot: RepositorySnapshot) {
        val destination = File(workspace, "invalid-${sequence++}.tlb")
        assertThrows(InvalidBackupException::class.java) { BackupArchive.write(snapshot, directory(), destination, password) }
        assertFalse(destination.exists())
    }
    private fun directory(): File = File(workspace, "files-${sequence++}").apply { mkdir() }
    private fun json(snapshot: RepositorySnapshot): JSONObject = JSONObject(ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toString(Charsets.UTF_8.name()))
}
