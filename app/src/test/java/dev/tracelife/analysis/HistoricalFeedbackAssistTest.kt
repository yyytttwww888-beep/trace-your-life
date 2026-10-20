package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
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
import java.time.LocalDate
import java.util.UUID

/** Fabricated semantic responses exercise decisions and coverage, not measured model accuracy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HistoricalFeedbackAssistTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var workflow: ReflectionWorkflow
    private val database = "synthetic-history-assist-${UUID.randomUUID()}.db"
    private val day = LocalDate.parse("2026-10-20")
    private val generator = Generator()
    private val semantic = SemanticFixture()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, databaseName = database)
        analysis = ControlledAnalysis(repository)
        workflow = ReflectionWorkflow(repository, analysis)
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(database) }

    @Test fun `seventeen historical opinions resume across pages and suggestions never silently become user decisions`() {
        val (run, target) = generated(17)
        assertEquals(HistoricalFeedbackAssistStop.REQUEST_LIMIT, HistoricalFeedbackAssist(analysis).process(run.id, target, semantic, 1).reason)
        assertEquals(8, analysis.historicalFeedbackAssistPage(run.id, target).scanned)
        assertEquals(0, analysis.task(run.id).state.historicalFeedbackResolutions.size)
        val reopened = ControlledAnalysis(repository)
        assertEquals(HistoricalFeedbackAssistStop.COMPLETE, HistoricalFeedbackAssist(reopened).process(run.id, target, semantic, 3).reason)
        val state = reopened.task(run.id).state
        val scans = state.calls.filter { it.purpose == CallPurpose.HISTORICAL_FEEDBACK_SCAN }
        assertEquals(listOf(8, 8, 1), scans.map { it.historicalFeedbackReadIds.size })
        assertEquals(17, scans.flatMap { it.historicalFeedbackReadIds }.distinct().size)
        assertEquals(17, reopened.historicalFeedbackAssistPage(run.id, target).unresolved)
        assertTrue(state.calls.first().historicalFeedbackReadIds.size <= 8)
        assertEquals(13, JSONObject(AnalysisRunCodec.encode(state)).getInt("formatVersion"))
        assertEquals(state, AnalysisRunCodec.decode(AnalysisRunCodec.encode(state)))
        val page = reopened.historicalFeedbackAssistPage(run.id, target)
        reopened.acceptHistoricalFeedbackSuggestions(page, page.items.map { it.content.reference.id }.toSet())
        assertEquals(9, reopened.historicalFeedbackAssistPage(run.id, target).unresolved)
        assertEquals(8, repository.historicalComparisons(run.id).size)
        workflow.respond(run.id, run.findings.single().target, workflow.inspect(run.id).taskVersion, ClaimFeedbackDecision.CONFIRMED)
        assertThrows(AnalysisDependencyBlockedException::class.java) { workflow.reviewAndSave(run.id, run.findings.single().target, generator, generator) }
    }

    @Test fun `a semantic paraphrase of rejected causation remains blocked while an unrelated opinion can be accepted`() {
        val (run, target) = generated(2)
        val conflictId = analysis.task(run.id).state.feedbackContext!!.references.first().id
        semantic.assessments[conflictId] = HistoricalFeedbackAssessment.CONFLICT
        HistoricalFeedbackAssist(analysis).process(run.id, target, semantic)
        val page = analysis.historicalFeedbackAssistPage(run.id, target)
        assertEquals(1, page.attention)
        assertEquals(conflictId, page.items.first().content.reference.id)
        assertTrue(semantic.requests.single().payloadJson.contains("小步实践证明方法更有效"))
        assertTrue(semantic.requests.single().payloadJson.contains("不能据此认定方法有效"))
        assertThrows(IllegalArgumentException::class.java) {
            analysis.acceptHistoricalFeedbackSuggestions(page, page.items.map { it.content.reference.id }.toSet())
        }
        assertTrue(analysis.task(run.id).state.historicalFeedbackResolutions.isEmpty())
        val unrelated = page.items.last().content.reference.id
        analysis.acceptHistoricalFeedbackSuggestions(page, setOf(unrelated))
        assertEquals(HistoricalFeedbackChoice.UNRELATED, analysis.task(run.id).state.historicalFeedbackResolutions.single().choice)
        assertEquals(1, analysis.historicalFeedbackAssistPage(run.id, target, onlyAttention = true).items.size)
        assertEquals(1, analysis.historicalFeedbackAssistPage(run.id, target).unresolved)
    }

    @Test fun `batch confirmation cannot cover another page or overwrite a newer personal comparison`() {
        val (run, target) = generated(9)
        HistoricalFeedbackAssist(analysis).process(run.id, target, semantic)
        val page = analysis.historicalFeedbackAssistPage(run.id, target)
        val elsewhere = analysis.historicalFeedbackAssistPage(run.id, target, page.nextOffset!!).items.single().content.reference.id
        assertThrows(IllegalArgumentException::class.java) { analysis.acceptHistoricalFeedbackSuggestions(page, setOf(elsewhere)) }
        assertTrue(analysis.task(run.id).state.historicalFeedbackResolutions.isEmpty())
        val first = page.items.first().content.reference.id
        analysis.resolveHistoricalFeedback(run.id, target, first, page.runVersion, HistoricalFeedbackChoice.CONFLICT,
            "合成：我仍认为这次归因有冲突。")
        assertThrows(AnalysisConflictException::class.java) { analysis.acceptHistoricalFeedbackSuggestions(page, setOf(first)) }
        val fresh = analysis.historicalFeedbackAssistPage(run.id, target)
        assertThrows(IllegalArgumentException::class.java) { analysis.acceptHistoricalFeedbackSuggestions(fresh, setOf(first)) }
        assertEquals(HistoricalFeedbackChoice.CONFLICT, analysis.task(run.id).state.historicalFeedbackResolutions.single().choice)
    }

    @Test fun `a changed historical correction during a request cannot save suggestions or approve an old page`() {
        val (run, target) = generated(1)
        val id = analysis.task(run.id).state.feedbackContext!!.references.single().id
        semantic.before = {
            val feedback = repository.claimFeedback(id)!!
            AnalysisFeedback(repository).update(id, feedback.version, repository.analysisRun(feedback.runId)!!.version,
                ClaimFeedbackDecision.CORRECTED, "合成：现在的限制又有变化。")
        }
        val result = HistoricalFeedbackAssist(analysis).process(run.id, target, semantic)
        assertEquals(HistoricalFeedbackAssistStop.STALE, result.reason)
        assertEquals(AnalysisStatus.STALE, analysis.task(run.id).status)
        assertTrue(analysis.task(run.id).state.calls.none { it.historicalFeedbackScan != null })
        assertThrows(StaleSnapshotException::class.java) { analysis.historicalFeedbackAssistPage(run.id, target) }
    }

    @Test fun `missing comparison references fail the whole page and a retry retains the exact request identity`() {
        val (run, target) = generated(3)
        semantic.omitLast = true
        assertEquals(HistoricalFeedbackAssistStop.RETRY_NEEDED, HistoricalFeedbackAssist(analysis).process(run.id, target, semantic).reason)
        assertEquals("INVALID_RESPONSE", analysis.task(run.id).state.calls.last().failureCode)
        assertEquals(0, analysis.historicalFeedbackAssistPage(run.id, target).scanned)
        semantic.omitLast = false
        assertEquals(HistoricalFeedbackAssistStop.COMPLETE, HistoricalFeedbackAssist(analysis).process(run.id, target, semantic).reason)
        val calls = analysis.task(run.id).state.calls.filter { it.purpose == CallPurpose.HISTORICAL_FEEDBACK_SCAN }
        assertEquals(listOf(1, 2), calls.map { it.attempt })
        assertEquals(1, calls.map { it.requestSha256 }.distinct().size)
        assertEquals(semantic.requests.first(), semantic.requests.last())
        assertTrue(analysis.task(run.id).state.historicalFeedbackResolutions.isEmpty())
    }

    @Test fun `deleting an opinion outside the generation page clears its later scan and old backup cannot resurrect it`() {
        val (run, target) = generated(17)
        val before = analysis.task(run.id).state
        val lastId = before.feedbackContext!!.references.last().id
        assertFalse(lastId in before.calls.first().historicalFeedbackReadIds)
        HistoricalFeedbackAssist(analysis).process(run.id, target, semantic)
        val work = File(context.cacheDir, "synthetic-assist-backup-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val attachments = File(work, "attachments").apply { mkdirs() }
            val snapshot = repository.captureSnapshot(attachments)
            AnalysisFeedback(repository).delete(lastId, repository.claimFeedback(lastId)!!.version)
            assertNull(repository.analysisRun(run.id))
            repository.restoreSnapshot(snapshot, attachments, repository.currentGeneration(), replaceExisting = true)
            assertNull(repository.analysisRun(run.id))
            assertNull(repository.claimFeedback(lastId))
        } finally { work.deleteRecursively() }
    }

    @Test fun `cancel between requests keeps the completed page and reopening does not dispatch anything`() {
        val (run, target) = generated(17)
        var cancelled = false
        semantic.before = { cancelled = true }
        val result = HistoricalFeedbackAssist(analysis).process(run.id, target, semantic, 3) { cancelled }
        assertEquals(HistoricalFeedbackAssistStop.CANCELLED, result.reason)
        assertEquals(1, result.requests)
        assertEquals(8, ControlledAnalysis(repository).historicalFeedbackAssistPage(run.id, target).scanned)
        assertEquals(1, semantic.requests.size)
        assertEquals(0, HistoricalFeedbackAssist(analysis).process(run.id, target, semantic, 3) { true }.requests)
    }

    @Test fun `comparison suggestions round trip in the backup without inventing user decisions`() {
        val (run, target) = generated(9)
        HistoricalFeedbackAssist(analysis).process(run.id, target, semantic)
        val work = File(context.cacheDir, "synthetic-assist-roundtrip-${UUID.randomUUID()}").apply { mkdirs() }
        val restoredName = "synthetic-assist-restored-${UUID.randomUUID()}.db"
        val restored = LifeRepository(context, databaseName = restoredName)
        try {
            val attachments = File(work, "attachments").apply { mkdirs() }
            val snapshot = repository.captureSnapshot(attachments)
            restored.restoreSnapshot(snapshot, attachments, restored.currentGeneration(), replaceExisting = true)
            val restoredAnalysis = ControlledAnalysis(restored)
            val historical = restoredAnalysis.task(run.id)
            assertEquals(9, HistoricalFeedbackAssistProtocol.completed(historical.state, target).size)
            assertEquals(AnalysisStatus.STALE, historical.status)
            assertTrue(historical.state.historicalFeedbackResolutions.isEmpty())
            val callsBefore = semantic.requests.size
            assertEquals(HistoricalFeedbackAssistStop.STALE,
                HistoricalFeedbackAssist(restoredAnalysis).process(run.id, target, semantic).reason)
            assertEquals(callsBefore, semantic.requests.size)
            assertThrows(StaleSnapshotException::class.java) { restoredAnalysis.historicalFeedbackAssistPage(run.id, target) }
            assertTrue(restored.historicalComparisons(run.id).isEmpty())
            val encoded = JSONObject(repository.analysisRun(run.id)!!.payload).put("formatVersion", 12).toString()
            assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(encoded) }
        } finally { restored.close(); context.deleteDatabase(restoredName); work.deleteRecursively() }
    }

    @Test fun `missing prior statement stays uncertain and cannot be released by a model guess`() {
        val (run, target) = generated(1, deletePrevious = true)
        val id = analysis.task(run.id).state.feedbackContext!!.references.single().id
        assertEquals(HistoricalFeedbackAssistStop.RETRY_NEEDED, HistoricalFeedbackAssist(analysis).process(run.id, target, semantic).reason)
        assertEquals(0, analysis.historicalFeedbackAssistPage(run.id, target).scanned)
        semantic.assessments[id] = HistoricalFeedbackAssessment.UNCERTAIN
        assertEquals(HistoricalFeedbackAssistStop.COMPLETE, HistoricalFeedbackAssist(analysis).process(run.id, target, semantic).reason)
        val page = analysis.historicalFeedbackAssistPage(run.id, target)
        assertNull(page.items.single().content.previousClaimText)
        assertEquals(1, page.attention)
        assertThrows(IllegalArgumentException::class.java) { analysis.acceptHistoricalFeedbackSuggestions(page, setOf(id)) }
    }

    @Test fun `shared request budget stops before an unregistered page and never calls partial coverage complete`() {
        val (run, target) = generated(9, limits = AnalysisLimits(maxCalls = 2))
        val result = HistoricalFeedbackAssist(analysis).process(run.id, target, semantic, 3)
        assertEquals(HistoricalFeedbackAssistStop.BUDGET, result.reason)
        assertEquals(1, result.requests)
        val page = analysis.historicalFeedbackAssistPage(run.id, target)
        assertEquals(8, page.scanned)
        assertEquals(9, page.total)
        assertEquals(1, semantic.requests.size)
        val archived = workflow.archive(run.id, workflow.inspect(run.id).taskVersion)
        assertNotNull(archived.archivedAt)
        assertEquals(HistoricalFeedbackAssistStop.ARCHIVED, HistoricalFeedbackAssist(analysis).process(run.id, target, semantic).reason)
        assertEquals(1, semantic.requests.size)
    }

    @Test fun `deleting feedback used by a reduced target removes comparison wording and remains backup safe`() {
        val (run, target) = generated(1, reduced = true)
        HistoricalFeedbackAssist(analysis).process(run.id, target, semantic)
        val before = analysis.task(run.id).state
        assertNotNull(before.calls.last().historicalFeedbackScan)
        val first = analysis.claimRef(run.id, "leaf-a", 0)
        val ownFeedback = repository.claimFeedback(AnalysisRunCodec.feedbackId(run.id, first))!!
        AnalysisFeedback(repository).delete(ownFeedback.id, ownFeedback.version)
        val state = analysis.task(run.id).state
        val scan = state.calls.last { it.purpose == CallPurpose.HISTORICAL_FEEDBACK_SCAN }
        assertEquals("FEEDBACK_DELETED", scan.failureCode)
        assertNull(scan.historicalFeedbackScan)
        assertNull(state.calls.single { it.batchKey == "root" }.reductionOutput)
        val work = File(context.cacheDir, "synthetic-assist-redacted-${UUID.randomUUID()}").apply { mkdirs() }
        try { repository.captureSnapshot(File(work, "attachments").apply { mkdirs() }) }
        finally { work.deleteRecursively() }
    }

    private fun generated(count: Int, deletePrevious: Boolean = false, limits: AnalysisLimits = AnalysisLimits(),
        reduced: Boolean = false): Pair<ReflectionRun, ClaimRef> {
        val old = day.minusDays(14)
        repository.createRecord(RecordDraft("合成上期：任务容易而且时间更多。", Occurrence(old), setOf("学习")), "old-source")
        analysis.start(AnalysisRequest("合成历史判断", AnalysisQuery(old, old, "Asia/Shanghai"), focusTopics = setOf("学习")), "old-run")
        generator.claimCount = count
        analysis.synthesize("old-run", "old-batch", listOf(EvidenceSelection("old-source", "body")), generator)
        repeat(count) { index ->
            AnalysisFeedback(repository).respond("old-run", analysis.claimRef("old-run", "old-batch", index), analysis.task("old-run").stored.version,
                ClaimFeedbackDecision.CORRECTED, "合成纠正 $index：任务简单且时间更多，不能据此认定方法有效。")
        }
        if (deletePrevious) repository.deleteRecord("old-source", 1)
        repository.createRecord(RecordDraft("合成这周：再尝试小步骤，仍只做过一次。", Occurrence(day), setOf("学习")), "new-source")
        if (reduced) repository.createRecord(RecordDraft("合成这周补充：练习更简单。", Occurrence(day), setOf("学习")), "new-source-b")
        generator.claimCount = 1
        val started = workflow.start(AnalysisQuery(day, day, "Asia/Shanghai"), limits)
        val run = if (!reduced) workflow.process(started.id, generator).run else {
            analysis.synthesize(started.id, "leaf-a", listOf(EvidenceSelection("new-source", "body")), generator)
            analysis.synthesize(started.id, "leaf-b", listOf(EvidenceSelection("new-source-b", "body")), generator)
            AnalysisFeedback(repository).respond(started.id, analysis.claimRef(started.id, "leaf-a", 0),
                analysis.task(started.id).stored.version, ClaimFeedbackDecision.CONFIRMED)
            analysis.reduce(started.id, "root", analysis.task(started.id).state.calls.map(ReductionGraph::nodeRef), generator)
            workflow.inspect(started.id)
        }
        return run to when (val target = run.findings.single().target) {
            is ReflectionTarget.Claim -> target.ref
            is ReflectionTarget.Reduction -> ReductionGraph.feedbackRef(target.ref)
        }
    }

    private class Generator : AnalysisProvider, SemanticReviewProvider, SemanticReductionScanProvider {
        override val id = "synthetic-assist-generator"
        override val contextWindowTokens = 100_000
        var claimCount = 1
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            val envelope = JSONObject(request.payloadJson)
            val refs = envelope.getJSONArray(if (envelope.optString("purpose") == "REDUCTION") "derivedInputs" else "evidence")
            return BatchProposal(List(claimCount) { i -> ProposedClaim("合成描述 $i：小步实践证明方法更有效。",
                (0 until refs.length()).map { refs.getJSONObject(it).getString("id") }, ClaimKind.POSSIBLE_INTERPRETATION) })
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion = error("Unresolved history must prevent this dispatch")
        override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion = error("Single batch fixture")
    }
    private class SemanticFixture : HistoricalFeedbackScanProvider {
        override val id = "synthetic-history-semantic"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<ModelRequest>()
        val assessments = mutableMapOf<String, HistoricalFeedbackAssessment>()
        var before: () -> Unit = {}
        var omitLast = false
        override fun prepareHistoricalFeedbackScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun scanHistoricalFeedbackPrepared(request: PreparedModelRequest): HistoricalFeedbackScanCompletion {
            requests += request.original; before()
            val input = JSONObject(request.original.payloadJson).getJSONArray("historicalComparisons")
            val result = (0 until input.length()).map { index ->
                val id = FeedbackContextProtocol.feedbackIdFromControlKey(input.getJSONObject(index).getString("controlKey"))
                HistoricalFeedbackSuggestion(id, assessments[id] ?: HistoricalFeedbackAssessment.UNRELATED,
                    if (assessments[id] == HistoricalFeedbackAssessment.CONFLICT) "合成比较：换成小步实践，仍在重复被否定的因果归因。"
                    else "合成比较夹具：该条历史涉及另一情境。")
            }
            return HistoricalFeedbackScanCompletion(HistoricalFeedbackScan(if (omitLast) result.dropLast(1) else result), ModelUsage(80, 25))
        }
    }
}
