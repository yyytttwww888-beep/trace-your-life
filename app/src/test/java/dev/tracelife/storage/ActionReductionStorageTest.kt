package dev.tracelife.storage

import android.content.Context
import dev.tracelife.actions.ActionDraft
import dev.tracelife.actions.ActionExperiments
import dev.tracelife.actions.ActionPeriodReflection
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.transfer.InvalidBackupException
import dev.tracelife.transfer.RepositorySnapshot
import dev.tracelife.transfer.SnapshotJsonCodec
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

/** Synthetic plans and deterministic model responses; no credentials or network access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActionReductionStorageTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var feedback: AnalysisFeedback
    private lateinit var actions: ActionExperiments
    private val database = "synthetic-action-reduction-storage-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-19T04:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-19")
    private val scope = AnalysisQuery(day, day, "Asia/Shanghai")
    private val directories = mutableListOf<File>()
    private val ownText = "合成用户独立确认文字，须在派生分析清理后保留"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        feedback = AnalysisFeedback(repository, clock)
        actions = ActionExperiments(repository, clock)
    }

    @After fun teardown() {
        repository.close()
        context.deleteDatabase(database)
        directories.forEach { it.deleteRecursively() }
    }

    @Test fun `backup round trip retains action scans omitted from final supporting evidence`() {
        val fixture = fixture()
        val note = fixture.note
        assertTrue(note.evidence.isEmpty())
        assertEquals(listOf("action-a"), note.actionEvidence.map { it.actionId })
        assertEquals(setOf("action-a", "action-b"), KnowledgeValidation.allActionEvidence(note).map { it.actionId }.toSet())
        val proof = requireNotNull(note.reductionProof)
        assertEquals(2, proof.originalActionEvidence.size)
        assertEquals(1, proof.scans.flatMap { it.result.findings }.count { it.classification == ReductionRelevance.IRRELEVANT })
        assertEquals(note, KnowledgeCodec.decode(KnowledgeCodec.encode(note)))
        val snapshot = capture()
        val encoded = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toByteArray()
        val decoded = SnapshotJsonCodec.read(encoded.inputStream())
        assertEquals(snapshot, decoded)
        withTarget { target ->
            target.restoreSnapshot(decoded, directory(), target.currentGeneration())
            assertEquals(note, target.knowledgeRevision(note.id))
            assertEquals(proof, target.knowledgeRevision(note.id)!!.reductionProof)
            assertEquals(ownText, target.claimFeedback(fixture.own.id)!!.userText)
        }
    }

    @Test fun `standalone publication still validates hash and presence of irrelevant action original`() {
        fixture()
        // A historical publication may outlive its task. Its complete scan proof still needs
        // every original action, including sources not selected for the final review.
        val snapshot = capture().copy(analysisRuns = emptyList())
        SnapshotValidation.validate(snapshot)
        val altered = snapshot.copy(actionRevisions = snapshot.actionRevisions.map { action ->
            if (action.id == "action-b") action.copy(draft = action.draft.copy(behavior = "合成被替换的历史计划")) else action
        })
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(altered) }
        val missing = snapshot.copy(actionRevisions = snapshot.actionRevisions.filterNot { it.id == "action-b" },
            actionHeads = snapshot.actionHeads.filterNot { it.id == "action-b" })
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(missing) }
    }

    @Test fun `restore retains local irrelevant action and purges incompatible imported reduction history`() {
        val fixture = fixture()
        val snapshot = capture()
        withTarget { target ->
            ActionExperiments(target, clock).create(draft("b").copy(behavior = "合成本地独立计划优先"), "action-b")
            target.restoreSnapshot(snapshot, directory(), target.currentGeneration(), replaceExisting = true,
                expectedAnalysisGeneration = target.currentAnalysisGeneration())
            assertEquals("合成本地独立计划优先", target.actionRevision("action-b")!!.draft.behavior)
            assertNotNull(target.actionRevision("action-a"))
            assertNull(target.analysisRun("run"))
            assertNull(target.knowledgeRevision(fixture.note.id))
            assertTrue(target.knowledgeDeleted(fixture.note.id))
            assertEquals(ownText, target.claimFeedback(fixture.own.id)!!.userText)
            val sanitized = target.captureSnapshot(directory())
            assertTrue(sanitized.deletedAnalysisRuns.any { it.id == "run" })
            SnapshotValidation.validate(sanitized)
        }
    }

    @Test fun `deleting irrelevant action removes all machine revisions and older backup cannot restore them`() {
        val fixture = fixture()
        val library = KnowledgeLibrary(repository, clock)
        val revised = library.publishReduction("run", fixture.target, "final", version(), expectedRevision = 1,
            topics = setOf("合成学习"))
        assertEquals(2, revised.revision)
        val old = capture()
        actions.delete("action-b", 1)
        assertNull(repository.analysisRun("run"))
        assertTrue(repository.knowledgeHistory(fixture.note.id).isEmpty())
        assertEquals(ownText, repository.claimFeedback(fixture.own.id)!!.userText)
        assertNotNull(actions.get("action-a"))
        repository.restoreSnapshot(old, directory(), repository.currentGeneration(), replaceExisting = true,
            expectedAnalysisGeneration = repository.currentAnalysisGeneration())
        assertNull(repository.actionRevision("action-b"))
        assertNull(repository.analysisRun("run"))
        assertNull(repository.knowledgeRevision(fixture.note.id))
        assertEquals(ownText, repository.claimFeedback(fixture.own.id)!!.userText)
        val sanitized = capture()
        assertEquals(2, sanitized.deletedKnowledge.single { it.id == fixture.note.id }.lastRevision)
        assertTrue(sanitized.deletedAnalysisRuns.any { it.id == "run" })
        SnapshotValidation.validate(sanitized)
    }

    @Test fun `deleting action leaf feedback clears derived body and preserves root confirmation and action text`() {
        val fixture = fixture()
        val old = capture()
        feedback.delete(fixture.parent.id, fixture.parent.version)
        assertNull(repository.knowledgeRevision(fixture.note.id))
        assertFalse(requireNotNull(repository.analysisRun("run")).payload.contains("SYNTHETIC_ACTION_REDUCTION_ROOT"))
        assertEquals(ownText, repository.claimFeedback(fixture.own.id)!!.userText)
        assertNotNull(actions.get("action-a"))
        assertNotNull(actions.get("action-b"))
        repository.restoreSnapshot(old, directory(), repository.currentGeneration(), replaceExisting = true,
            expectedAnalysisGeneration = repository.currentAnalysisGeneration())
        assertNull(repository.knowledgeRevision(fixture.note.id))
        assertFalse(requireNotNull(repository.analysisRun("run")).payload.contains("SYNTHETIC_ACTION_REDUCTION_ROOT"))
        assertEquals(ownText, repository.claimFeedback(fixture.own.id)!!.userText)
        SnapshotValidation.validate(capture())
    }

    private data class Fixture(val note: StoredKnowledgeRevision, val target: ReductionStatementRef,
        val own: StoredClaimFeedback, val parent: StoredClaimFeedback)

    private fun fixture(): Fixture {
        actions.create(draft("a"), "action-a")
        actions.create(draft("b"), "action-b")
        ActionPeriodReflection(analysis).start(AnalysisRequest("合成：对照不同计划，哪些执行情况仍未知？", scope,
            limits = AnalysisLimits(inputTokenLimit = 30_000)), "run")
        val provider = SyntheticProvider()
        analysis.actionPage("run").entries.forEach { receipt ->
            analysis.synthesizeActions("run", "leaf-${receipt.actionId}", emptyList(), listOf(receipt), provider)
        }
        val parentRef = analysis.claimRef("run", "leaf-action-b", 0)
        val parent = feedback.respond("run", parentRef, version(), ClaimFeedbackDecision.CONFIRMED,
            "合成局部判断的独立用户反馈").feedback
        fun node(key: String) = ReductionGraph.nodeRef(analysis.task("run").state.calls.single { it.batchKey == key })
        analysis.reduce("run", "root", listOf(node("leaf-action-a"), node("leaf-action-b")), provider)
        val target = analysis.reductionStatementRef("run", node("root"), 0)
        val own = feedback.respondReduction("run", target, version(), ClaimFeedbackDecision.CONFIRMED, ownText).feedback
        val sources = analysis.reductionReviewContext("run", target).originalActionEvidence
        analysis.scanReduction("run", "scan", target, sources.map { it.id }, provider)
        analysis.reviewReduction("run", "final", target, provider)
        val note = KnowledgeLibrary(repository, clock).publishReduction("run", target, "final", version())
        return Fixture(note, target, own, parent)
    }

    private fun draft(label: String) = ActionDraft("合成计划 $label", "合成触发 $label", "合成行为 $label",
        "合成观察信号 $label", day.plusDays(7), "Asia/Shanghai")
    private fun version() = analysis.task("run").stored.version
    private fun directory() = File(context.cacheDir, "synthetic-action-reduction-${UUID.randomUUID()}")
        .apply { check(mkdir()); directories += this }
    private fun capture(): RepositorySnapshot = repository.captureSnapshot(directory())
    private fun withTarget(block: (LifeRepository) -> Unit) {
        val name = "synthetic-action-reduction-target-${UUID.randomUUID()}.db"
        val target = LifeRepository(context, clock, name)
        try { block(target) } finally { target.close(); context.deleteDatabase(name) }
    }

    private class SyntheticProvider : AnalysisProvider, SemanticReductionScanProvider, SemanticReviewProvider {
        override val id = "synthetic-action-reduction-storage"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray(Charsets.UTF_8).size
        override fun complete(request: ModelRequest): BatchProposal {
            val json = JSONObject(request.payloadJson)
            val derived = json.has("derivedInputs")
            val entries = json.getJSONArray(if (derived) "derivedInputs" else "actionEvidence")
            val refs = (0 until entries.length()).map { entries.getJSONObject(it).getString("id") }
            return BatchProposal(listOf(ProposedClaim(if (derived) "SYNTHETIC_ACTION_REDUCTION_ROOT 合成计划存在，执行未知。"
                else "合成局部计划已保存，不能证明执行。", refs, ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareScan(request: ModelRequest) = prepare(request)
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
            val entries = JSONObject(request.original.payloadJson).getJSONArray("actionEvidence")
            return ReductionScanCompletion(ReductionScanResult((0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                ReductionFinding(entry.getString("id"), if (entry.getString("actionId") == "action-b")
                    ReductionRelevance.IRRELEVANT else ReductionRelevance.SUPPORT, "合成：逐个计划核对相关性")
            }))
        }
        override fun prepareReview(request: ModelRequest) = prepare(request)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val entries = JSONObject(request.original.payloadJson).getJSONArray("actionEvidence")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED,
                (0 until entries.length()).map { entries.getJSONObject(it).getString("id") }))
        }
    }
}
