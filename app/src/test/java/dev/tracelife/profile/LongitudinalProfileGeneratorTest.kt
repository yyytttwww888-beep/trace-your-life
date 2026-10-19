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
            val evidence = JSONObject(request.payloadJson).getJSONArray("evidence")
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
