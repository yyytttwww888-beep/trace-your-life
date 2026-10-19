package dev.tracelife.storage

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.profile.*
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

/** Synthetic records and independent feedback through SQLite, including deletion and restore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileMaintenanceStorageTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var directory: File
    private val name = "synthetic-maintenance-storage-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-19")
    private val target = ProfileAnalysisTarget(ProfileDimension.ABILITIES_AND_STRATEGIES, ProfileLayer.CONTEXTUAL_PATTERN)
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        analysis = ControlledAnalysis(repository, clock)
        directory = File(context.cacheDir, "synthetic-maintenance-${UUID.randomUUID()}").apply { mkdirs() }
        repository.createRecord(RecordDraft("合成记录：小练习用于发现遗漏，结果仍取决于任务条件。", Occurrence(date)), "source")
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(name); directory.deleteRecursively() }

    @Test fun `catalog includes unmaterialized unclassified decisions and fixed leaf hashes reject changes`() {
        val rejected = generate("unclassified", profileTarget = null)
        AnalysisFeedback(repository, clock).respond("unclassified", rejected, analysis.task("unclassified").stored.version,
            ClaimFeedbackDecision.REJECTED, "合成独立纠正：不能扩大到所有任务。")
        val claim = generate("candidate")
        val refs = repository.profileMaintenanceCatalog(target, "candidate", claim)
        assertEquals(1, refs.size)
        assertEquals(ProfileMaintenanceReferenceKind.FEEDBACK, refs.single().kind)
        assertNull(repository.profileMaintenanceReference(refs.single()).dimension)
        val feedback = repository.claimFeedback(refs.single().id)!!
        repository.saveClaimFeedback(feedback.copy(version = feedback.version + 1, userText = "合成新限定。"), feedback.version,
            analysis.task("unclassified").stored.version)
        assertFalse(repository.profileMaintenanceCatalogCurrent(target, "candidate", claim, refs))
        assertThrows(StaleSnapshotException::class.java) { repository.profileMaintenanceReference(refs.single()) }
    }

    @Test fun `feedback linked by selected profiles remains in catalog after dimension reorganization`() {
        val old = profile()
        val changedTarget = ProfileAnalysisTarget(ProfileDimension.ROLES_AND_CONTEXT, ProfileLayer.CONTEXTUAL_PATTERN)
        PersonalProfile(repository, clock).update(old.id, old.revision, "move-dimension", old.draft.copy(dimension = changedTarget.dimension))
        val oldClaim = analysis.claimRef("old", "candidate", 0)
        val response = AnalysisFeedback(repository, clock).forClaim("old", oldClaim)!!.feedback
        repository.saveClaimFeedback(response.copy(version = response.version + 1, decision = ClaimFeedbackDecision.CORRECTED,
            userText = "合成限定：这只适用于原先的任务。"), response.version, analysis.task("old").stored.version)
        val claim = generate("new-dimension", changedTarget)
        val refs = repository.profileMaintenanceCatalog(changedTarget, "new-dimension", claim)
        assertEquals(setOf(ProfileMaintenanceReferenceKind.PROFILE, ProfileMaintenanceReferenceKind.FEEDBACK), refs.map { it.kind }.toSet())
        assertEquals(response.id, refs.single { it.kind == ProfileMaintenanceReferenceKind.FEEDBACK }.id)
    }

    @Test fun `unrelated profile deletion keeps maintenance task while referenced profile deletion erases it`() {
        val old = profile()
        val unrelated = PersonalProfile(repository, clock).create(old.draft.copy(dimension = ProfileDimension.ROLES_AND_CONTEXT), "unrelated",
            "合成无关独立文字。")
        val claim = generate("maintenance")
        analysis.planProfileMaintenance("maintenance", claim)
        PersonalProfile(repository, clock).delete(unrelated.value.id, unrelated.value.revision)
        assertNotNull(repository.analysisRun("maintenance"))
        PersonalProfile(repository, clock).delete(old.id, old.revision)
        assertNull(repository.analysisRun("maintenance"))
        assertNotNull(repository.analysisRun("old"))
        assertTrue(repository.captureSnapshot(directory).deletedAnalysisRuns.any { it.id == "maintenance" })
    }

    @Test fun `feedback deletion removes dependent maintenance while retaining unrelated ordinary tasks`() {
        val old = generate("blocked")
        val saved = AnalysisFeedback(repository, clock).respond("blocked", old, analysis.task("blocked").stored.version,
            ClaimFeedbackDecision.REJECTED, "合成独立否定。")
        val claim = generate("maintenance")
        analysis.planProfileMaintenance("maintenance", claim)
        generate("unrelated")
        repository.deleteClaimFeedback(saved.feedback.id, saved.feedback.version)
        assertNull(repository.analysisRun("maintenance"))
        assertNotNull(repository.analysisRun("unrelated"))
        assertNull(repository.claimFeedback(saved.feedback.id))
    }

    @Test fun `source task deletion follows profile identities and preserves user owned profile note`() {
        val old = profile()
        val claim = generate("maintenance")
        analysis.planProfileMaintenance("maintenance", claim)
        repository.deleteAnalysisRun("old", analysis.task("old").stored.version)
        assertNull(repository.analysisRun("maintenance"))
        assertNull(repository.knowledgeRevision(old.draft.knowledge.knowledgeId))
        assertEquals("合成保留的独立背景。", repository.profileRevision(old.id)!!.userText)
    }

    @Test fun `old backups cannot restore deleted cross task history and keep surviving user profile text`() {
        val old = profile()
        val claim = generate("maintenance")
        analysis.planProfileMaintenance("maintenance", claim)
        val snapshot = repository.captureSnapshot(directory)
        val encoded = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toByteArray()
        assertEquals(snapshot, SnapshotJsonCodec.read(encoded.inputStream()))
        repository.deleteKnowledge(old.draft.knowledge.knowledgeId, old.draft.knowledge.revision)
        repository.restoreSnapshot(snapshot, directory, repository.currentGeneration(), true, repository.currentAnalysisGeneration())
        assertNull(repository.analysisRun("maintenance"))
        assertNull(repository.knowledgeRevision(old.draft.knowledge.knowledgeId))
        assertEquals("合成保留的独立背景。", repository.profileRevision(old.id)!!.userText)
        SnapshotValidation.validate(repository.captureSnapshot(File(directory, "after").apply { mkdir() }))
    }

    @Test fun `snapshot already carrying a deleted reference cannot carry its maintenance paraphrases`() {
        val old = profile()
        val claim = generate("maintenance")
        analysis.planProfileMaintenance("maintenance", claim)
        val snapshot = repository.captureSnapshot(directory)
        val invalid = snapshot.copy(profileRevisions = emptyList(), profileHeads = emptyList(),
            deletedProfiles = listOf(dev.tracelife.transfer.Deletion(old.id, old.revision, clock.instant())))
        assertThrows(IllegalArgumentException::class.java) { SnapshotValidation.validate(invalid) }
    }

    @Test fun `local shorter profile chain wins restore and removes tasks referencing discarded remote revisions`() {
        val old = profile()
        val base = repository.captureSnapshot(directory)
        PersonalProfile(repository, clock).feedback(old.id, old.revision, "remote-feedback", "合成另一份副本后来的文字。")
        val claim = generate("remote-maintenance")
        analysis.planProfileMaintenance("remote-maintenance", claim)
        val incoming = repository.captureSnapshot(File(directory, "incoming").apply { mkdir() })
        val targetName = "synthetic-maintenance-restore-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, targetName).use { targetStore ->
                targetStore.restoreSnapshot(base, directory, targetStore.currentGeneration())
                targetStore.restoreSnapshot(incoming, directory, targetStore.currentGeneration(), true, targetStore.currentAnalysisGeneration())
                assertEquals(old.revision, targetStore.profileRevision(old.id)!!.revision)
                assertEquals(old.userText, targetStore.profileRevision(old.id)!!.userText)
                assertNull(targetStore.analysisRun("remote-maintenance"))
                val output = targetStore.captureSnapshot(File(directory, "target").apply { mkdir() })
                SnapshotValidation.validate(output)
                assertTrue(output.deletedAnalysisRuns.any { it.id == "remote-maintenance" })
            }
        } finally { context.deleteDatabase(targetName) }
    }

    @Test fun `typed profile knowledge cannot publish through an ordinary review when history exists`() {
        profile()
        val claim = generate("new")
        AnalysisFeedback(repository, clock).respond("new", claim, analysis.task("new").stored.version, ClaimFeedbackDecision.CONFIRMED)
        analysis.reviewClaim("new", "ordinary-review", claim, provider = reviewer())
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeLibrary(repository, clock).publish("new", claim, "ordinary-review", KnowledgeCategory.INSIGHT,
                analysis.task("new").stored.version)
        }
        assertEquals(1, repository.knowledgeHeads().size)
    }

    @Test fun `published maintenance proof independently rejects erased context even without its task`() {
        val old = profile()
        val claim = generate("new")
        AnalysisFeedback(repository, clock).respond("new", claim, analysis.task("new").stored.version, ClaimFeedbackDecision.CONFIRMED)
        val maintenance = ProfileMaintenance(repository, analysis, clock)
        maintenance.begin("new", claim)
        val relation = object : SemanticProfileRelationProvider {
            override val id = "synthetic-maintenance-storage-relation"
            override val contextWindowTokens = 20_000
            override fun prepareRelation(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
            override fun relatePrepared(request: PreparedModelRequest) = ProfileRelationCompletion(ProfileRelationResult(
                ProfileRelation.SUPPLEMENT, ProfileMaintenanceRecommendation.NARROW, "合成建议：明确记录中的具体任务情境。"))
        }
        val scan = object : SemanticReductionScanProvider {
            override val id = "synthetic-maintenance-storage-scan"
            override val contextWindowTokens = 20_000
            override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
            override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
                val evidence = JSONObject(request.original.payloadJson).getJSONArray("evidence")
                return ReductionScanCompletion(ReductionScanResult((0 until evidence.length()).map {
                    ReductionFinding(evidence.getJSONObject(it).getString("id"), ReductionRelevance.SUPPORT, "合成原文支持具体情境。")
                }))
            }
        }
        val progress = maintenance.process("new", claim, relation, scan, reviewer(), maxModelRequests = 10).progress
        assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, progress.status)
        maintenance.apply("new", claim, progress.taskVersion!!, progress.planId!!, ProfileMaintenanceAction.NARROW,
            old.id, old.revision, "narrow")
        val snapshot = repository.captureSnapshot(directory)
        assertTrue(snapshot.knowledgeRevisions.any { it.profileMaintenanceProof != null })
        val invalid = snapshot.copy(analysisRuns = snapshot.analysisRuns.filterNot { it.id == "new" },
            profileRevisions = emptyList(), profileHeads = emptyList(),
            deletedProfiles = listOf(dev.tracelife.transfer.Deletion(old.id, old.revision + 1, clock.instant())))
        assertThrows(IllegalArgumentException::class.java) { SnapshotValidation.validate(invalid) }
        assertEquals(snapshot, SnapshotJsonCodec.read(ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toByteArray().inputStream()))
    }

    private fun generate(runId: String, profileTarget: ProfileAnalysisTarget? = target): ClaimRef {
        analysis.start(AnalysisRequest("合成任务：保留具体情境和未知。", AnalysisQuery(date, date, "UTC"), profileTarget = profileTarget), runId)
        analysis.synthesize(runId, "candidate", listOf(EvidenceSelection("source", "body")), object : AnalysisProvider {
            override val id = "synthetic-maintenance-storage-generator"
            override val contextWindowTokens = 20_000
            override fun countInputTokens(request: ModelRequest) = 100
            override fun complete(request: ModelRequest): BatchProposal {
                val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim("合成解释：仅本次明确任务中，小练习可能帮助发现遗漏。", listOf(id), ClaimKind.POSSIBLE_INTERPRETATION)))
            }
        })
        return analysis.claimRef(runId, "candidate", 0)
    }

    private fun profile(): StoredProfileRevision {
        val claim = generate("old")
        AnalysisFeedback(repository, clock).respond("old", claim, analysis.task("old").stored.version, ClaimFeedbackDecision.CONFIRMED)
        analysis.reviewClaim("old", "review", claim, provider = reviewer())
        val result = ProfileCandidateGenerator(repository, analysis, clock).materialize("old", claim, analysis.task("old").stored.version)
        val id = result.profile!!.id
        PersonalProfile(repository, clock).feedback(id, 1, "independent", "合成保留的独立背景。")
        return repository.profileRevision(id)!!
    }

    private fun reviewer() = object : SemanticReviewProvider {
            override val id = "synthetic-maintenance-storage-review"
            override val contextWindowTokens = 20_000
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                val evidence = JSONObject(request.original.payloadJson).getJSONArray("evidence")
                return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, (0 until evidence.length()).map { evidence.getJSONObject(it).getString("id") }))
            }
    }
}
