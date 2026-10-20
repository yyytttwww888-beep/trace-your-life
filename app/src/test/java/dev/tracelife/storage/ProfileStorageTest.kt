package dev.tracelife.storage

import android.content.Context
import dev.tracelife.actions.ActionDraft
import dev.tracelife.actions.ActionExperiments
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.profile.*
import dev.tracelife.transfer.Deletion
import dev.tracelife.transfer.RepositorySnapshot
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
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic statements and independent user notes, through real SQLite and repository transactions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileStorageTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var workspace: File
    private val name = "synthetic-profile-storage-${UUID.randomUUID()}.db"
    private val now = Instant.parse("2026-10-19T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-19")
    private val scope = AnalysisQuery(day, day, "Asia/Shanghai")
    private var sequence = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        workspace = File(context.cacheDir, "synthetic-profile-storage-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name); workspace.deleteRecursively() }

    @Test fun `commands persist immutable history with strict operation retry identity and separate generation`() {
        val knowledge = publish()
        val initial = initial("profile", knowledge)
        val source = repository.analysisSourceStamp()
        val before = repository.currentAnalysisGeneration()
        repository.saveProfile(initial, null)
        assertEquals(source, repository.analysisSourceStamp())
        assertTrue(repository.currentAnalysisGeneration() > before)
        val adopted = command(initial, "adopt", ProfileChange.ADOPTED, ProfileAdoption.ADOPTED)
        repository.saveProfile(adopted, 1)
        val feedback = command(adopted, "feedback", ProfileChange.FEEDBACK).copy(userText = "合成用户独立背景。")
        repository.saveProfile(feedback, 2)
        val generation = repository.currentAnalysisGeneration()

        assertEquals(adopted, repository.saveProfile(adopted.copy(updatedAt = now.plusSeconds(10)), 1))
        assertEquals(initial, repository.saveProfile(initial.copy(createdAt = now.plusSeconds(10), updatedAt = now.plusSeconds(10)), null))
        assertEquals(generation, repository.currentAnalysisGeneration())
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveProfile(feedback.copy(userText = "合成不同请求。"), 2)
        }
        assertThrows(RevisionConflictException::class.java) { repository.saveProfile(adopted.copy(operationId = "late-command"), 1) }
        assertThrows(RevisionConflictException::class.java) { repository.deleteProfile("profile", 1) }
        assertEquals(listOf(feedback, adopted, initial), repository.profileHistory("profile"))
        assertEquals(listOf(ProfileHead("profile", 3)), repository.profileHeads())
        assertThrows(IllegalArgumentException::class.java) { repository.profileHistory("profile", limit = 101) }
        assertThrows(IllegalArgumentException::class.java) { repository.profileHeads(limit = 101) }
        repository.close()
        repository = LifeRepository(context, clock, name)
        assertEquals(feedback, repository.profileRevision("profile"))
    }

    @Test fun `adoption and new sources validate current knowledge while independent maintenance survives staleness`() {
        val knowledge = publish()
        val first = repository.saveProfile(initial("profile", knowledge), null)
        repository.updateRecord("source-1", 1, RecordDraft("合成更正后的来源。", Occurrence(day)))
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveProfile(command(first, "invalid-adopt", ProfileChange.ADOPTED, ProfileAdoption.ADOPTED), 1)
        }
        val annotated = repository.saveProfile(command(first, "user-note", ProfileChange.FEEDBACK).copy(userText = "合成失效后独立补充。"), 1)
        val declined = repository.saveProfile(command(annotated, "decline", ProfileChange.DECLINED, ProfileAdoption.DECLINED), 2)
        assertEquals("合成失效后独立补充。", declined.userText)
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveProfile(initial("stale-copy", knowledge).copy(draft = draft(knowledge).copy(layer = ProfileLayer.LONG_TERM)), null)
        }

        val note = publish(KnowledgeCategory.KNOWLEDGE_NOTE)
        assertThrows(IllegalArgumentException::class.java) { repository.saveProfile(initial("not-personal", note), null) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveProfile(command(declined, "wrong-source", ProfileChange.ORGANIZED, ProfileAdoption.CANDIDATE)
                .copy(draft = draft(note)), 3)
        }
        assertEquals(declined, repository.profileRevision("profile"))
    }

    @Test fun `declined identity cannot be recreated under another id or organized into occupied slot`() {
        val knowledge = publish()
        val initial = repository.saveProfile(initial("original", knowledge), null)
        repository.saveProfile(command(initial, "decline", ProfileChange.DECLINED, ProfileAdoption.DECLINED), 1)
        assertThrows(IllegalArgumentException::class.java) { repository.saveProfile(initial("replacement", knowledge), null) }
        val other = initial("other-dimension", knowledge).copy(draft = draft(knowledge).copy(dimension = ProfileDimension.ROLES_AND_CONTEXT))
        repository.saveProfile(other, null)
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveProfile(command(other, "same-slot", ProfileChange.ORGANIZED).copy(draft = draft(knowledge)), 1)
        }
        assertEquals(other, repository.profileRevision(other.id))
        assertEquals(2, repository.profileHeads().size)
    }

    @Test fun `future publication rejects new candidates and adoption without blocking independent feedback`() {
        val knowledge = publish()
        val candidate = repository.saveProfile(initial("profile", knowledge), null)
        repository.close()
        val earlierClock = Clock.fixed(now.minusSeconds(1), ZoneOffset.UTC)
        repository = LifeRepository(context, earlierClock, name)
        assertEquals(dev.tracelife.knowledge.KnowledgeEvidenceState.CURRENT,
            KnowledgeLibrary(repository, earlierClock).get(knowledge.id)!!.evidenceState)
        val before = repository.currentAnalysisGeneration()

        assertThrows(IllegalArgumentException::class.java) {
            repository.saveProfile(initial("future-source", knowledge)
                .copy(draft = draft(knowledge).copy(dimension = ProfileDimension.ROLES_AND_CONTEXT)), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveProfile(command(candidate, "future-adopt", ProfileChange.ADOPTED, ProfileAdoption.ADOPTED), 1)
        }
        assertEquals(before, repository.currentAnalysisGeneration())
        assertNull(repository.profileRevision("future-source"))
        assertEquals(listOf(candidate), repository.profileHistory(candidate.id))

        val annotated = repository.saveProfile(command(candidate, "independent-note", ProfileChange.FEEDBACK)
            .copy(userText = "合成时钟回拨后的独立文字。"), 1)
        repository.close()
        repository = LifeRepository(context, clock, name)
        val adopted = repository.saveProfile(command(annotated, "now-adopt", ProfileChange.ADOPTED, ProfileAdoption.ADOPTED), 2)
        assertEquals(annotated.userText, adopted.userText)
        assertEquals(ProfileAdoption.ADOPTED, adopted.adoption)
    }

    @Test fun `local complete chain wins over higher incoming versions and old backups`() {
        val initial = repository.saveProfile(initial("profile", publish()), null)
        val old = snapshot()
        val local = repository.saveProfile(command(initial, "local-feedback", ProfileChange.FEEDBACK).copy(userText = "合成本地背景优先。"), 1)
        val declined = repository.saveProfile(command(local, "local-decline", ProfileChange.DECLINED, ProfileAdoption.DECLINED), 2)
        val imported = mutableListOf(initial)
        repeat(4) { index -> imported += command(imported.last(), "incoming-$index", ProfileChange.FEEDBACK)
            .copy(userText = "合成备份更新但无权覆盖 $index", updatedAt = now.plusSeconds(index.toLong() + 1)) }
        val higher = old.copy(profileRevisions = imported, profileHeads = listOf(ProfileHead("profile", 5)))

        restore(higher)
        assertEquals(declined, repository.profileRevision("profile"))
        assertEquals(listOf(declined, local, initial), repository.profileHistory("profile"))
        restore(old)
        assertEquals(declined, repository.profileRevision("profile"))
    }

    @Test fun `both deletion ledgers win and old backups cannot resurrect independent profile text`() {
        val empty = snapshot()
        val local = orphan("local", 1)
        val incoming = orphan("incoming", 2)
        restore(empty.copy(profileRevisions = listOf(local, incoming), profileHeads = listOf(ProfileHead("local", 1), ProfileHead("incoming", 1))))
        val old = snapshot()
        repository.deleteProfile("local", 1)
        restore(old.copy(profileRevisions = listOf(local), profileHeads = listOf(ProfileHead("local", 1)),
            deletedProfiles = listOf(Deletion("incoming", 1, now))))
        assertTrue(repository.profileHeads().isEmpty())
        assertEquals(setOf("local", "incoming"), snapshot().deletedProfiles.map { it.id }.toSet())
        restore(old)
        assertNull(repository.profileRevision("local"))
        assertNull(repository.profileRevision("incoming"))
        assertThrows(IllegalArgumentException::class.java) { repository.saveProfile(local, null) }
    }

    @Test fun `old schema backups preserve orphan profiles and profile only targets require replacement`() {
        val empty = snapshot()
        val orphan = orphan("profile", 1)
        repository.restoreSnapshot(empty.copy(profileRevisions = listOf(orphan), profileHeads = listOf(ProfileHead(orphan.id, 1))),
            directory(), repository.currentGeneration())
        assertThrows(IllegalStateException::class.java) { repository.restoreSnapshot(empty, directory(), repository.currentGeneration()) }
        restore(empty.copy(sourceSchemaVersion = 6))
        assertEquals(orphan, repository.profileRevision("profile"))
        assertEquals(orphan, snapshot().profileRevisions.single())
        assertNull(repository.knowledgeRevision(orphan.draft.knowledge.knowledgeId))
    }

    @Test fun `deleting source knowledge task or shared feedback leaves no machine copy in profile history`() {
        listOf("source", "knowledge", "task", "feedback").forEach { kind ->
            val knowledge = publish(withFeedback = true)
            val first = repository.saveProfile(initial("profile-$kind", knowledge), null)
            val note = repository.saveProfile(command(first, "note-$kind", ProfileChange.FEEDBACK).copy(userText = "合成独立背景 $kind"), 1)
            val old = snapshot()
            when (kind) {
                "source" -> repository.deleteRecord("source-$sequence", 1)
                "knowledge" -> repository.deleteKnowledge(knowledge.id, 1)
                "task" -> repository.deleteAnalysisRun(knowledge.runId, repository.analysisRun(knowledge.runId)!!.version)
                else -> {
                    val feedbackId = AnalysisRunCodec.feedbackId(knowledge.runId, knowledge.claimRef)
                    repository.deleteClaimFeedback(feedbackId, 1)
                }
            }
            assertNull(repository.knowledgeRevision(knowledge.id))
            assertEquals(note, repository.profileRevision(note.id))
            assertEquals(listOf(note, first), repository.profileHistory(note.id))
            assertFalse(repository.profileHistory(note.id).any { ProfileCodec.encode(it).contains(knowledge.claim.text) })
            restore(old)
            assertNull(repository.knowledgeRevision(knowledge.id))
            assertEquals(note, repository.profileRevision(note.id))
        }
    }

    @Test fun `merged capacity or conflicting slot rejects restore without losing local decisions`() {
        val empty = snapshot()
        val local = orphan("local", 1)
        restore(empty.copy(profileRevisions = listOf(local), profileHeads = listOf(ProfileHead(local.id, 1))))
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val collision = local.copy(id = "another-id")
        assertThrows(IllegalArgumentException::class.java) {
            restore(empty.copy(profileRevisions = listOf(collision), profileHeads = listOf(ProfileHead(collision.id, 1))))
        }
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(generation, repository.currentAnalysisGeneration())
        assertEquals(local, repository.profileRevision(local.id))

        repository.deleteProfile(local.id, 1)
        fun rows(prefix: String, offset: Int) = List(750) { index -> orphan("$prefix-$index", offset + index)
            .copy(userText = "\u0001".repeat(2_000)) }
        val retained = rows("retained", 10)
        val incoming = rows("incoming", 10_000)
        restore(empty.copy(profileRevisions = retained, profileHeads = retained.map { ProfileHead(it.id, 1) }))
        val before = repository.analysisSourceStamp()
        val analysis = repository.currentAnalysisGeneration()
        assertThrows(IllegalArgumentException::class.java) {
            restore(empty.copy(profileRevisions = incoming, profileHeads = incoming.map { ProfileHead(it.id, 1) }))
        }
        assertEquals(before, repository.analysisSourceStamp())
        assertEquals(analysis, repository.currentAnalysisGeneration())
        assertEquals(retained.toSet(), snapshot().profileRevisions.toSet())
    }

    @Test fun `profile changes invalidate captured publication and restore while record analysis remains current`() {
        val knowledge = publish()
        val before = snapshot()
        val source = repository.analysisSourceStamp()
        repository.saveProfile(initial("profile", knowledge), null)
        val stage = File(workspace, "synthetic.pending").apply { writeText("synthetic complete bytes") }
        val target = File(workspace, "synthetic.json")
        assertThrows(StaleSnapshotException::class.java) { repository.publishSnapshot(before, stage, target) }
        assertThrows(StaleSnapshotException::class.java) {
            repository.restoreSnapshot(before, directory(), before.generation, true, before.analysisGeneration)
        }
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(source, repository.analysisRun(knowledge.runId)!!.source)
        assertFalse(target.exists())
    }

    @Test fun `schema six migration retains source task action and generations then enables profile writes`() {
        val knowledge = publish()
        val action = ActionExperiments(repository, clock).create(ActionDraft("合成行动", "合成触发", "合成做法", "合成信号", day, "Asia/Shanghai"), "action").value
        val before = snapshot()
        repository.close()
        LifeDatabase(context, name).use { helper -> helper.writableDatabase.apply {
            dev.tracelife.storage.removeVersionNineForMigration(this)
            execSQL("DROP TABLE dashboard_preferences")
            execSQL("ALTER TABLE store_state DROP COLUMN restore_epoch")
            execSQL("DROP TABLE profile_heads")
            execSQL("DROP TABLE profile_revisions")
            execSQL("DROP TABLE deleted_profiles")
            version = 6
        } }
        repository = LifeRepository(context, clock, name)
        assertEquals(SourceStamp(before.storeId, before.generation), repository.analysisSourceStamp())
        assertEquals(before.analysisGeneration, repository.currentAnalysisGeneration())
        assertEquals(action, repository.actionRevision(action.id))
        assertEquals(knowledge, repository.knowledgeRevision(knowledge.id))
        assertEquals(before.analysisRuns.single(), repository.analysisRun(knowledge.runId))
        assertEquals(10, snapshot().sourceSchemaVersion)
        assertTrue(repository.profileHeads().isEmpty())
        repository.saveProfile(initial("after-upgrade", knowledge), null)
        assertEquals(before.generation, repository.currentGeneration())
        assertTrue(repository.currentAnalysisGeneration() > before.analysisGeneration)
    }

    private fun draft(knowledge: StoredKnowledgeRevision) = ProfileDraft(ProfileKnowledgeRef(knowledge.id, knowledge.revision),
        ProfileDimension.BEHAVIOR_AND_DECISIONS, ProfileLayer.CONTEXTUAL_PATTERN)
    private fun initial(id: String, knowledge: StoredKnowledgeRevision) = StoredProfileRevision(id, 1, draft(knowledge),
        ProfileAdoption.CANDIDATE, now, now)
    private fun orphan(id: String, identity: Int) = StoredProfileRevision(id, 1,
        ProfileDraft(ProfileKnowledgeRef(identity.toString(16).padStart(64, '0'), 1), ProfileDimension.ROLES_AND_CONTEXT, ProfileLayer.RECENT_STATE),
        ProfileAdoption.CANDIDATE, now, now, userText = "合成独立文字。")
    private fun command(previous: StoredProfileRevision, operation: String, change: ProfileChange, adoption: ProfileAdoption = previous.adoption) =
        previous.copy(revision = previous.revision + 1, operationId = operation, change = change, adoption = adoption)

    private fun publish(category: KnowledgeCategory = KnowledgeCategory.OBSERVATION, withFeedback: Boolean = false): StoredKnowledgeRevision {
        sequence++
        val sourceId = "source-$sequence"
        val runId = "run-$sequence"
        repository.createRecord(RecordDraft("合成来源陈述：我在任务开始前列出步骤。", Occurrence(day)), sourceId)
        val analysis = ControlledAnalysis(repository, clock)
        analysis.start(AnalysisRequest("合成画像观察", scope), runId)
        analysis.runBatch(runId, "extract", listOf(EvidenceSelection(sourceId, "body")), object : AnalysisProvider {
            override val id = "synthetic-profile-extract"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = 100
            override fun complete(request: ModelRequest): BatchProposal {
                val receipt = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim("合成机器陈述 $sequence：用户自述先列步骤。", listOf(receipt), ClaimKind.REPORTED_EXPERIENCE)))
            }
        })
        val claim = analysis.claimRef(runId, "extract", 0)
        if (withFeedback) AnalysisFeedback(repository, clock).respond(runId, claim, analysis.task(runId).stored.version,
            ClaimFeedbackDecision.CONFIRMED, "合成共享反馈。")
        analysis.reviewClaim(runId, "review", claim, provider = object : SemanticReviewProvider {
            override val id = "synthetic-profile-review"
            override val contextWindowTokens = 100_000
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                val receipt = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(receipt)))
            }
        })
        return KnowledgeLibrary(repository, clock).publish(runId, claim, "review", category, analysis.task(runId).stored.version)
    }

    private fun snapshot() = repository.captureSnapshot(directory())
    private fun restore(value: RepositorySnapshot) = repository.restoreSnapshot(value, directory(), repository.currentGeneration(), true,
        repository.currentAnalysisGeneration())
    private fun directory() = File(workspace, UUID.randomUUID().toString()).apply { check(mkdir()) }
}
