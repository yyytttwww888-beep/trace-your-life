package dev.tracelife.storage

import android.content.Context
import dev.tracelife.analysis.AnalysisCall
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRunState
import dev.tracelife.analysis.BatchProposal
import dev.tracelife.analysis.CallStatus
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ClaimRef
import dev.tracelife.analysis.EvidenceReceipt
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
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

/** Synthetic notes, proposals and explicitly submitted independent user wording only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ClaimFeedbackStorageTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var name: String
    private lateinit var workspace: File
    private val now = Instant.parse("2026-10-17T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var sequence = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        name = "synthetic-feedback-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, name)
        workspace = File(context.cacheDir, "synthetic-feedback-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(name)
        workspace.deleteRecursively()
    }

    @Test fun `feedback persists with a separate CAS and advances task version but not source generation`() {
        val run = run()
        val generation = repository.currentGeneration()
        val analysisGeneration = repository.currentAnalysisGeneration()
        val value = feedback(run)
        repository.saveClaimFeedback(value, null, run.version)
        assertEquals(value, repository.claimFeedback(value.id))
        assertEquals(1, repository.claimFeedbackVersion(value.id))
        assertEquals(run.version + 1, repository.analysisRun(run.id)!!.version)
        assertEquals(run.payload, repository.analysisRun(run.id)!!.payload)
        assertEquals(generation, repository.currentGeneration())
        assertTrue(repository.currentAnalysisGeneration() > analysisGeneration)
        assertThrows(RevisionConflictException::class.java) { repository.saveAnalysisRun(run.copy(version = run.version + 1), run.version) }
        assertThrows(RevisionConflictException::class.java) { repository.saveClaimFeedback(value, null, run.version + 1) }
        val corrected = value.copy(version = 2, decision = ClaimFeedbackDecision.CORRECTED, userText = "合成用户独立文字：我需要恢复而非加班。")
        assertThrows(RevisionConflictException::class.java) { repository.saveClaimFeedback(corrected, 1, run.version) }
        repository.saveClaimFeedback(corrected, 1, run.version + 1)
        repository.close()
        repository = LifeRepository(context, clock, name)
        assertEquals(corrected, repository.claimFeedback(value.id))
        assertEquals(listOf(corrected), repository.claimFeedbacks())
    }

    @Test fun `current decisions bind the saved extraction hash and expected task version`() {
        val run = run()
        val value = feedback(run)
        assertThrows(RevisionConflictException::class.java) { repository.saveClaimFeedback(value, null, null) }
        val fakeRef = ClaimRef(value.batchKey, 0, "a".repeat(64))
        val fake = value.copy(id = AnalysisRunCodec.feedbackId(run.id, fakeRef), claimSha256 = fakeRef.claimSha256)
        assertThrows(IllegalArgumentException::class.java) { repository.saveClaimFeedback(fake, null, run.version) }
        assertThrows(IllegalArgumentException::class.java) { repository.saveClaimFeedback(value.copy(source = value.source.copy(generation = 0)), null, run.version) }
        assertThrows(IllegalArgumentException::class.java) { repository.saveClaimFeedback(value.copy(decision = ClaimFeedbackDecision.CORRECTED), null, run.version) }
        assertThrows(IllegalArgumentException::class.java) { repository.saveClaimFeedback(value.copy(userText = "x".repeat(2_001)), null, run.version) }
        assertTrue(repository.claimFeedbacks().isEmpty())
        assertEquals(run.version, repository.analysisRun(run.id)!!.version)
    }

    @Test fun `source revisions preserve independent wording while prohibiting new confirmations`() {
        val run = run()
        val value = feedback(run, ClaimFeedbackDecision.REJECTED, "合成用户独立文字：单次记录不能代表一贯表现。")
        repository.saveClaimFeedback(value, null, run.version)
        repository.updateRecord("synthetic-source", 1, RecordDraft("合成资料：已更正的源记录"))
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveClaimFeedback(value.copy(version = 2, decision = ClaimFeedbackDecision.CONFIRMED), 1, null)
        }
        val updated = value.copy(version = 2, userText = "合成用户独立文字：保留我的否定及补充说明。")
        repository.saveClaimFeedback(updated, 1, null)
        assertEquals(updated, repository.claimFeedback(value.id))
        assertEquals(value.source, repository.claimFeedback(value.id)!!.source)
    }

    @Test fun `source deletion purges model text while independent feedback and deletion fence survive`() {
        val run = run()
        val value = feedback(run, ClaimFeedbackDecision.CORRECTED, "合成用户独立文字：这是我后来表达的解释。")
        repository.saveClaimFeedback(value, null, run.version)
        repository.deleteRecord("synthetic-source", 1)
        assertNull(repository.analysisRun(run.id))
        assertEquals(value, repository.claimFeedback(value.id))
        val snapshot = repository.captureSnapshot(directory())
        assertTrue(snapshot.records.isEmpty())
        assertTrue(snapshot.analysisRuns.isEmpty())
        assertEquals(listOf(value), snapshot.claimFeedback)
        val withdrawn = value.copy(version = 2, decision = ClaimFeedbackDecision.WITHDRAWN)
        repository.saveClaimFeedback(withdrawn, 1, null)
        repository.deleteClaimFeedback(value.id, 2)
        assertNull(repository.claimFeedback(value.id))
        assertEquals(3, repository.claimFeedbackVersion(value.id))
        assertEquals(listOf(value.id), repository.captureSnapshot(directory()).deletedClaimFeedback.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { repository.saveClaimFeedback(value, null, null) }
    }

    @Test fun `confirmation never copies model wording into independent user text`() {
        val run = run()
        val value = feedback(run)
        repository.saveClaimFeedback(value, null, run.version)
        repository.deleteAnalysisRun(run.id, repository.analysisRun(run.id)!!.version)
        val snapshot = repository.captureSnapshot(directory())
        assertNull(snapshot.claimFeedback.single().userText)
        assertTrue(snapshot.analysisRuns.isEmpty())
        assertEquals(value, snapshot.claimFeedback.single())
    }

    @Test fun `expired tasks only permit existing independent wording rejection or withdrawal`() {
        val run = run(expiresAt = now.plusSeconds(1))
        val value = feedback(run)
        repository.saveClaimFeedback(value, null, run.version)
        repository.close()
        repository = LifeRepository(context, Clock.fixed(now.plusSeconds(2), ZoneOffset.UTC), name)
        val changed = value.copy(version = 2, decision = ClaimFeedbackDecision.CORRECTED, userText = "合成用户独立文字")
        assertThrows(IllegalArgumentException::class.java) { repository.saveClaimFeedback(changed, 1, null) }
        val rejected = value.copy(version = 2, decision = ClaimFeedbackDecision.REJECTED, updatedAt = now.plusSeconds(2))
        repository.saveClaimFeedback(rejected, 1, null)
        assertEquals(rejected, repository.claimFeedback(value.id))
    }

    @Test fun `feedback publication token and direct restore preview become stale after a user decision`() {
        val run = run()
        val snapshot = repository.captureSnapshot(directory())
        val stage = File(workspace, "synthetic-stage").apply { writeText("synthetic export") }
        repository.saveClaimFeedback(feedback(run), null, run.version)
        assertThrows(StaleSnapshotException::class.java) { repository.publishSnapshot(snapshot, stage, File(workspace, "synthetic-export")) }
        assertThrows(StaleSnapshotException::class.java) {
            repository.restoreSnapshot(snapshot, directory(), snapshot.generation, true, snapshot.analysisGeneration)
        }
        val current = repository.captureSnapshot(directory())
        repository.deleteClaimFeedback(feedback(run).id, 1)
        assertEquals(2, repository.claimFeedbackVersion(feedback(run).id))
        assertThrows(StaleSnapshotException::class.java) { repository.publishSnapshot(current, stage, File(workspace, "synthetic-export")) }
    }

    @Test fun `old backup cannot overwrite a newer local rejection or recover erased feedback`() {
        val run = run()
        val value = feedback(run)
        repository.saveClaimFeedback(value, null, run.version)
        val files = directory()
        val old = repository.captureSnapshot(files)
        val rejected = value.copy(version = 2, decision = ClaimFeedbackDecision.REJECTED, userText = "合成独立文字：不能据此推断。")
        repository.saveClaimFeedback(rejected, 1, repository.analysisRun(run.id)!!.version)
        // Even an imported higher version does not supersede present user authority.
        val misleading = old.copy(claimFeedback = listOf(value.copy(version = 100, updatedAt = now.plusSeconds(100))))
        repository.restoreSnapshot(misleading, files, repository.currentGeneration(), true)
        assertEquals(rejected, repository.claimFeedback(value.id))
        repository.deleteClaimFeedback(value.id, 2)
        repository.restoreSnapshot(old, files, repository.currentGeneration(), true)
        assertNull(repository.claimFeedback(value.id))
        assertEquals(3, repository.claimFeedbackVersion(value.id))
    }

    @Test fun `restoring a backup made before feedback preserves orphan independent wording`() {
        val run = run()
        val files = directory()
        val before = repository.captureSnapshot(files)
        val value = feedback(run, ClaimFeedbackDecision.CORRECTED, "合成用户独立文字：旧备份也不能抹掉这句话。")
        repository.saveClaimFeedback(value, null, run.version)
        repository.deleteRecord("synthetic-source", 1)
        repository.restoreSnapshot(before, files, repository.currentGeneration(), true)
        assertNull(repository.record("synthetic-source"))
        assertNull(repository.analysisRun(run.id))
        assertEquals(value, repository.claimFeedback(value.id))
    }

    @Test fun `restore rejects combined feedback over budget atomically instead of dropping user text`() {
        val empty = repository.captureSnapshot(directory())
        val local = List(400) { orphan(empty.storeId, "synthetic-local-$it") }
        val incoming = List(400) { orphan(empty.storeId, "synthetic-import-$it") }
        repository.restoreSnapshot(empty.copy(claimFeedback = local), directory(), repository.currentGeneration())
        val generation = repository.currentGeneration()
        val analysisGeneration = repository.currentAnalysisGeneration()
        assertThrows(IllegalArgumentException::class.java) {
            repository.restoreSnapshot(empty.copy(claimFeedback = incoming), directory(), generation, true, analysisGeneration)
        }
        assertEquals(generation, repository.currentGeneration())
        assertEquals(analysisGeneration, repository.currentAnalysisGeneration())
        assertEquals(local.toSet(), repository.captureSnapshot(directory()).claimFeedback.toSet())
        assertEquals(100, repository.claimFeedbacks(0, 100).size)
        assertEquals(50, repository.claimFeedbacks(350, 100).size)
        assertThrows(IllegalArgumentException::class.java) { repository.claimFeedbacks(0, 101) }
    }

    @Test fun `version three migration preserves tasks generations and records and adds empty feedback`() {
        val run = run()
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        repository.close()
        LifeDatabase(context, name).use { helper ->
            helper.writableDatabase.apply {
                execSQL("DROP TABLE knowledge_heads")
                execSQL("DROP TABLE knowledge_revisions")
                execSQL("DROP TABLE deleted_knowledge")
                execSQL("DROP TABLE claim_feedback")
                execSQL("DROP TABLE deleted_claim_feedback")
                version = 3
            }
        }
        repository = LifeRepository(context, clock, name)
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(generation, repository.currentAnalysisGeneration())
        assertEquals(run, repository.analysisRun(run.id))
        assertEquals("合成资料：用户记录了疲劳。", repository.record("synthetic-source")!!.draft.body)
        assertTrue(repository.claimFeedbacks().isEmpty())
        repository.saveClaimFeedback(feedback(run), null, run.version)
        assertEquals(5, repository.captureSnapshot(directory()).sourceSchemaVersion)
    }

    private fun run(expiresAt: Instant? = null): StoredAnalysisRun {
        val record = repository.createRecord(RecordDraft("合成资料：用户记录了疲劳。"), "synthetic-source")
        val text = record.draft.body
        val hash = AnalysisRunCodec.sha256(text)
        val receipt = EvidenceReceipt(AnalysisRunCodec.evidenceId(record.id, 1, "body", 0, text.length, hash),
            record.id, 1, "body", 0, text.length, text.length, hash)
        val claim = ProposedClaim("合成模型提案：用户自述疲劳。", listOf(receipt.id), ClaimKind.REPORTED_EXPERIENCE)
        val call = AnalysisCall("synthetic-batch", 1, "synthetic-provider", "a".repeat(64), 100, 100, listOf(receipt), now,
            CallStatus.SUCCEEDED, now, proposal = BatchProposal(listOf(claim)))
        val request = AnalysisRequest("合成问题：本周发生了什么？", AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai"), expiresAt = expiresAt)
        val run = StoredAnalysisRun("synthetic-run", 1, repository.analysisSourceStamp(), now, now, expiresAt, AnalysisRunCodec.encode(AnalysisRunState(request, listOf(call))))
        return repository.saveAnalysisRun(run, null)
    }

    private fun feedback(run: StoredAnalysisRun, decision: ClaimFeedbackDecision = ClaimFeedbackDecision.CONFIRMED, text: String? = null): StoredClaimFeedback {
        val call = AnalysisRunCodec.decode(run.payload).calls.single()
        val ref = ClaimRef(call.batchKey, 0, AnalysisRunCodec.claimSha256(call.proposal!!.claims.single()))
        return StoredClaimFeedback(AnalysisRunCodec.feedbackId(run.id, ref), 1, run.id, ref.batchKey, 0, ref.claimSha256, run.source, decision, text, now, now)
    }

    private fun orphan(storeId: String, runId: String): StoredClaimFeedback {
        val ref = ClaimRef("synthetic-erased-batch", 0, "a".repeat(64))
        return StoredClaimFeedback(AnalysisRunCodec.feedbackId(runId, ref), 1, runId, ref.batchKey, 0, ref.claimSha256,
            SourceStamp(storeId, 0), ClaimFeedbackDecision.CORRECTED, "合".repeat(2_000), now, now)
    }

    private fun directory() = File(workspace, "synthetic-${sequence++}").apply { check(mkdirs()) }
}
