package dev.tracelife.storage

import android.content.Context
import dev.tracelife.actions.ActionDraft
import dev.tracelife.actions.ActionExperiments
import dev.tracelife.analysis.AnalysisFeedback
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
import dev.tracelife.analysis.LegacyRedactionCapacityFixture
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.transfer.StaleSnapshotException
import dev.tracelife.transfer.RepositorySnapshot
import dev.tracelife.transfer.Deletion
import dev.tracelife.transfer.InvalidBackupException
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
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** All source material, model proposals and independent user text are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KnowledgeStorageTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var name: String
    private lateinit var workspace: File
    private val now = Instant.parse("2026-10-17T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var sequence = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        name = "synthetic-knowledge-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, name)
        workspace = File(context.cacheDir, "synthetic-knowledge-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(name)
        workspace.deleteRecursively()
    }

    @Test fun `publication is immutable CAS with idempotent retry and independent generation`() {
        val run = run()
        val value = proof(run)
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        assertEquals(value, repository.publishKnowledge(value, null, run.version))
        assertEquals(source, repository.analysisSourceStamp())
        assertTrue(repository.currentAnalysisGeneration() > generation)
        val after = repository.currentAnalysisGeneration()
        assertEquals(value, repository.publishKnowledge(value, null, run.version))
        assertEquals(after, repository.currentAnalysisGeneration())
        val next = value.copy(revision = 2, topics = setOf("learning"))
        assertThrows(RevisionConflictException::class.java) { repository.publishKnowledge(next, null, run.version) }
        repository.publishKnowledge(next, 1, run.version)
        assertEquals(next, repository.knowledgeRevision(value.id))
        assertEquals(value, repository.knowledgeRevision(value.id, 1))
        assertEquals(listOf(next, value), repository.knowledgeHistory(value.id))
        assertEquals(listOf(KnowledgeHead(value.id, 2)), repository.knowledgeHeads())
        repository.close()
        repository = LifeRepository(context, clock, name)
        assertEquals(next, repository.knowledgeRevision(value.id))
        assertThrows(IllegalArgumentException::class.java) { repository.knowledgeHeads(0, 101) }
    }

    @Test fun `publication cannot forge proof or race task and source changes`() {
        val run = run()
        val value = proof(run)
        assertThrows(IllegalArgumentException::class.java) { repository.publishKnowledge(value.copy(reviewProviderId = "synthetic-invented"), null, run.version) }
        val nextRun = repository.saveAnalysisRun(run.copy(version = 2), 1)
        assertThrows(RevisionConflictException::class.java) { repository.publishKnowledge(value, null, 1) }
        repository.publishKnowledge(value, null, nextRun.version)
        repository.updateRecord("synthetic-source", 1, RecordDraft("合成资料：修改后的记录。"))
        assertThrows(StaleSnapshotException::class.java) { repository.publishKnowledge(value, null, nextRun.version) }
        assertEquals(value, repository.knowledgeRevision(value.id))
    }

    @Test fun `a newer successful review prevents selecting an older supportive opinion`() {
        val run = run()
        val value = proof(run)
        repository.publishKnowledge(value, null, run.version)
        val state = AnalysisRunCodec.decode(run.payload)
        val rejected = state.calls.last().copy(batchKey = "synthetic-later-review", review = ClaimReview(ReviewVerdict.UNSUPPORTED))
        val updated = repository.saveAnalysisRun(run.copy(version = 2, payload = AnalysisRunCodec.encode(state.copy(calls = state.calls + rejected))), 1)
        assertFalse(KnowledgeValidation.reviewStillCurrent(value, updated))
        assertThrows(IllegalArgumentException::class.java) { repository.publishKnowledge(value, 1, updated.version) }
        assertTrue(KnowledgeValidation.reviewStillCurrent(value, null))
        assertEquals(value, repository.knowledgeRevision(value.id))
    }

    @Test fun `feedback changes block old reviews and corrected wording never replaces published text`() {
        val run = run()
        val value = proof(run)
        repository.publishKnowledge(value, null, run.version)
        val feedback = feedback(run, ClaimFeedbackDecision.CONFIRMED)
        repository.saveClaimFeedback(feedback, null, run.version)
        val currentRun = repository.analysisRun(run.id)!!
        assertThrows(RevisionConflictException::class.java) { repository.publishKnowledge(value, 1, currentRun.version) }
        val reviewed = addReview(currentRun, 1)
        val updated = proof(reviewed, "synthetic-review-1", 2)
        repository.publishKnowledge(updated, 1, reviewed.version)
        val correction = feedback.copy(version = 2, decision = ClaimFeedbackDecision.CORRECTED, userText = "合成用户独立文字：这不是原来陈述的含义。")
        repository.saveClaimFeedback(correction, 1, reviewed.version)
        val correctedRun = addReview(repository.analysisRun(run.id)!!, 2)
        assertThrows(IllegalArgumentException::class.java) {
            repository.publishKnowledge(proof(correctedRun, "synthetic-review-2", 3), 2, correctedRun.version)
        }
        assertEquals(value.claim.text, repository.knowledgeRevision(value.id)!!.claim.text)
        assertEquals(correction.userText, repository.claimFeedback(feedback.id)!!.userText)
    }

    @Test fun `interpretations require a confirmed feedback dependency and fresh review`() {
        val run = run(kind = ClaimKind.POSSIBLE_INTERPRETATION)
        assertThrows(IllegalArgumentException::class.java) { proof(run, category = KnowledgeCategory.INSIGHT) }
        val confirmed = feedback(run, ClaimFeedbackDecision.CONFIRMED)
        repository.saveClaimFeedback(confirmed, null, run.version)
        val reviewed = addReview(repository.analysisRun(run.id)!!, 1)
        val value = proof(reviewed, "synthetic-review-1", category = KnowledgeCategory.INSIGHT)
        repository.publishKnowledge(value, null, reviewed.version)
        assertEquals(KnowledgeCategory.INSIGHT, repository.knowledgeRevision(value.id)!!.category)
        assertThrows(IllegalArgumentException::class.java) { repository.publishKnowledge(value.copy(category = KnowledgeCategory.OBSERVATION), 1, reviewed.version) }
    }

    @Test fun `knowledge deletion survives restore and prevents identity reuse`() {
        val run = run()
        val value = proof(run)
        repository.publishKnowledge(value, null, run.version)
        val files = directory()
        val snapshot = repository.captureSnapshot(files)
        repository.deleteKnowledge(value.id, 1)
        assertNull(repository.knowledgeRevision(value.id))
        assertThrows(IllegalArgumentException::class.java) { repository.publishKnowledge(value, null, run.version) }
        repository.restoreSnapshot(snapshot, files, repository.currentGeneration(), true)
        val restored = repository.captureSnapshot(directory())
        assertTrue(restored.knowledgeRevisions.isEmpty())
        assertEquals(value.id, restored.deletedKnowledge.single().id)
    }

    @Test fun `source deletion removes all machine knowledge while preserving independent feedback`() {
        val run = run()
        val confirmed = feedback(run, ClaimFeedbackDecision.CONFIRMED).copy(userText = "合成独立用户文字：这是我自己的说明。")
        repository.saveClaimFeedback(confirmed, null, run.version)
        val reviewed = addReview(repository.analysisRun(run.id)!!, 1)
        val value = proof(reviewed, "synthetic-review-1")
        repository.publishKnowledge(value, null, reviewed.version)
        repository.deleteRecord("synthetic-source", 1)
        assertNull(repository.knowledgeRevision(value.id))
        assertTrue(repository.knowledgeHistory(value.id).isEmpty())
        assertEquals(confirmed, repository.claimFeedback(confirmed.id))
        assertTrue(repository.captureSnapshot(directory()).knowledgeRevisions.isEmpty())
    }

    @Test fun `task and feedback erasure remove only dependent knowledge and all its revisions`() {
        val first = run()
        val second = run(id = "synthetic-second")
        val a = proof(first)
        val b = proof(second)
        repository.publishKnowledge(a, null, first.version)
        repository.publishKnowledge(b, null, second.version)
        repository.deleteAnalysisRun(first.id, first.version)
        assertNull(repository.knowledgeRevision(a.id))
        assertEquals(b, repository.knowledgeRevision(b.id))
        val confirmed = feedback(second, ClaimFeedbackDecision.CONFIRMED)
        repository.saveClaimFeedback(confirmed, null, second.version)
        val reviewed = addReview(repository.analysisRun(second.id)!!, 1)
        repository.publishKnowledge(proof(reviewed, "synthetic-review-1", 2), 1, reviewed.version)
        repository.deleteClaimFeedback(confirmed.id, 1)
        assertNull(repository.knowledgeRevision(b.id))
        assertTrue(repository.knowledgeHistory(b.id).isEmpty())
        assertEquals(setOf(a.id, b.id), repository.captureSnapshot(directory()).deletedKnowledge.map { it.id }.toSet())
    }

    @Test fun `restore preserves later local knowledge head and invalidates every source stamp`() {
        val run = run()
        val value = proof(run)
        repository.publishKnowledge(value, null, run.version)
        val files = directory()
        val old = repository.captureSnapshot(files)
        val next = value.copy(revision = 2, topics = setOf("learning"))
        repository.publishKnowledge(next, 1, run.version)
        repository.restoreSnapshot(old, files, repository.currentGeneration(), true)
        assertEquals(next, repository.knowledgeRevision(value.id))
        assertEquals(2, repository.knowledgeHistory(value.id).size)
        assertTrue(repository.currentGeneration() > value.source.generation)
        assertEquals(value.source, repository.knowledgeRevision(value.id)!!.source)
    }

    @Test fun `restore cannot preserve a newer local note without its original evidence and fences it`() {
        val emptyFiles = directory()
        val empty = repository.captureSnapshot(emptyFiles)
        val run = run()
        val value = proof(run)
        repository.publishKnowledge(value, null, run.version)
        val fullFiles = directory()
        val full = repository.captureSnapshot(fullFiles)
        repository.restoreSnapshot(empty, emptyFiles, repository.currentGeneration(), true)
        assertNull(repository.knowledgeRevision(value.id))
        assertEquals(value.id, repository.captureSnapshot(directory()).deletedKnowledge.single().id)
        repository.restoreSnapshot(full, fullFiles, repository.currentGeneration(), true)
        assertNull(repository.knowledgeRevision(value.id))
    }

    @Test fun `publication invalidates transfer snapshots and expired proofs cannot publish`() {
        val run = run(expiresAt = now.plusSeconds(1))
        val value = proof(run)
        val files = directory()
        val snapshot = repository.captureSnapshot(files)
        repository.publishKnowledge(value, null, run.version)
        assertThrows(StaleSnapshotException::class.java) {
            repository.restoreSnapshot(snapshot, files, snapshot.generation, true, snapshot.analysisGeneration)
        }
        repository.close()
        repository = LifeRepository(context, Clock.fixed(now.plusSeconds(2), ZoneOffset.UTC), name)
        assertThrows(IllegalArgumentException::class.java) { repository.publishKnowledge(value, null, run.version) }
    }

    @Test fun `version four migration preserves source tasks and feedback and initializes knowledge`() {
        val run = run()
        val value = feedback(run, ClaimFeedbackDecision.CONFIRMED)
        repository.saveClaimFeedback(value, null, run.version)
        val generation = repository.currentAnalysisGeneration()
        val source = repository.analysisSourceStamp()
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
            execSQL("DROP TABLE knowledge_heads")
            execSQL("DROP TABLE knowledge_revisions")
            execSQL("DROP TABLE deleted_knowledge")
            version = 4
        } }
        repository = LifeRepository(context, clock, name)
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(generation, repository.currentAnalysisGeneration())
        assertEquals(value, repository.claimFeedback(value.id))
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertEquals(9, repository.captureSnapshot(directory()).sourceSchemaVersion)
    }

    @Test fun `feedback deletion removes an overfull legacy task and all its knowledge without losing independent text`() {
        val fixture = capacityFixture()
        val inconsistent = fixture.snapshot.copy(claimFeedback = fixture.snapshot.claimFeedback.filterNot { it.id == fixture.deleted.id },
            deletedClaimFeedback = listOf(Deletion(fixture.deleted.id, fixture.deleted.version, now)))
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(inconsistent) }
        val result = AnalysisFeedback(repository, clock).delete(fixture.deleted.id, fixture.deleted.version)
        assertEquals(fixture.full.id, result.purgedAnalysisRunId)
        assertNull(repository.analysisRun(fixture.full.id))
        assertNull(repository.claimFeedback(fixture.deleted.id))
        assertEquals(fixture.independent, repository.claimFeedback(fixture.independent.id))
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertTrue(fixture.knowledgeIds.all(repository::knowledgeDeleted))
        val clean = repository.captureSnapshot(directory())
        assertEquals(fixture.full.version, clean.deletedAnalysisRuns.single().lastRevision)
        assertEquals(2, clean.deletedKnowledge.first { it.id == fixture.knowledgeIds.first() }.lastRevision)
        assertEquals("合成容量边界独立行动", ActionExperiments(repository, clock).get("capacity-action")!!.value.draft.title)
        val restored = repository.restoreSnapshot(fixture.snapshot, directory(), repository.currentGeneration(), true)
        assertEquals(0, restored.capacityPurgedAnalysisRuns)
        assertNull(repository.analysisRun(fixture.full.id))
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertEquals(fixture.independent, repository.claimFeedback(fixture.independent.id))
        assertThrows(IllegalArgumentException::class.java) {
            ControlledAnalysis(repository, clock).start(AnalysisRunCodec.decode(fixture.small.payload).request, fixture.full.id)
        }
    }

    @Test fun `restore merging feedback tombstones reports capacity cleanup and fences the entire legacy task`() {
        val fixture = capacityFixture()
        val targetName = "synthetic-capacity-target-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, targetName).use { target ->
                // The target deleted feedback while its earlier ledger was still small. It has no
                // task tombstone yet, so restoring the larger backup must exercise redaction.
                target.restoreSnapshot(fixture.snapshot.copy(analysisRuns = listOf(fixture.small)), directory(), target.currentGeneration())
                assertNull(target.deleteClaimFeedback(fixture.deleted.id, fixture.deleted.version).purgedAnalysisRunId)
                assertNotNull(target.analysisRun(fixture.full.id))
                assertEquals(1, target.knowledgeHeads().size)
                val result = target.restoreSnapshot(fixture.snapshot, directory(), target.currentGeneration(), true)
                assertEquals(1, result.capacityPurgedAnalysisRuns)
                assertNull(target.analysisRun(fixture.full.id))
                assertTrue(target.knowledgeHeads().isEmpty())
                assertTrue(fixture.knowledgeIds.all(target::knowledgeDeleted))
                assertEquals(fixture.independent, target.claimFeedback(fixture.independent.id))
                assertEquals("合成容量边界独立行动", ActionExperiments(target, clock).get("capacity-action")!!.value.draft.title)
                val clean = target.captureSnapshot(directory())
                assertEquals(fixture.full.version, clean.deletedAnalysisRuns.single().lastRevision)
                assertEquals(0, target.restoreSnapshot(fixture.snapshot, directory(), target.currentGeneration(), true).capacityPurgedAnalysisRuns)
                assertNull(target.analysisRun(fixture.full.id))
                assertThrows(IllegalArgumentException::class.java) {
                    ControlledAnalysis(target, clock).start(AnalysisRunCodec.decode(fixture.small.payload).request, fixture.full.id)
                }
            }
        } finally { context.deleteDatabase(targetName) }
    }

    @Test fun `restore excludes feedback-incompatible later tasks before deciding aggregate redaction capacity`() {
        val fixture = capacityFixture()
        fun bytes(run: StoredAnalysisRun) = run.payload.toByteArray(Charsets.UTF_8).size.toLong()
        val growingJson = JSONObject(fixture.full.payload)
        // Removing one filler output leaves enough room for the v6 envelope in this individual
        // task. The full snapshot below still leaves less room than that envelope's growth.
        growingJson.getJSONArray("calls").getJSONObject(5).getJSONObject("proposal").put("unknowns", JSONArray())
        val growing = fixture.full.copy(payload = growingJson.toString())
        val erased = AnalysisRunCodec.redactDeletedFeedback(growing, setOf(fixture.deleted.id), now)
        assertTrue(bytes(erased) > bytes(growing) + 128)
        val incompatibleId = "z-incompatible-capacity"
        val companions = (1..7).map { index -> fixture.full.copy(
            id = if (index == 7) incompatibleId else "synthetic-capacity-other-$index", version = 1) }
        val remaining = 16L * 1024 * 1024 - bytes(growing) - companions.sumOf(::bytes) - 64
        val reserveJson = JSONObject(fixture.small.payload)
        val unknowns = JSONArray()
        reserveJson.getJSONArray("calls").getJSONObject(0).getJSONObject("proposal").put("unknowns", unknowns)
        repeat(20) {
            val gap = remaining - reserveJson.toString().toByteArray(Charsets.UTF_8).size
            val overhead = if (unknowns.length() == 0) 2 else 3
            if (gap > overhead) unknowns.put("x".repeat(minOf(2_000L, gap - overhead).toInt()))
        }
        val reserve = fixture.small.copy(id = "synthetic-capacity-reserve", version = 1, payload = reserveJson.toString())
        val incoming = fixture.snapshot.copy(analysisRuns = listOf(growing) + companions + reserve)
        val incomingBytes = incoming.analysisRuns.sumOf(::bytes)
        assertTrue(incomingBytes <= 16L * 1024 * 1024)
        assertTrue(incomingBytes + bytes(erased) - bytes(growing) > 16L * 1024 * 1024)
        SnapshotValidation.validate(incoming)

        val smallState = AnalysisRunCodec.decode(fixture.small.payload)
        val localOnlyClaim = smallState.calls.first().copy(batchKey = "synthetic-later-only-claim")
        val localRef = ClaimRef(localOnlyClaim.batchKey, 0, AnalysisRunCodec.claimSha256(localOnlyClaim.proposal!!.claims.single()))
        val localRun = fixture.small.copy(id = incompatibleId, version = 1,
            payload = AnalysisRunCodec.encode(smallState.copy(calls = smallState.calls + localOnlyClaim)))
        val localFeedback = StoredClaimFeedback(AnalysisRunCodec.feedbackId(incompatibleId, localRef), 1, incompatibleId,
            localRef.batchKey, localRef.claimIndex, localRef.claimSha256, localRun.source,
            ClaimFeedbackDecision.CONFIRMED, "合成旧备份不存在的独立反馈仍须保留", now, now)
        val initial = fixture.snapshot.copy(analysisRuns = listOf(fixture.small, localRun),
            claimFeedback = fixture.snapshot.claimFeedback + localFeedback)
        val targetName = "synthetic-aggregate-capacity-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, targetName).use { target ->
                target.restoreSnapshot(initial, directory(), target.currentGeneration())
                assertNull(target.deleteClaimFeedback(fixture.deleted.id, fixture.deleted.version).purgedAnalysisRunId)
                val result = target.restoreSnapshot(incoming, directory(), target.currentGeneration(), true)
                assertEquals(0, result.capacityPurgedAnalysisRuns)
                assertNotNull(target.analysisRun(growing.id))
                assertNull(target.analysisRun(incompatibleId))
                assertEquals(localFeedback, target.claimFeedback(localFeedback.id))
                val after = target.captureSnapshot(directory())
                assertFalse(after.deletedAnalysisRuns.any { it.id == growing.id })
                assertTrue(after.analysisRuns.sumOf(::bytes) <= 16L * 1024 * 1024)
                assertEquals("FEEDBACK_DELETED", AnalysisRunCodec.decode(target.analysisRun(growing.id)!!.payload)
                    .calls.single { it.batchKey == "synthetic-review-1" }.failureCode)
            }
        } finally { context.deleteDatabase(targetName) }
    }

    private data class CapacityFixture(val small: StoredAnalysisRun, val full: StoredAnalysisRun,
        val deleted: StoredClaimFeedback, val independent: StoredClaimFeedback,
        val knowledgeIds: List<String>, val snapshot: RepositorySnapshot)

    private fun capacityFixture(): CapacityFixture {
        val original = run()
        val deleted = feedback(original, ClaimFeedbackDecision.CONFIRMED).copy(userText = "合成即将删除的用户文字")
        repository.saveClaimFeedback(deleted, null, original.version)
        val reviewed = addReview(repository.analysisRun(original.id)!!, 1)
        val note = proof(reviewed, "synthetic-review-1")
        repository.publishKnowledge(note, null, reviewed.version)
        repository.publishKnowledge(note.copy(revision = 2, topics = setOf("合成容量测试")), 1, reviewed.version)
        val state = AnalysisRunCodec.decode(reviewed.payload)
        val extra = state.calls.first().copy(batchKey = "synthetic-independent")
        val extraRef = ClaimRef(extra.batchKey, 0, AnalysisRunCodec.claimSha256(extra.proposal!!.claims.single()))
        val extraReview = state.calls[1].copy(batchKey = "synthetic-independent-review", reviewTarget = extraRef)
        val withExtra = repository.saveAnalysisRun(reviewed.copy(version = reviewed.version + 1,
            payload = AnalysisRunCodec.encode(state.copy(calls = state.calls + extra + extraReview))), reviewed.version)
        val extraNote = KnowledgeValidation.fromRun(withExtra, extraRef, extraReview.batchKey, KnowledgeCategory.OBSERVATION,
            emptySet(), 1, now, now)
        repository.publishKnowledge(extraNote, null, withExtra.version)
        val independent = StoredClaimFeedback(AnalysisRunCodec.feedbackId(withExtra.id, extraRef), 1, withExtra.id,
            extraRef.batchKey, extraRef.claimIndex, extraRef.claimSha256, withExtra.source,
            ClaimFeedbackDecision.CONFIRMED, "合成容量边界仍须保留的独立文字", now, now)
        repository.saveClaimFeedback(independent, null, withExtra.version)
        ActionExperiments(repository, clock).create(ActionDraft("合成容量边界独立行动", "合成触发", "合成做法", "合成信号",
            LocalDate.parse("2026-10-18"), "Asia/Shanghai"), "capacity-action")
        val small = repository.analysisRun(original.id)!!
        val full = repository.saveAnalysisRun(LegacyRedactionCapacityFixture.fill(small).copy(version = small.version + 1), small.version)
        return CapacityFixture(small, full, deleted, independent, listOf(note.id, extraNote.id), repository.captureSnapshot(directory()))
    }

    private fun run(id: String = "synthetic-run", kind: ClaimKind = ClaimKind.REPORTED_EXPERIENCE, expiresAt: Instant? = null): StoredAnalysisRun {
        val record = repository.createRecord(RecordDraft("合成资料：用户记录了疲劳。"), "synthetic-source")
        val text = record.draft.body
        val hash = AnalysisRunCodec.sha256(text)
        val evidence = EvidenceReceipt(AnalysisRunCodec.evidenceId(record.id, 1, "body", 0, text.length, hash),
            record.id, 1, "body", 0, text.length, text.length, hash)
        val claim = ProposedClaim("合成模型提案：用户自述疲劳。", listOf(evidence.id), kind)
        val extraction = AnalysisCall("synthetic-batch", 1, "synthetic-provider", "a".repeat(64), 100, 100, listOf(evidence), now,
            CallStatus.SUCCEEDED, now, proposal = BatchProposal(listOf(claim), listOf("合成批次缺口，不复制到正式条目。")))
        val ref = ClaimRef(extraction.batchKey, 0, AnalysisRunCodec.claimSha256(claim))
        val review = AnalysisCall("synthetic-review", 1, "synthetic-reviewer", "b".repeat(64), 100, 100, listOf(evidence), now,
            CallStatus.SUCCEEDED, now, purpose = CallPurpose.REVIEW, reviewTarget = ref,
            review = ClaimReview(ReviewVerdict.SUPPORTED, listOf(evidence.id)))
        val request = AnalysisRequest("合成问题：本周有哪些记录？", AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai"), expiresAt = expiresAt)
        return repository.saveAnalysisRun(StoredAnalysisRun(id, 1, repository.analysisSourceStamp(), now, now, expiresAt,
            AnalysisRunCodec.encode(AnalysisRunState(request, listOf(extraction, review)))), null)
    }

    private fun ref(run: StoredAnalysisRun): ClaimRef = AnalysisRunCodec.decode(run.payload).calls.first().let {
        ClaimRef(it.batchKey, 0, AnalysisRunCodec.claimSha256(it.proposal!!.claims.single()))
    }

    private fun proof(run: StoredAnalysisRun, review: String = "synthetic-review", revision: Int = 1,
        category: KnowledgeCategory = KnowledgeCategory.OBSERVATION) =
        KnowledgeValidation.fromRun(run, ref(run), review, category, emptySet(), revision, now, now)

    private fun feedback(run: StoredAnalysisRun, decision: ClaimFeedbackDecision): StoredClaimFeedback {
        val ref = ref(run)
        return StoredClaimFeedback(AnalysisRunCodec.feedbackId(run.id, ref), 1, run.id, ref.batchKey, ref.claimIndex, ref.claimSha256,
            run.source, decision, null, now, now)
    }

    private fun addReview(run: StoredAnalysisRun, feedbackVersion: Int): StoredAnalysisRun {
        val state = AnalysisRunCodec.decode(run.payload)
        val call = state.calls.last { it.purpose == CallPurpose.REVIEW }.copy(batchKey = "synthetic-review-$feedbackVersion", feedbackVersion = feedbackVersion)
        return repository.saveAnalysisRun(run.copy(version = run.version + 1, payload = AnalysisRunCodec.encode(state.copy(calls = state.calls + call))), run.version)
    }

    private fun directory() = File(workspace, "synthetic-${sequence++}").apply { check(mkdirs()) }
}
