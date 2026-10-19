package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.actions.ActionDraft
import dev.tracelife.actions.ActionExperiments
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.reports.PeriodReports
import dev.tracelife.storage.*
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

/** Synthetic originals and user text only; providers are deterministic and never use the network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReductionPublicationTransferTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var feedback: AnalysisFeedback
    private lateinit var knowledge: KnowledgeLibrary
    private lateinit var workspace: File
    private val databaseName = "synthetic-period-publication-${UUID.randomUUID()}.db"
    private val now = Instant.parse("2026-10-19T04:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-19")
    private val scope = AnalysisQuery(day, day, "Asia/Shanghai")
    private val ownText = "合成周期用户独立文字，不能随机器结论清除"
    private var sequence = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, databaseName)
        analysis = ControlledAnalysis(repository, clock)
        feedback = AnalysisFeedback(repository, clock)
        knowledge = KnowledgeLibrary(repository, clock)
        workspace = File(context.cacheDir, "synthetic-period-publication-${UUID.randomUUID()}").apply { check(mkdir()) }
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
        workspace.deleteRecursively()
    }

    @Test fun `publication proof survives both backup codecs without changing outer format or becoming current on restore`() {
        val fixture = fixture()
        val note = fixture.note
        assertEquals(5, JSONObject(KnowledgeCodec.encode(note)).getInt("formatVersion"))
        assertEquals(note, KnowledgeCodec.decode(KnowledgeCodec.encode(note)))
        assertEquals(3, KnowledgeValidation.allEvidence(note).size)
        assertEquals(2, note.evidence.size)
        val snapshot = repository.captureSnapshot(directory())
        val bytes = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toByteArray()
        assertEquals(7, JSONObject(bytes.toString(Charsets.UTF_8)).getInt("formatVersion"))
        assertEquals(8, snapshot.sourceSchemaVersion)
        assertEquals(snapshot, SnapshotJsonCodec.read(bytes.inputStream()))
        val archive = File(workspace, "proof.tlb")
        val password = "synthetic-period-proof-passphrase".toCharArray()
        BackupArchive.write(snapshot, directory(), archive, password)
        assertEquals(snapshot, BackupArchive.read(archive, directory(), password))
        repository.restoreSnapshot(snapshot, directory(), repository.currentGeneration(), replaceExisting = true)
        assertEquals(note, repository.knowledgeRevision(note.id))
        assertEquals(KnowledgeEvidenceState.STALE, knowledge.get(note.id)!!.evidenceState)
        assertEquals(ownText, repository.claimFeedback(fixture.own.id)!!.userText)
    }

    @Test fun `deleting deep parent removes all published revisions and old backup cannot revive machine text`() {
        val fixture = fixture()
        val revised = knowledge.publishReduction("run", fixture.target, "final", version(), 1, setOf("learning", "synthetic"))
        assertEquals(2, revised.revision)
        val old = repository.captureSnapshot(directory())
        feedback.delete(fixture.parent!!.id, fixture.parent.version)
        assertNull(repository.knowledgeRevision(fixture.note.id))
        assertTrue(repository.knowledgeHistory(fixture.note.id).isEmpty())
        assertEquals(ownText, repository.claimFeedback(fixture.own.id)!!.userText)
        assertEquals(FeedbackEvidenceState.DEPENDENCY_CHANGED, feedback.get(fixture.own.id)!!.evidenceState)
        repository.restoreSnapshot(old, directory(), repository.currentGeneration(), replaceExisting = true)
        val sanitized = repository.captureSnapshot(directory())
        assertTrue(sanitized.knowledgeRevisions.isEmpty())
        assertEquals(2, sanitized.deletedKnowledge.single { it.id == fixture.note.id }.lastRevision)
        assertFalse(sanitized.analysisRuns.single().payload.contains("SYNTHETIC_ROOT_PUBLICATION"))
        assertEquals(ownText, repository.claimFeedback(fixture.own.id)!!.userText)
        assertEquals("合成独立行动", ActionExperiments(repository, clock).get("action")!!.value.draft.title)
        assertEquals(sanitized, SnapshotJsonCodec.read(ByteArrayOutputStream().also { SnapshotJsonCodec.write(sanitized, it) }
            .toByteArray().inputStream()))
    }

    @Test fun `parent feedback created after publication still deletes zero-version dependent copy`() {
        val fixture = fixture(parentConfirmation = false)
        val old = repository.captureSnapshot(directory())
        val parent = feedback.respond("run", fixture.parentRef, version(), ClaimFeedbackDecision.CONFIRMED,
            "合成后来补充的父意见").feedback
        assertEquals(KnowledgeEvidenceState.FEEDBACK_CHANGED, knowledge.get(fixture.note.id)!!.evidenceState)
        feedback.delete(parent.id, parent.version)
        repository.restoreSnapshot(old, directory(), repository.currentGeneration(), replaceExisting = true)
        assertNull(repository.knowledgeRevision(fixture.note.id))
        assertTrue(repository.knowledgeDeleted(fixture.note.id))
        assertEquals(ownText, repository.claimFeedback(fixture.own.id)!!.userText)
    }

    @Test fun `middle statement feedback is an ancestor fence and does not disappear behind leaf-only publication checks`() {
        val fixture = fixture(middleConfirmation = true)
        val middle = feedback.forReduction("run", fixture.middle)!!.feedback
        assertTrue(KnowledgeValidation.feedbackDependencies(fixture.note).any {
            it.claim == ReductionGraph.feedbackRef(fixture.middle) && it.version == middle.version
        })
        feedback.delete(middle.id, middle.version)
        assertNull(repository.knowledgeRevision(fixture.note.id))
        assertEquals(ownText, repository.claimFeedback(fixture.own.id)!!.userText)
    }

    @Test fun `parent withdrawal blocks idempotent publication and all current exports while retaining independent text`() {
        val fixture = fixture()
        val parent = requireNotNull(fixture.parent)
        val oldVersion = version()
        feedback.update(parent.id, parent.version, oldVersion, ClaimFeedbackDecision.WITHDRAWN, "合成父判断已撤回")
        assertThrows(RevisionConflictException::class.java) {
            repository.publishKnowledge(fixture.note, null, oldVersion)
        }
        assertThrows(RevisionConflictException::class.java) {
            repository.publishKnowledge(fixture.note, null, version())
        }
        assertEquals(KnowledgeEvidenceState.FEEDBACK_CHANGED, knowledge.get(fixture.note.id)!!.evidenceState)
        val snapshot = repository.captureSnapshot(directory())
        val selection = PublishedKnowledgeExport.select(snapshot, snapshot.records, snapshot.records, ReadingExportOptions())
        assertTrue(selection.entries.isEmpty())
        assertEquals(1, selection.omittedByReason["parent_feedback_changed"])
        assertEquals(fixture.note, repository.knowledgeRevision(fixture.note.id))
        assertEquals(ownText, repository.claimFeedback(fixture.own.id)!!.userText)
    }

    @Test fun `backup cannot carry a published copy beside a parent tombstone even when analysis task is absent`() {
        val fixture = fixture()
        val parent = requireNotNull(fixture.parent)
        val snapshot = repository.captureSnapshot(directory()).copy(analysisRuns = emptyList(),
            claimFeedback = repository.claimFeedbacks().filterNot { it.id == parent.id },
            deletedClaimFeedback = listOf(Deletion(parent.id, parent.version, now)))
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(snapshot) }
        assertEquals(fixture.note, repository.knowledgeRevision(fixture.note.id))
    }

    @Test fun `missing task makes derived publication historical and cannot make it eligible for reading export`() {
        fixture()
        val snapshot = repository.captureSnapshot(directory()).copy(analysisRuns = emptyList())
        SnapshotValidation.validate(snapshot)
        val selection = PublishedKnowledgeExport.select(snapshot, snapshot.records, snapshot.records, ReadingExportOptions())
        assertTrue(selection.entries.isEmpty())
        assertEquals(1, selection.omittedByReason["review_changed"])
    }

    @Test fun `scan-only originals remain linked and exported and outside scope suppress the whole publication`() {
        val fixture = fixture()
        val transfer = DataTransfer(context, repository, clock)
        val reading = File(workspace, "knowledge.zip")
        transfer.exportReadingPackage(reading)
        val entries = entries(reading)
        val body = entries.values.single { "# 已发布知识\n" in it }
        assertTrue(body.contains("分批原文筛查 IRRELEVANT"))
        assertTrue(body.contains("record-c"))
        assertFalse(entries.values.any { ownText in it })
        val raw = entries.filterKeys { it.startsWith("records/") }.values.single { "合成原始资料 c" in it }
        assertTrue(raw.contains("已发布知识（非原始证据）"))
        val filtered = File(workspace, "filtered.zip")
        transfer.exportReadingPackage(filtered, ReadingExportOptions(scope = ExportScope(topics = setOf("learning"))))
        assertFalse(entries(filtered).keys.any { it.startsWith("notes/entries/") })
        val reports = PeriodReports(repository, clock)
        val limited = reports.open(scope.copy(recordIds = setOf("record-a", "record-b")))
        assertEquals(0, limited.knowledgeCount)
        assertEquals(1, limited.omittedOutOfScopeKnowledge)
        val material = File(workspace, "material.zip")
        transfer.exportPeriodReport(material, scope)
        assertTrue(entries(material).values.any { "合成原始资料 c" in it })
        assertTrue(entries(material).values.any { "分批原文筛查 IRRELEVANT" in it })
        val evidence = File(workspace, "evidence.zip")
        transfer.exportReadingPackage(evidence, ReadingExportOptions(kind = ReadingPackageKind.EVIDENCE, question = "合成取证问题"))
        assertFalse(entries(evidence).values.any { fixture.note.claim.text in it })
    }

    @Test fun `task and source deletion keep root independent text and block older publication restoration`() {
        val fixture = fixture()
        val old = repository.captureSnapshot(directory())
        analysis.delete("run", version())
        repository.restoreSnapshot(old, directory(), repository.currentGeneration(), replaceExisting = true)
        assertNull(repository.analysisRun("run"))
        assertNull(repository.knowledgeRevision(fixture.note.id))
        assertEquals(ownText, repository.claimFeedback(fixture.own.id)!!.userText)
        assertEquals(FeedbackEvidenceState.TASK_UNAVAILABLE, feedback.get(fixture.own.id)!!.evidenceState)
        repository.deleteRecord("record-c", 1)
        repository.restoreSnapshot(old, directory(), repository.currentGeneration(), replaceExisting = true)
        assertNull(repository.record("record-c"))
        assertNull(repository.knowledgeRevision(fixture.note.id))
        assertEquals(ownText, repository.claimFeedback(fixture.own.id)!!.userText)
        assertEquals("合成独立行动", ActionExperiments(repository, clock).get("action")!!.value.draft.title)
    }

    private data class Fixture(val note: StoredKnowledgeRevision, val target: ReductionStatementRef,
        val middle: ReductionStatementRef, val own: StoredClaimFeedback, val parentRef: ClaimRef,
        val parent: StoredClaimFeedback?)

    private fun fixture(parentConfirmation: Boolean = true, middleConfirmation: Boolean = false): Fixture {
        listOf("a", "b", "c").forEach { id -> repository.createRecord(RecordDraft("合成原始资料 $id：记录一次学习观察。",
            Occurrence(day), topics = setOf(if (id == "c") "work" else "learning")), "record-$id") }
        analysis.start(AnalysisRequest("合成周期整体核对", scope), "run")
        val provider = object : AnalysisProvider {
            override val id = "synthetic-publication-provider"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
            override fun complete(request: ModelRequest): BatchProposal {
                val json = JSONObject(request.payloadJson)
                val derived = json.has("derivedInputs")
                val refs = json.getJSONArray(if (derived) "derivedInputs" else "evidence")
                return BatchProposal(listOf(ProposedClaim(if (derived) "SYNTHETIC_ROOT_PUBLICATION 合成周期解释" else "合成单批解释",
                    (0 until refs.length()).map { refs.getJSONObject(it).getString("id") }, ClaimKind.POSSIBLE_INTERPRETATION)))
            }
        }
        listOf("a", "b", "c").forEach { id -> analysis.synthesize("run", "leaf-$id", listOf(EvidenceSelection("record-$id", "body")), provider) }
        val parentRef = analysis.claimRef("run", "leaf-a", 0)
        val parent = if (parentConfirmation) feedback.respond("run", parentRef, version(), ClaimFeedbackDecision.CONFIRMED, "合成父层用户意见").feedback else null
        fun node(key: String) = ReductionGraph.nodeRef(analysis.task("run").state.calls.single { it.batchKey == key })
        analysis.reduce("run", "middle", listOf(node("leaf-a"), node("leaf-b")), provider)
        val middle = analysis.reductionStatementRef("run", node("middle"), 0)
        if (middleConfirmation) feedback.respondReduction("run", middle, version(), ClaimFeedbackDecision.CONFIRMED, "合成中间层用户意见")
        analysis.reduce("run", "root", listOf(node("middle"), node("leaf-c")), provider)
        val target = analysis.reductionStatementRef("run", node("root"), 0)
        val own = feedback.respondReduction("run", target, version(), ClaimFeedbackDecision.CONFIRMED, ownText).feedback
        val receipts = analysis.reductionReviewContext("run", target).originalEvidence
        analysis.scanReduction("run", "scan", target, receipts.map { it.id }, object : SemanticReductionScanProvider {
            override val id = "synthetic-scan-provider"
            override val contextWindowTokens = 100_000
            override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, request.payloadJson.toByteArray().size)
            override fun scanPrepared(request: PreparedModelRequest) = ReductionScanCompletion(ReductionScanResult(receipts.map {
                ReductionFinding(it.id, if (it.recordId == "record-c") ReductionRelevance.IRRELEVANT else ReductionRelevance.SUPPORT,
                    "合成原文逐项分类")
            }))
        })
        analysis.reviewReduction("run", "final", target, object : SemanticReviewProvider {
            override val id = "synthetic-final-provider"
            override val contextWindowTokens = 100_000
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, request.payloadJson.toByteArray().size)
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                val raw = JSONObject(request.payloadJson).getJSONArray("evidence")
                return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(raw.getJSONObject(0).getString("id"))))
            }
        })
        val note = knowledge.publishReduction("run", target, "final", version(), topics = setOf("learning"))
        ActionExperiments(repository, clock).create(ActionDraft("合成独立行动", "合成触发", "合成执行", "合成信号", day.plusDays(1), "Asia/Shanghai"), "action")
        return Fixture(note, target, middle, own, parentRef, parent)
    }

    private fun version() = requireNotNull(repository.analysisRun("run")).version
    private fun directory() = File(workspace, "files-${sequence++}").apply { check(mkdir()) }
    private fun entries(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { it.name to zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
    }
}
