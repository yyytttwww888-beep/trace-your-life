package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.profile.*
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SnapshotValidation
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

/** Synthetic tasks and user choices through real backup, restoration and reading-package paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileCandidateTransferTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var generator: ProfileCandidateGenerator
    private lateinit var workspace: File
    private val name = "synthetic-profile-candidate-transfer-${UUID.randomUUID()}.db"
    private val now = Instant.parse("2026-10-19T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-19")
    private val target = ProfileAnalysisTarget(ProfileDimension.BEHAVIOR_AND_DECISIONS, ProfileLayer.CONTEXTUAL_PATTERN)
    private val password = "synthetic-profile-candidate-backup".toCharArray()
    private val claimText = "SYNTHETIC_PENDING_PROFILE_CLAIM：本次练习记录中尝试了缩小目标，尚不证明效果。"
    private val reviewText = "SYNTHETIC_PRIVATE_PROFILE_REVIEW"
    private val userText = "SYNTHETIC_INDEPENDENT_PROFILE_NOTE"
    private var sequence = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        analysis = ControlledAnalysis(repository, clock)
        generator = ProfileCandidateGenerator(repository, analysis, clock)
        workspace = File(context.cacheDir, "synthetic-profile-candidate-transfer-${UUID.randomUUID()}").apply { check(mkdir()) }
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name); workspace.deleteRecursively(); password.fill('\u0000') }

    @Test fun `typed pending generation survives encrypted restore as historical without dispatch or publication`() {
        val claim = generate()
        val snapshot = capture()
        val encoded = encode(snapshot)
        val json = JSONObject(encoded.toString(Charsets.UTF_8))
        assertEquals(10, json.getInt("formatVersion"))
        assertEquals(11, json.getInt("sourceSchemaVersion"))
        assertEquals(12, JSONObject(snapshot.analysisRuns.single().payload).getInt("formatVersion"))
        assertEquals(snapshot, SnapshotJsonCodec.read(encoded.inputStream()))
        val archive = File(workspace, "pending.tlb")
        BackupArchive.write(snapshot, directory(), archive, password)
        val restored = BackupArchive.read(archive, directory(), password)
        assertEquals(snapshot, restored)
        assertFalse(archive.readBytes().toString(Charsets.ISO_8859_1).contains(claimText))
        withTarget { store ->
            store.restoreSnapshot(restored, directory(), store.currentGeneration())
            val restoredAnalysis = ControlledAnalysis(store, clock)
            val workflow = ProfileCandidateGenerator(store, restoredAnalysis, clock)
            assertEquals(target, restoredAnalysis.task("run").state.request.profileTarget)
            assertEquals(AnalysisStatus.STALE, restoredAnalysis.task("run").status)
            assertEquals(ProfileCandidateStatus.STALE, workflow.inspect("run").status)
            val result = workflow.process("run", neverProvider, neverReviewer)
            assertEquals(0, result.modelRequestsThisPass)
            assertEquals(ProfileCandidateStatus.STALE, result.status)
            assertTrue(store.profileHeads().isEmpty())
            assertTrue(store.knowledgeHeads().isEmpty())
            assertEquals(claim, restoredAnalysis.claimRef("run", "generated", 0))
        }
    }

    @Test fun `legacy analysis versions restore without gaining a profile target`() {
        repository.createRecord(RecordDraft("合成旧任务原文。", Occurrence(day)), "source")
        analysis.start(request().copy(profileTarget = null), "legacy")
        val snapshot = capture()
        val row = snapshot.analysisRuns.single()
        (1..8).forEach { version ->
            val payload = JSONObject(row.payload).apply {
                put("formatVersion", version)
                if (version < 8) getJSONObject("request").remove("profileTarget")
                remove("profileMaintenancePlans")
                remove("actionPlan")
                getJSONObject("request").remove("actionPeriod")
                remove("feedbackContext")
                remove("historicalFeedbackResolutions")
                getJSONObject("request").remove("inheritHistoricalFeedback")
            }.toString()
            val legacy = snapshot.copy(analysisRuns = listOf(row.copy(payload = payload)))
            val decoded = SnapshotJsonCodec.read(encode(legacy).inputStream())
            withTarget { store ->
                store.restoreSnapshot(decoded, directory(), store.currentGeneration())
                assertEquals(payload, store.analysisRun("legacy")!!.payload)
                assertNull(ControlledAnalysis(store, clock).task("legacy").state.request.profileTarget)
                assertTrue(store.profileHeads().isEmpty())
            }
        }
    }

    @Test fun `backup and restore reject mixed versions invalid targets and incompatible call purpose atomically`() {
        generate()
        val snapshot = capture()
        val row = snapshot.analysisRuns.single()
        val badPayloads = listOf<(JSONObject) -> Unit>(
            { it.put("formatVersion", 7) },
            { it.put("formatVersion", 7); it.getJSONObject("request").put("profileTarget", JSONObject.NULL) },
            { it.getJSONObject("request").getJSONObject("profileTarget").put("layer", "LONG_TERM") },
            { it.getJSONObject("request").getJSONObject("profileTarget").put("dimension", "INVENTED") },
            { it.getJSONObject("request").getJSONObject("profileTarget").put("adopt", true) },
            { it.getJSONArray("calls").getJSONObject(0).put("purpose", "EXTRACTION") },
        )
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        badPayloads.forEach { mutate ->
            val payload = JSONObject(row.payload).also(mutate).toString()
            val invalid = snapshot.copy(analysisRuns = listOf(row.copy(payload = payload)))
            assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(invalid) }
            val destination = File(workspace, "invalid-${sequence++}.tlb")
            assertThrows(InvalidBackupException::class.java) { BackupArchive.write(invalid, directory(), destination, password) }
            assertFalse(destination.exists())
            assertThrows(InvalidBackupException::class.java) {
                repository.restoreSnapshot(invalid, directory(), repository.currentGeneration(), true, generation)
            }
            assertEquals(source, repository.analysisSourceStamp())
            assertEquals(generation, repository.currentAnalysisGeneration())
            assertEquals(row, repository.analysisRun("run"))
        }
    }

    @Test fun `reading export excludes pending proposals and candidates then links adopted shared knowledge`() {
        val claim = generate()
        val pending = reading()
        assertFalse(pending.values.joinToString().contains(claimText))
        assertTrue(pending.keys.none { it.startsWith("profile/") })
        val candidate = materialize(claim)
        val unadopted = reading()
        assertTrue(unadopted.keys.none { it.startsWith("profile/") })
        assertEquals(0, JSONObject(unadopted.getValue("manifest.json")).getJSONObject("profile").getInt("included"))
        assertTrue(unadopted.filterKeys { it.startsWith("notes/entries/") }.values.any { it.contains(claimText) })

        val profiles = PersonalProfile(repository, clock)
        val annotated = profiles.feedback(candidate.id, candidate.revision, "note", userText)
        profiles.adopt(candidate.id, annotated.value.revision, "adopt")
        val adopted = reading()
        val leaf = adopted.filterKeys { it.startsWith("profile/entries/") }.values.single()
        val value = repository.profileRevision(candidate.id)!!
        assertEquals(1, JSONObject(adopted.getValue("manifest.json")).getJSONObject("profile").getInt("included"))
        assertTrue(leaf.contains(value.draft.knowledge.knowledgeId))
        assertTrue(leaf.contains("../../notes/entries/"))
        assertFalse(leaf.contains(claimText))
        assertFalse(adopted.values.any { it.contains(userText) })
        assertFalse(ProfileCodec.encode(value).contains(claimText))
    }

    @Test fun `restoring an earlier generated candidate preserves the local complete declined chain`() {
        val candidate = materialize(generate())
        val old = capture()
        val profiles = PersonalProfile(repository, clock)
        val annotated = profiles.feedback(candidate.id, candidate.revision, "local-note", userText)
        val declined = profiles.decline(candidate.id, annotated.value.revision, "local-decline")
        val localHistory = repository.profileHistory(candidate.id)
        restore(old)
        assertEquals(declined.value, repository.profileRevision(candidate.id))
        assertEquals(localHistory, repository.profileHistory(candidate.id))
        assertEquals(target, AnalysisRunCodec.decode(repository.analysisRun("run")!!.payload).request.profileTarget)
        assertEquals(0, profiles.open().total)
        assertEquals(ProfileCandidateStatus.STALE, generator.inspect("run").status)
    }

    @Test fun `feedback deletion and old restoration preserve target but erase reviewed machine copies`() {
        val claim = generate()
        val candidate = materialize(claim)
        val profiles = PersonalProfile(repository, clock)
        val annotated = profiles.feedback(candidate.id, candidate.revision, "independent", userText)
        val old = capture()
        val feedback = requireNotNull(AnalysisFeedback(repository, clock).forClaim("run", claim)).feedback
        AnalysisFeedback(repository, clock).delete(feedback.id, feedback.version)
        assertSanitized(annotated.value, claim)
        restore(old)
        assertSanitized(annotated.value, claim)
        val portable = capture()
        val file = File(workspace, "sanitized.tlb")
        BackupArchive.write(portable, directory(), file, password)
        assertEquals(portable, BackupArchive.read(file, directory(), password))
    }

    @Test fun `generated profile deletion ledger prevents resurrection from a complete earlier backup`() {
        val candidate = materialize(generate())
        val old = capture()
        PersonalProfile(repository, clock).delete(candidate.id, candidate.revision)
        restore(old)
        assertNull(repository.profileRevision(candidate.id))
        assertTrue(capture().deletedProfiles.any { it.id == candidate.id })
        assertTrue(reading().keys.none { it.startsWith("profile/") })
    }

    private fun assertSanitized(profile: StoredProfileRevision, claim: ClaimRef) {
        val run = requireNotNull(repository.analysisRun("run"))
        val state = AnalysisRunCodec.decode(run.payload)
        assertEquals(target, state.request.profileTarget)
        assertFalse(run.payload.contains(reviewText))
        assertTrue(state.calls.any { it.purpose == CallPurpose.REVIEW && it.failureCode == "FEEDBACK_DELETED" && it.review == null })
        assertNull(repository.knowledgeRevision(profile.draft.knowledge.knowledgeId))
        assertEquals(profile, repository.profileRevision(profile.id))
        assertNull(repository.claimFeedback(AnalysisRunCodec.feedbackId("run", claim)))
        assertEquals(0, PersonalProfile(repository, clock).open().total)
    }

    private fun request() = AnalysisRequest("合成画像候选，仅限本次练习情境。", AnalysisQuery(day, day, "Asia/Shanghai"), profileTarget = target)
    private fun generate(): ClaimRef {
        listOf("合成先缩小练习后完成。", "合成难题缩小后仍未完成，效果未知。").forEachIndexed { index, body ->
            repository.createRecord(RecordDraft(body, Occurrence(day)), "source-$index")
        }
        generator.start(request(), "run")
        analysis.synthesize("run", "generated", listOf(EvidenceSelection("source-0", "body"), EvidenceSelection("source-1", "body")), fixtureProvider)
        return analysis.claimRef("run", "generated", 0)
    }
    private fun materialize(claim: ClaimRef): ProfileHead {
        AnalysisFeedback(repository, clock).respond("run", claim, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED, "合成用户确认。")
        analysis.reviewClaim("run", "reviewed", claim, provider = fixtureProvider)
        val result = generator.materialize("run", claim, analysis.task("run").stored.version)
        assertEquals(ProfileCandidateMaterializationStatus.CREATED, result.status)
        return requireNotNull(result.profile)
    }
    private val fixtureProvider = object : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-profile-transfer-provider"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            val evidence = JSONObject(request.payloadJson).getJSONArray("evidence")
            return BatchProposal(listOf(ProposedClaim(claimText, listOf(evidence.getJSONObject(0).getString("id")), ClaimKind.POSSIBLE_INTERPRETATION)),
                listOf("合成资料没有证明长期稳定性。"))
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val evidence = JSONObject(request.original.payloadJson).getJSONArray("evidence")
            assertEquals(2, evidence.length())
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(evidence.getJSONObject(0).getString("id")), unknowns = listOf(reviewText)))
        }
    }
    private val neverProvider = object : AnalysisProvider {
        override val id = "synthetic-forbidden-profile-provider"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = error("Restoration must not prepare a request")
        override fun complete(request: ModelRequest): BatchProposal = error("Restoration must not invoke a model")
    }
    private val neverReviewer = object : SemanticReviewProvider {
        override val id = "synthetic-forbidden-profile-reviewer"
        override val contextWindowTokens = 100_000
        override fun prepareReview(request: ModelRequest): PreparedModelRequest = error("Restoration must not prepare a review")
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion = error("Restoration must not invoke a reviewer")
    }
    private fun capture() = repository.captureSnapshot(directory())
    private fun restore(snapshot: RepositorySnapshot) = repository.restoreSnapshot(snapshot, directory(), repository.currentGeneration(), true, repository.currentAnalysisGeneration())
    private fun encode(snapshot: RepositorySnapshot) = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toByteArray()
    private fun directory() = File(workspace, "directory-${sequence++}").apply { check(mkdir()) }
    private fun reading(): Map<String, String> {
        val file = File(workspace, "reading-${sequence++}.zip")
        DataTransfer(context, repository, clock).exportReadingPackage(file, ReadingExportOptions(profileZoneId = "Asia/Shanghai"))
        return ZipFile(file).use { zip -> zip.entries().asSequence().filterNot { it.isDirectory }
            .associate { it.name to zip.getInputStream(it).bufferedReader().use { stream -> stream.readText() } } }
    }
    private fun withTarget(block: (LifeRepository) -> Unit) {
        val targetName = "synthetic-profile-candidate-target-${UUID.randomUUID()}.db"
        try { LifeRepository(context, clock, targetName).use(block) }
        finally { context.deleteDatabase(targetName) }
    }
}
