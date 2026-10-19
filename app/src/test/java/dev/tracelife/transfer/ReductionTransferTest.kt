package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.actions.ActionDraft
import dev.tracelife.actions.ActionExperiments
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.AnalysisQuery
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile

/** Only synthetic drafts, independent user text and passphrases. No network or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReductionTransferTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var workspace: File
    private val databaseName = "synthetic-reduction-transfer-${UUID.randomUUID()}.db"
    private val now = Instant.parse("2026-10-19T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-19")
    private val scope = AnalysisQuery(day, day, "Asia/Shanghai")
    private val password = "synthetic-reduction-backup-passphrase".toCharArray()
    private val derivedMarker = "SYNTHETIC_PRIVATE_REDUCTION_TEXT"
    private var sequence = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, databaseName)
        analysis = ControlledAnalysis(repository, clock)
        workspace = File(context.cacheDir, "synthetic-reduction-transfer-${UUID.randomUUID()}").apply { check(mkdir()) }
    }
    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
        workspace.deleteRecursively()
    }

    @Test fun `encrypted and logical backups retain reduction graph without changing outer format or promoting restored drafts`() {
        fixture()
        val snapshot = repository.captureSnapshot(directory())
        val bytes = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toByteArray()
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(5, json.getInt("formatVersion"))
        assertEquals(6, json.getInt("sourceSchemaVersion"))
        assertEquals(6, JSONObject(snapshot.analysisRuns.single().payload).getInt("formatVersion"))
        assertEquals(snapshot, SnapshotJsonCodec.read(bytes.inputStream()))
        val archive = File(workspace, "reduction.tlb")
        BackupArchive.write(snapshot, directory(), archive, password)
        val restored = BackupArchive.read(archive, directory(), password)
        assertEquals(snapshot, restored)
        assertFalse(archive.readBytes().toString(Charsets.ISO_8859_1).contains(derivedMarker))
        val targetName = "synthetic-reduction-target-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, targetName).use { target ->
                target.restoreSnapshot(restored, directory(), target.currentGeneration())
                val targetAnalysis = ControlledAnalysis(target, clock)
                assertEquals(AnalysisStatus.STALE, targetAnalysis.task("run").status)
                assertEquals(ReductionProcessingStatus.STALE, PeriodReductionProcessor(targetAnalysis).inspect("run").status)
                assertEquals(snapshot.analysisRuns.single().payload, target.analysisRun("run")!!.payload)
                assertEquals("合成独立行动文字", ActionExperiments(target, clock).get("action")!!.value.draft.title)
            }
        } finally { context.deleteDatabase(targetName) }
    }

    @Test fun `feedback deletion erases all descendant output and old backup cannot restore it or consume extra budget`() {
        val claim = fixture()
        val backup = repository.captureSnapshot(directory())
        val before = state().calls
        val feedback = AnalysisFeedback(repository, clock).forClaim("run", claim)!!.feedback
        AnalysisFeedback(repository, clock).delete(feedback.id, feedback.version)
        assertDescendantsErased(before)
        assertIndependentText()
        repository.restoreSnapshot(backup, directory(), repository.currentGeneration(), replaceExisting = true)
        assertDescendantsErased(before)
        assertIndependentText()
        assertNull(repository.claimFeedback(feedback.id))
        assertEquals(feedback.version + 1, repository.claimFeedbackVersion(feedback.id))
        val saved = repository.captureSnapshot(directory())
        assertFalse(saved.analysisRuns.single().payload.contains(derivedMarker))
        // A sanitized graph remains portable; erased parent outputs are not required to reappear.
        val archive = File(workspace, "sanitized.tlb")
        BackupArchive.write(saved, directory(), archive, password)
        assertEquals(saved, BackupArchive.read(archive, directory(), password))
    }

    @Test fun `deletion also erases descendants created before the first feedback version existed`() {
        val claim = fixture(parentFeedback = false)
        val before = state().calls
        assertTrue(before.filter { it.purpose == CallPurpose.REDUCTION }.flatMap { it.reductionInputs }
            .flatMap { it.feedbackTokens }.any { it.claim == claim && it.version == 0 })
        val feedback = AnalysisFeedback(repository, clock).respond("run", claim, analysis.task("run").stored.version,
            ClaimFeedbackDecision.CONFIRMED, "合成后来提供的独立说明").feedback
        AnalysisFeedback(repository, clock).delete(feedback.id, feedback.version)
        assertDescendantsErased(before)
        assertIndependentText()
    }

    @Test fun `withdrawn local parent feedback wins over an older backup and blocks reusing existing drafts`() {
        val claim = fixture()
        val before = repository.captureSnapshot(directory())
        val feedback = AnalysisFeedback(repository, clock).forClaim("run", claim)!!.feedback
        val withdrawn = AnalysisFeedback(repository, clock).update(feedback.id, feedback.version,
            analysis.task("run").stored.version, ClaimFeedbackDecision.WITHDRAWN, "合成当前撤回意见").feedback
        val processor = PeriodReductionProcessor(analysis)
        assertEquals(ReductionProcessingStatus.DEPENDENCY_BLOCKED, processor.inspect("run").status)
        val provider = NeverCalledProvider()
        assertEquals(ReductionProcessingStatus.DEPENDENCY_BLOCKED, processor.process("run", provider).status)
        assertEquals(0, provider.requests)
        repository.restoreSnapshot(before, directory(), repository.currentGeneration(), replaceExisting = true)
        assertEquals(withdrawn, repository.claimFeedback(feedback.id))
        assertEquals(ReductionProcessingStatus.STALE, processor.process("run", provider).status)
        assertEquals(0, provider.requests)
        assertIndependentText()
    }

    @Test fun `a backup already carrying feedback deletion cannot contain its unerased descendant text`() {
        val claim = fixture()
        val snapshot = repository.captureSnapshot(directory())
        val feedback = AnalysisFeedback(repository, clock).forClaim("run", claim)!!.feedback
        val inconsistent = snapshot.copy(claimFeedback = snapshot.claimFeedback.filterNot { it.id == feedback.id },
            deletedClaimFeedback = listOf(Deletion(feedback.id, feedback.version, now)))
        rejectBackup(inconsistent)
    }

    @Test fun `missing forward cyclic and changed parent references are rejected before backup publication`() {
        fixture()
        val snapshot = repository.captureSnapshot(directory())
        val run = snapshot.analysisRuns.single()
        fun changed(change: (JSONArray) -> Unit): RepositorySnapshot {
            val payload = JSONObject(run.payload)
            change(payload.getJSONArray("calls"))
            return snapshot.copy(analysisRuns = listOf(run.copy(payload = payload.toString())))
        }
        // Remove a real parent without altering its child's registered reference.
        rejectBackup(changed { calls -> calls.remove(0) })
        // Reordering retains hashes and input IDs, but makes a dependency point forward.
        rejectBackup(changed { calls ->
            val intermediate = calls.getJSONObject(3)
            val root = calls.getJSONObject(4)
            calls.put(3, root); calls.put(4, intermediate)
        })
        // A self-edge has internally consistent input identity; topological validation must reject it.
        rejectBackup(changed { calls ->
            val root = state().calls.last()
            val old = root.reductionInputs.first()
            val self = ReductionGraph.input(ReductionGraph.nodeRef(root), root.requestSha256, old.feedbackTokens)
            calls.getJSONObject(4).getJSONArray("reductionInputs").put(0, JSONObject(AnalysisRunCodec.json(ReductionGraph.inputMap(self))))
        })
        rejectBackup(changed { calls ->
            calls.getJSONObject(0).getJSONObject("proposal").getJSONArray("unknowns").put("合成新增未知项，改变整个父输出")
        })
        assertEquals(run, repository.analysisRun("run"))
    }

    @Test fun `deleting task prevents old graph restoration while preserving independent feedback and actions`() {
        fixture()
        val before = repository.captureSnapshot(directory())
        analysis.delete("run", analysis.task("run").stored.version)
        repository.restoreSnapshot(before, directory(), repository.currentGeneration(), replaceExisting = true)
        assertNull(repository.analysisRun("run"))
        assertEquals(ReductionProcessingStatus.TASK_UNAVAILABLE, PeriodReductionProcessor(analysis).inspect("run").status)
        assertIndependentText()
    }

    @Test fun `reading evidence and material packages explicitly omit reduction drafts and never expose their text`() {
        fixture()
        val transfer = DataTransfer(context, repository, clock)
        val reading = File(workspace, "reading.zip")
        val evidence = File(workspace, "evidence.zip")
        val material = File(workspace, "material.zip")
        transfer.exportReadingPackage(reading)
        transfer.exportReadingPackage(evidence, ReadingExportOptions(kind = ReadingPackageKind.EVIDENCE, question = "合成问题"))
        transfer.exportPeriodReport(material, scope)
        listOf(reading, evidence, material).forEach { file -> ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).reader().use { it.readText() } }
            assertFalse(entries.values.any { derivedMarker in it })
            val completeness = JSONObject(entries.getValue("manifest.json")).getJSONObject("completeness")
            assertFalse(completeness.getBoolean("reductionDraftsIncluded"))
            assertFalse(completeness.getBoolean("analysisRunsIncluded"))
            assertTrue(entries.getValue("README.md").contains("跨批周期草稿"))
        } }
    }

    @Test fun `root feedback keeps independent wording after parent erasure and old backup restoration`() {
        val parent = fixture()
        val feedback = AnalysisFeedback(repository, clock)
        val root = ReductionGraph.statementRef(state().calls.single { it.batchKey == "root" }, 0)
        val own = feedback.respondReduction("run", root, analysis.task("run").stored.version,
            ClaimFeedbackDecision.CONFIRMED, "合成周期用户独立确认文字").feedback
        val old = repository.captureSnapshot(directory())
        val before = state().calls
        val erased = feedback.forClaim("run", parent)!!.feedback
        feedback.delete(erased.id, erased.version)
        assertDescendantsErased(before)
        assertEquals(FeedbackEvidenceState.DEPENDENCY_CHANGED, feedback.get(own.id)!!.evidenceState)
        val maintained = feedback.update(own.id, own.version, null, ClaimFeedbackDecision.WITHDRAWN,
            "合成周期用户后来独立撤回文字").feedback
        repository.restoreSnapshot(old, directory(), repository.currentGeneration(), replaceExisting = true)
        assertEquals(maintained, feedback.get(own.id)!!.feedback)
        assertFalse(repository.analysisRun("run")!!.payload.contains(derivedMarker))
        assertIndependentText()
        val snapshot = repository.captureSnapshot(directory())
        assertEquals(snapshot, SnapshotJsonCodec.read(ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }
            .toByteArray().inputStream()))
    }

    @Test fun `new intermediate feedback invalidates old ancestor zero fence and storage blocks reconfirming root`() {
        fixture()
        val feedback = AnalysisFeedback(repository, clock)
        val root = ReductionGraph.statementRef(state().calls.single { it.batchKey == "root" }, 0)
        val middle = ReductionGraph.statementRef(state().calls.single { it.batchKey == "intermediate" }, 0)
        val own = feedback.respondReduction("run", root, analysis.task("run").stored.version,
            ClaimFeedbackDecision.CONFIRMED, "合成根结论确认").feedback
        assertEquals(FeedbackEvidenceState.CURRENT, feedback.get(own.id)!!.evidenceState)
        feedback.respondReduction("run", middle, analysis.task("run").stored.version,
            ClaimFeedbackDecision.CONFIRMED, "合成中间层后来确认")
        assertEquals(FeedbackEvidenceState.DEPENDENCY_CHANGED, feedback.get(own.id)!!.evidenceState)
        val withdrawn = feedback.update(own.id, own.version, analysis.task("run").stored.version,
            ClaimFeedbackDecision.WITHDRAWN, "合成撤回根结论").feedback
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveClaimFeedback(withdrawn.copy(version = withdrawn.version + 1,
                decision = ClaimFeedbackDecision.CONFIRMED), withdrawn.version, analysis.task("run").stored.version)
        }
        assertEquals(withdrawn, repository.claimFeedback(own.id))
    }

    private fun fixture(parentFeedback: Boolean = true): ClaimRef {
        listOf("a", "b", "c").forEach { id -> repository.createRecord(RecordDraft("合成原文 $id：学习练习的观察。", Occurrence(day)), id) }
        analysis.start(AnalysisRequest("合成周期对照", scope), "run")
        listOf("a", "b", "c").forEach { id -> analysis.synthesize("run", "leaf-$id", listOf(EvidenceSelection(id, "body")), object : AnalysisProvider {
            override val id = "synthetic-leaf-provider"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
            override fun complete(request: ModelRequest): BatchProposal {
                val evidence = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim("合成解释：一次练习或许提供线索。", listOf(evidence), ClaimKind.POSSIBLE_INTERPRETATION)),
                    listOf("合成未知：不代表完整生活。"))
            }
        }) }
        val claim = analysis.claimRef("run", "leaf-a", 0)
        if (parentFeedback) AnalysisFeedback(repository, clock).respond("run", claim, analysis.task("run").stored.version,
            ClaimFeedbackDecision.CONFIRMED, "合成即将删除的父意见")
        AnalysisFeedback(repository, clock).respond("run", analysis.claimRef("run", "leaf-c", 0), analysis.task("run").stored.version,
            ClaimFeedbackDecision.CONFIRMED, "合成应保留的独立反馈")
        ActionExperiments(repository, clock).create(ActionDraft("合成独立行动文字", "合成触发", "合成做法", "合成信号", day.plusDays(1), "Asia/Shanghai"), "action")
        appendReduction("intermediate", listOf("leaf-a", "leaf-b"))
        appendReduction("root", listOf("intermediate", "leaf-c"))
        return claim
    }

    private fun appendReduction(key: String, children: List<String>) {
        val stored = requireNotNull(repository.analysisRun("run"))
        val current = AnalysisRunCodec.decode(stored.payload)
        val inputs = children.map { child ->
            val parent = current.calls.single { it.batchKey == child }
            val ref = ReductionGraph.nodeRef(parent)
            val tokens = ReductionGraph.claimRefs(current.calls, ref).map { claim ->
                ReductionFeedbackToken(claim, repository.claimFeedbackVersion(AnalysisRunCodec.feedbackId("run", claim)))
            }
            // Exercise compatibility fences for graphs created before derived feedback existed.
            ReductionGraph.input(ref, parent.requestSha256, tokens, includeDerivedFeedback = false)
        }
        val call = AnalysisCall(key, 1, "synthetic-reduction-provider", AnalysisRunCodec.sha256("synthetic-request-$key"),
            256, 256, emptyList(), now, CallStatus.SUCCEEDED, now, purpose = CallPurpose.REDUCTION,
            reductionInputs = inputs, reductionOutput = ReductionDigest(
                listOf(ReductionStatement("$derivedMarker $key", inputs.map { it.id })), listOf("$derivedMarker unknown $key")))
        repository.saveAnalysisRun(stored.copy(version = stored.version + 1,
            payload = AnalysisRunCodec.encode(current.copy(calls = current.calls + call))), stored.version)
    }

    private fun assertDescendantsErased(before: List<AnalysisCall>) {
        val after = state().calls
        assertEquals(before.size, after.size)
        assertEquals(before.filter { it.purpose == CallPurpose.SYNTHESIS }, after.filter { it.purpose == CallPurpose.SYNTHESIS })
        before.filter { it.purpose == CallPurpose.REDUCTION }.forEach { previous ->
            val erased = after.single { it.batchKey == previous.batchKey }
            assertEquals(CallStatus.FAILED, erased.status)
            assertEquals("FEEDBACK_DELETED", erased.failureCode)
            assertNull(erased.reductionOutput)
            assertEquals(previous.reductionInputs, erased.reductionInputs)
            assertEquals(previous.inputBytes, erased.inputBytes)
            assertEquals(previous.inputTokens, erased.inputTokens)
            assertEquals(previous.requestSha256, erased.requestSha256)
        }
        assertEquals(before.sumOf { it.inputBytes }, after.sumOf { it.inputBytes })
        assertFalse(repository.analysisRun("run")!!.payload.contains(derivedMarker))
    }
    private fun assertIndependentText() {
        assertTrue(repository.claimFeedbacks().any { it.userText == "合成应保留的独立反馈" })
        assertEquals("合成独立行动文字", ActionExperiments(repository, clock).get("action")!!.value.draft.title)
    }
    private fun rejectBackup(snapshot: RepositorySnapshot) {
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(snapshot) }
        val file = File(workspace, "invalid-${sequence++}.tlb")
        assertThrows(InvalidBackupException::class.java) { BackupArchive.write(snapshot, directory(), file, password) }
        assertFalse(file.exists())
    }
    private fun state() = AnalysisRunCodec.decode(repository.analysisRun("run")!!.payload)
    private fun directory() = File(workspace, "files-${sequence++}").apply { check(mkdir()) }
    private class NeverCalledProvider : AnalysisProvider {
        var requests = 0
        override val id = "synthetic-must-not-dispatch"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        override fun complete(request: ModelRequest): BatchProposal { requests++; error("No request may be dispatched") }
    }
}
