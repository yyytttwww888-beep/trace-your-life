package dev.tracelife.profile

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.io.File
import java.io.ByteArrayOutputStream
import dev.tracelife.transfer.SnapshotJsonCodec
import dev.tracelife.knowledge.KnowledgeEvidenceState

/** Synthetic longitudinal records, typed providers and explicit confirmations; no network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LongitudinalProfileGeneratorTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var generator: LongitudinalProfileGenerator
    private val database = "synthetic-longitudinal-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneOffset.UTC)
    private val first = ProfileComparisonPeriod(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"))
    private val second = ProfileComparisonPeriod(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"))
    private val target = ProfileAnalysisTarget(ProfileDimension.ABILITIES_AND_STRATEGIES, ProfileLayer.LONG_TERM, listOf(first, second))
    private val model = SyntheticModel()

    @Before fun setup() { context = RuntimeEnvironment.getApplication(); open() }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `monthly default uses complete declared months and survives strict payload round trip`() {
        val run = generator.startMonthly(ProfileDimension.BEHAVIOR_AND_DECISIONS, "合成跨期策略假设", "Asia/Shanghai", id = "monthly")
        val periods = run.state.request.profileTarget!!.periods
        assertEquals(3, periods.size)
        assertEquals(LocalDate.parse("2026-07-01"), periods.first().from)
        assertEquals(LocalDate.parse("2026-09-30"), periods.last().through)
        assertEquals(run.state, AnalysisRunCodec.decode(run.stored.payload))
        assertFalse(LongitudinalProfileProtocol.targetMap(ProfileAnalysisTarget(target.dimension, ProfileLayer.RECENT_STATE)).containsKey("periods"))
        assertThrows(IllegalArgumentException::class.java) {
            LongitudinalProfileProtocol.parseTarget(LongitudinalProfileProtocol.targetMap(target), allowLongTerm = false)
        }
        val legacy = JSONObject(run.stored.payload).put("formatVersion", 9).apply {
            remove("actionPlan"); getJSONObject("request").remove("actionPeriod")
        }
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(legacy.toString()) }
    }

    @Test fun `period plan rejects one period gaps overlaps and scope mismatch`() {
        assertThrows(IllegalArgumentException::class.java) { target.copy(periods = listOf(first)) }
        assertThrows(IllegalArgumentException::class.java) { target.copy(periods = listOf(first, second.copy(from = second.from.plusDays(1)))) }
        assertThrows(IllegalArgumentException::class.java) { target.copy(periods = listOf(first, first)) }
        assertThrows(IllegalArgumentException::class.java) { generator.start(request().copy(scope = request().scope.copy(from = first.from.plusDays(1))), "bad-scope") }
    }

    @Test fun `unknown and straddling dates never supply missing second period`() {
        record("first", first.from)
        repository.createRecord(RecordDraft("合成未知日期：采用小步骤。"), "unknown")
        repository.createRecord(RecordDraft("合成跨期摘要：重复描述同一个事件。", Occurrence(first.through, second.from)), "overlap")
        generator.start(request(), "insufficient")
        val report = generator.process("insufficient", model, model)
        assertEquals(LongitudinalProfileStatus.INSUFFICIENT_PERIODS, report.status)
        assertEquals(listOf(1, 0), report.progress.periods.map { it.datedRecordsWithText })
        assertEquals(0, model.requests)
        assertFalse(report.progress.independentEventsEstablished)
    }

    @Test fun `single period output is charged and rejected despite valid cross period inputs`() {
        seedTwo(); generator.start(request(), "bad-output")
        model.onlyFirst = true
        val report = generator.process("bad-output", model, model)
        assertEquals(LongitudinalProfileStatus.RETRY_NEEDED, report.status)
        assertEquals(1, report.modelRequestsThisPass)
        assertEquals("INVALID_RESPONSE", analysis.task("bad-output").state.calls.single().failureCode)
        assertEquals(0, report.progress.candidates.total)
    }

    @Test fun `supported review must cite more than one dated period and task restores enforce actual dates`() {
        seedTwo(); generator.start(request(), "bad-review")
        model.reviewOnlyFirst = true
        val report = generator.process("bad-review", model, model)
        assertEquals(LongitudinalProfileStatus.RETRY_NEEDED, report.status)
        assertEquals(2, report.modelRequestsThisPass)
        assertEquals("INVALID_RESPONSE", analysis.task("bad-review").state.calls.last().failureCode)
        val run = analysis.task("bad-review").stored
        val sources = listOf(repository.record("first")!!, repository.record("second")!!.let {
            it.copy(draft = it.draft.copy(occurrence = Occurrence(first.from)))
        })
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.validateStored(run, sources) }
    }

    @Test fun `bounded indexed batches continue after restart beyond thirty two fragments with no false integration`() {
        repeat(38) { record("aug-$it", first.from.plusDays((it % 20).toLong())) }
        record("sep", second.from)
        repository.createRecord(RecordDraft("合成日期未知：需要保留为背景。"), "unknown")
        generator.start(request(), "many")
        val pass = generator.process("many", model, model, maxModelRequests = 2, fragmentsPerBatch = 8)
        assertEquals(LongitudinalProfileStatus.PASS_LIMIT_REACHED, pass.status)
        assertEquals(2, pass.modelRequestsThisPass)
        repository.close(); open()
        val complete = generator.process("many", model, model, maxModelRequests = 100, fragmentsPerBatch = 8)
        assertEquals(0L, complete.progress.candidates.coverage!!.remainingTextFragments)
        assertEquals(SynthesisIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED, complete.progress.candidates.integration)
        val calls = analysis.task("many").state.calls.filter { it.purpose == CallPurpose.SYNTHESIS }
        assertTrue(calls.size > 1)
        assertTrue(calls.all { it.evidence.size <= 8 && it.status == CallStatus.SUCCEEDED })
        assertEquals(40, calls.flatMap { it.evidence }.map { it.recordId }.distinct().size)
        assertTrue(calls.sumOf { it.evidence.size } > 40) // explicit repeated anchors, not independent events
        assertEquals(complete.progress.candidates.coverage!!.textCodePoints, complete.progress.candidates.coverage!!.completedTextCodePoints)
        assertEquals(0, generator.process("many", model, model).modelRequestsThisPass)
    }

    @Test fun `empty historical catalog still requires full maintenance before explicit candidate application`() {
        seedTwo(); generator.start(request(), "complete")
        val generated = generator.process("complete", model, model)
        assertEquals(2, generated.modelRequestsThisPass)
        val claim = generated.progress.candidates.candidates.single().claim
        AnalysisFeedback(repository, clock).respond("complete", claim, analysis.task("complete").stored.version, ClaimFeedbackDecision.CONFIRMED)
        analysis.reviewClaim("complete", "confirmed-local", claim, provider = model)
        assertEquals(ProfileCandidateDisposition.MAINTENANCE_REQUIRED, generator.inspect("complete").candidates.candidates.single().disposition)
        assertThrows(IllegalArgumentException::class.java) {
            ProfileCandidateGenerator(repository, analysis, clock).materialize("complete", claim, analysis.task("complete").stored.version)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeLibrary(repository, clock).publish("complete", claim, "confirmed-local", KnowledgeCategory.INSIGHT, analysis.task("complete").stored.version)
        }
        val maintenance = ProfileMaintenance(repository, analysis, clock)
        assertEquals(0, maintenance.begin("complete", claim).totalReferences)
        val reviewed = maintenance.process("complete", claim, model, model, model, maxModelRequests = 3, fragmentsPerBatch = 8)
        assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, reviewed.status)
        assertEquals(2, reviewed.progress.scannedFragments)
        val applied = maintenance.apply("complete", claim, reviewed.progress.taskVersion!!, reviewed.progress.planId!!,
            ProfileMaintenanceAction.NEW, operationId = "explicit-long-term")
        assertEquals(ProfileAdoption.CANDIDATE, applied.adoption)
        assertEquals(ProfileLayer.LONG_TERM, repository.profileRevision(applied.profile!!.id)!!.draft.layer)
        assertEquals(0, PersonalProfile(repository, clock).open().total)
        val restored = AnalysisRunCodec.decode(repository.analysisRun("complete")!!.payload)
        assertEquals(target, restored.profileMaintenancePlans.single().profileTarget)
        val published = repository.knowledgeRevision(applied.knowledge!!.knowledgeId, applied.knowledge!!.revision)!!
        assertEquals(published, KnowledgeCodec.decode(KnowledgeCodec.encode(published)))
        val legacyKnowledge = JSONObject(KnowledgeCodec.encode(published)).put("formatVersion", 4).apply {
            remove("actionPlan"); remove("actionEvidence")
        }
        assertThrows(IllegalArgumentException::class.java) { KnowledgeCodec.decode(legacyKnowledge.toString()) }
        val wrongReview = published.copy(review = published.review.copy(supportingEvidenceIds = published.review.supportingEvidenceIds.take(1)))
        val originals = listOf(repository.record("first")!!, repository.record("second")!!)
        assertThrows(IllegalArgumentException::class.java) { KnowledgeValidation.validateEvidence(wrongReview, originals) }
    }

    @Test fun `counterevidence blocks a model that incorrectly supports the longitudinal candidate`() {
        seedTwo(); record("counter", second.from.plusDays(1), "合成反例：相同情境下小步骤没有帮助。")
        generator.start(request(), "counter-run")
        val claim = generator.process("counter-run", model, model).progress.candidates.candidates.first().claim
        val maintenance = ProfileMaintenance(repository, analysis, clock)
        maintenance.begin("counter-run", claim)
        model.counter = true
        val report = maintenance.process("counter-run", claim, model, model, model, maxModelRequests = 5, fragmentsPerBatch = 8)
        assertEquals(ProfileMaintenanceStatus.RETRY_NEEDED, report.status)
        assertEquals(1, report.progress.counterevidenceFragments)
        assertFalse(report.progress.status == ProfileMaintenanceStatus.READY_TO_APPLY)
    }

    @Test fun `request budget stops before dispatch without silently dropping the second period`() {
        seedTwo(); generator.start(request().copy(limits = AnalysisLimits(inputTokenLimit = 1)), "budget")
        val report = generator.process("budget", model, model)
        assertEquals(LongitudinalProfileStatus.BUDGET_EXHAUSTED, report.status)
        assertEquals(0, report.modelRequestsThisPass)
        assertEquals(0, model.requests)
        assertEquals(2L, report.progress.candidates.coverage!!.remainingTextFragments)
    }

    @Test fun `multiple batches form one original backed candidate after explicit full maintenance and survive backup`() {
        repeat(5) { record("aug-integrate-$it", first.from.plusDays(it.toLong())) }
        record("sep-integrate", second.from)
        generator.start(request(), "integrated")
        val generated = generator.process("integrated", model, model, maxModelRequests = 100, fragmentsPerBatch = 3)
        assertEquals(LongitudinalProfileStatus.INTEGRATED, generated.status)
        assertTrue(generated.progress.integration!!.leafCount > 1)
        val claim = generated.progress.integratedCandidates.single()
        val detail = generator.integratedCandidate(generated.progress, claim)
        assertEquals(6, detail.originalEvidence.size)
        assertTrue(detail.text.contains("整体"))
        assertTrue(detail.unknowns.any { it.contains("未记录") })
        assertEquals(0, generator.process("integrated", model, model).modelRequestsThisPass)
        assertThrows(IllegalArgumentException::class.java) { analysis.reviewClaim("integrated", "shortcut", claim, provider = model) }
        val feedback = AnalysisFeedback(repository, clock)
        feedback.respond("integrated", claim, analysis.task("integrated").stored.version, ClaimFeedbackDecision.CONFIRMED)
        val maintenance = ProfileMaintenance(repository, analysis, clock)
        maintenance.begin("integrated", claim)
        val reviewed = maintenance.process("integrated", claim, model, model, model, maxModelRequests = 10, fragmentsPerBatch = 3)
        assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, reviewed.status)
        assertEquals(6, reviewed.progress.scannedFragments)
        assertEquals(6, reviewed.progress.finalRereadFragments)
        val applied = maintenance.apply("integrated", claim, reviewed.progress.taskVersion!!, reviewed.progress.planId!!,
            ProfileMaintenanceAction.NEW, operationId = "explicit-integrated")
        assertEquals(ProfileAdoption.CANDIDATE, applied.adoption)
        val published = repository.knowledgeRevision(applied.knowledge!!.knowledgeId)!!
        val proof = published.profileMaintenanceProof!!.plan.integration!!
        assertEquals(generated.progress.integration!!.leafCount, proof.leafRefs.size)
        assertEquals(6, KnowledgeValidation.allEvidence(published).size)
        assertEquals(proof.feedbackTokens.size + 1, KnowledgeValidation.feedbackDependencies(published).size)
        assertEquals(published, KnowledgeCodec.decode(KnowledgeCodec.encode(published)))
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeCodec.decode(JSONObject(KnowledgeCodec.encode(published)).put("formatVersion", 6).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.decode(JSONObject(analysis.task("integrated").stored.payload).put("formatVersion", 15).toString())
        }
        val directory = File(context.cacheDir, "synthetic-integrated-backup-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val snapshot = repository.captureSnapshot(directory)
            SnapshotValidation.validate(snapshot)
            val bytes = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toByteArray()
            val decoded = SnapshotJsonCodec.read(bytes.inputStream())
            assertEquals(snapshot.knowledgeRevisions, decoded.knowledgeRevisions)
            val ancestor = proof.feedbackTokens.first().claim
            feedback.respond("integrated", ancestor, analysis.task("integrated").stored.version, ClaimFeedbackDecision.REJECTED,
                "合成独立意见：多个锚点是同一次经历，不支持这个解释。")
            assertEquals(KnowledgeEvidenceState.FEEDBACK_CHANGED, KnowledgeLibrary(repository, clock).get(published.id)!!.evidenceState)
            assertEquals(ProfileMaintenanceStatus.DEPENDENCY_BLOCKED, maintenance.inspect("integrated", claim).status)
            val opinion = feedback.forClaim("integrated", ancestor)!!.feedback
            feedback.delete(opinion.id, opinion.version)
            assertNull(repository.analysisRun("integrated"))
            assertNull(repository.knowledgeRevision(published.id))
            repository.restoreSnapshot(decoded, directory, repository.currentGeneration(), true, repository.currentAnalysisGeneration())
            assertNull(repository.analysisRun("integrated"))
            assertNull(repository.knowledgeRevision(published.id))
            assertEquals(6, repository.captureSnapshot(File(directory, "after").apply { mkdirs() }).records.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `large integrated candidate preserves all origins and blocks final review instead of discarding context`() {
        repeat(33) { record("large-$it", first.from.plusDays((it % 20).toLong())) }
        record("large-sep", second.from)
        generator.start(request(), "large-integrated")
        val generated = generator.process("large-integrated", model, model, maxModelRequests = 100, fragmentsPerBatch = 8)
        assertEquals(LongitudinalProfileStatus.INTEGRATED, generated.status)
        val claim = generated.progress.integratedCandidates.single()
        val maintenance = ProfileMaintenance(repository, analysis, clock)
        maintenance.begin("large-integrated", claim)
        val reviewed = maintenance.process("large-integrated", claim, model, model, model, maxModelRequests = 100, fragmentsPerBatch = 8)
        assertEquals(ProfileMaintenanceStatus.RELATED_EVIDENCE_LIMIT, reviewed.status)
        assertEquals(34, reviewed.progress.scannedFragments)
        assertEquals(0, reviewed.progress.finalRereadFragments)
        assertTrue(analysis.task("large-integrated").state.calls.none { it.profileMaintenanceProof != null })
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    @Test fun `partial reduction root cannot become a maintained whole profile`() {
        repeat(5) { record("partial-$it", first.from.plusDays(it.toLong())) }; record("partial-sep", second.from)
        generator.start(request(), "partial")
        val generated = generator.process("partial", model, model, maxModelRequests = 100, fragmentsPerBatch = 3)
        val state = analysis.task("partial").state
        val leaves = state.calls.filter { it.purpose == CallPurpose.SYNTHESIS }.map(ReductionGraph::nodeRef)
        assertTrue(leaves.size > 2)
        analysis.reduce("partial", "deliberately-partial", leaves.take(2), model)
        val root = analysis.reductionNode("partial", "deliberately-partial")
        val ref = ReductionGraph.feedbackRef(analysis.reductionStatementRef("partial", root.ref, 0))
        assertThrows(IllegalArgumentException::class.java) { ProfileMaintenance(repository, analysis, clock).begin("partial", ref) }
        assertEquals(LongitudinalProfileStatus.INTEGRATED, generated.status)
    }

    @Test fun `deleting integrated target confirmation removes dependent maintenance instead of retaining a redacted root`() {
        repeat(3) { record("own-delete-$it", first.from.plusDays(it.toLong())) }; record("own-delete-sep", second.from)
        generator.start(request(), "own-delete")
        val generated = generator.process("own-delete", model, model, maxModelRequests = 100, fragmentsPerBatch = 3)
        val claim = generated.progress.integratedCandidates.single()
        val feedback = AnalysisFeedback(repository, clock)
        val response = feedback.respond("own-delete", claim, analysis.task("own-delete").stored.version, ClaimFeedbackDecision.CONFIRMED)
        ProfileMaintenance(repository, analysis, clock).begin("own-delete", claim)
        val directory = File(context.cacheDir, "synthetic-own-delete-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val before = repository.captureSnapshot(directory)
            feedback.delete(response.feedback.id, response.feedback.version)
            assertNull(repository.analysisRun("own-delete"))
            repository.restoreSnapshot(before, directory, repository.currentGeneration(), true, repository.currentAnalysisGeneration())
            assertNull(repository.analysisRun("own-delete"))
            assertNotNull(repository.record("own-delete-sep"))
            assertTrue(repository.knowledgeHeads().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `maintenance of a new candidate can retain a rejected integrated statement in its portable history proof`() {
        repeat(3) { record("history-integrate-$it", first.from.plusDays(it.toLong())) }; record("history-integrate-sep", second.from)
        generator.start(request(), "historical-integrated")
        val old = generator.process("historical-integrated", model, model, maxModelRequests = 100, fragmentsPerBatch = 3)
            .progress.integratedCandidates.single()
        AnalysisFeedback(repository, clock).respond("historical-integrated", old, analysis.task("historical-integrated").stored.version,
            ClaimFeedbackDecision.REJECTED, "合成历史纠正：过去的概括过宽，请按具体情境重看。")
        generator.start(request(), "new-integrated")
        val selected = generator.process("new-integrated", model, model, maxModelRequests = 100, fragmentsPerBatch = 3)
            .progress.integratedCandidates.single()
        AnalysisFeedback(repository, clock).respond("new-integrated", selected, analysis.task("new-integrated").stored.version,
            ClaimFeedbackDecision.CONFIRMED)
        val maintenance = ProfileMaintenance(repository, analysis, clock)
        assertEquals(1, maintenance.begin("new-integrated", selected).totalReferences)
        val checked = maintenance.process("new-integrated", selected, model, model, model, maxModelRequests = 20, fragmentsPerBatch = 4)
        assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, checked.status)
        val result = maintenance.apply("new-integrated", selected, checked.progress.taskVersion!!, checked.progress.planId!!,
            ProfileMaintenanceAction.NEW, operationId = "explicit-new-after-history")
        val published = repository.knowledgeRevision(result.knowledge!!.knowledgeId)!!
        assertEquals(ProfileMaintenanceReferenceKind.FEEDBACK, published.profileMaintenanceProof!!.plan.references.single().kind)
        val directory = File(context.cacheDir, "synthetic-history-integrated-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val snapshot = repository.captureSnapshot(directory)
            SnapshotValidation.validate(snapshot)
            val bytes = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toByteArray()
            assertEquals(snapshot.knowledgeRevisions, SnapshotJsonCodec.read(bytes.inputStream()).knowledgeRevisions)
        } finally { directory.deleteRecursively() }
    }

    private fun request() = AnalysisRequest("合成跨周期：哪些策略可以作为待确认的长期理解？", AnalysisQuery(first.from, second.through, "UTC"),
        limits = AnalysisLimits(inputTokenLimit = 30_000), profileTarget = target)
    private fun record(id: String, date: LocalDate, body: String = "合成练习：先列明确小步骤，在这个情境中完成练习。") =
        repository.createRecord(RecordDraft(body, Occurrence(date)), id)
    private fun seedTwo() { record("first", first.from); record("second", second.from) }
    private fun open() {
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        generator = LongitudinalProfileGenerator(repository, analysis, clock)
    }

    private class SyntheticModel : AnalysisProvider, SemanticReviewProvider, SemanticReductionScanProvider, SemanticProfileRelationProvider {
        override val id = "synthetic-longitudinal-v1"
        override val contextWindowTokens = 100_000
        var requests = 0; var onlyFirst = false; var reviewOnlyFirst = false; var counter = false
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            requests++
            val payload = JSONObject(request.payloadJson)
            if (payload.getString("purpose") == "REDUCTION") {
                val inputs = payload.getJSONArray("derivedInputs")
                val ids = (0 until inputs.length()).map { inputs.getJSONObject(it).getString("id") }
                return BatchProposal(listOf(ProposedClaim("合成整体假设：多个阶段的小步骤可能帮助练习，但需保留失败情境和重复事件限制。",
                    ids, ClaimKind.POSSIBLE_INTERPRETATION)), listOf("未记录的情境仍然未知。"))
            }
            val evidence = payload.getJSONArray("evidence")
            val ids = (0 until evidence.length()).map { evidence.getJSONObject(it).getString("id") }
            return BatchProposal(listOf(ProposedClaim("合成纵向假设：在八月与九月已记录的练习情境里，小步骤可能帮助推进；不代表永久稳定，其他项目仍未知。",
                if (onlyFirst) ids.take(1) else ids, ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            requests++
            val evidence = JSONObject(request.original.payloadJson).getJSONArray("evidence")
            val ids = (0 until evidence.length()).map { evidence.getJSONObject(it).getString("id") }
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED,
                supportingEvidenceIds = if (reviewOnlyFirst) ids.take(1) else ids))
        }
        override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
            requests++
            val evidence = JSONObject(request.original.payloadJson).getJSONArray("evidence")
            return ReductionScanCompletion(ReductionScanResult((0 until evidence.length()).map {
                val value = evidence.getJSONObject(it)
                ReductionFinding(value.getString("id"), if (counter && value.getString("text").contains("合成反例"))
                    ReductionRelevance.COUNTEREVIDENCE else ReductionRelevance.SUPPORT, "合成扫描分类")
            }))
        }
        override fun prepareRelation(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun relatePrepared(request: PreparedModelRequest) = ProfileRelationCompletion(
            ProfileRelationResult(ProfileRelation.UNRELATED, ProfileMaintenanceRecommendation.KEEP, "合成历史无关"))
    }
}
