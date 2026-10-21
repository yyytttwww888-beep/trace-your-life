package dev.tracelife.actions

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.reports.PeriodReports
import dev.tracelife.storage.*
import dev.tracelife.transfer.*
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
import java.time.*
import java.util.UUID

/** Synthetic action plans and user reports; model doubles never access a network or key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActionPeriodReflectionTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var actions: ActionExperiments
    private lateinit var analysis: ControlledAnalysis
    private lateinit var period: ActionPeriodReflection
    private val database = "synthetic-action-period-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val from = LocalDate.parse("2026-10-12")
    private val scope = AnalysisQuery(from, from.plusDays(6), "Asia/Shanghai")
    private val provider = Provider()
    private val directories = mutableListOf<File>()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        attach()
    }
    private fun attach() {
        actions = ActionExperiments(repository, clock)
        analysis = ControlledAnalysis(repository, clock)
        period = ActionPeriodReflection(analysis)
    }
    @After fun teardown() { repository.close(); context.deleteDatabase(database); directories.forEach { it.deleteRecursively() } }

    @Test fun `period routes opening plan and retrospective report without unrelated review text`() {
        clock.now = Instant.parse("2026-10-10T04:00:00Z")
        actions.create(draft(), "action")
        clock.now = Instant.parse("2026-10-11T04:00:00Z")
        actions.review("action", 1, "old-review", ActionReview(ActionExecution.UNKNOWN, "合成范围外秘密结果", ActionDecision.DEFER, from.minusDays(1)), from.plusDays(10))
        clock.now = Instant.parse("2026-10-19T04:00:00Z")
        actions.review("action", 2, "period-review", ActionReview(ActionExecution.NOT_EXECUTED, "合成：本周没有足够时间完成练习", ActionDecision.ADJUST, scope.through),
            adjustedDraft = draft().copy(behavior = "合成下轮改为五分钟", reviewOn = from.plusDays(15)))
        period.start(request(), "run")
        val page = analysis.actionPage("run")
        assertEquals(listOf(ActionEvidenceKind.OPENING_PLAN, ActionEvidenceKind.USER_REVIEW), page.entries.map { it.kind })
        val first = JSONObject(AnalysisRunCodec.json(repository.actionEvidenceBody(page.entries.first())))
        assertTrue(first.isNull("review"))
        assertFalse(first.toString().contains("秘密结果"))
        period.process("run", provider)
        val envelope = provider.requests.single()
        val review = envelope.getJSONArray("actionEvidence").getJSONObject(1)
        assertEquals("NOT_EXECUTED", review.getJSONObject("review").getString("execution"))
        assertEquals("合成先做最小练习", review.getJSONObject("reviewedPlan").getString("behavior"))
        assertEquals("合成下轮改为五分钟", review.getJSONObject("nextPlan").getString("behavior"))
        assertTrue(envelope.getString("system").contains("计划，不证明执行"))
    }

    @Test fun `out of period review routes this period adjustment deferral and stopping without old observation`() {
        for (decision in listOf(ActionDecision.ADJUST, ActionDecision.DEFER, ActionDecision.STOP)) {
            val id = "synthetic-${decision.name.lowercase()}"
            clock.now = Instant.parse("2026-10-10T04:00:00Z")
            actions.create(draft(), id)
            clock.now = Instant.parse("2026-10-14T04:00:00Z")
            val response = ActionReview(ActionExecution.EXECUTED, "合成范围外观察不能进入本期", decision, from.minusDays(1))
            when (decision) {
                ActionDecision.ADJUST -> actions.review(id, 1, "outside", response,
                    adjustedDraft = draft().copy(behavior = "合成本期新计划"))
                ActionDecision.DEFER -> actions.review(id, 1, "outside", response, from.plusDays(15))
                else -> actions.review(id, 1, "outside", response)
            }
        }
        period.start(request(), "run")
        val refs = analysis.actionPage("run").entries
        assertEquals(6, refs.size)
        assertEquals(3, refs.count { it.kind == ActionEvidenceKind.PLAN_CHANGE })
        assertEquals(0, refs.count { it.kind == ActionEvidenceKind.USER_REVIEW })
        refs.filter { it.kind == ActionEvidenceKind.PLAN_CHANGE }.forEach { ref ->
            val body = JSONObject(AnalysisRunCodec.json(repository.actionEvidenceBody(ref)))
            assertTrue(body.isNull("review")); assertTrue(body.isNull("reviewedPlan"))
            assertFalse(body.toString().contains("范围外观察"))
            assertEquals(if (ref.actionId.endsWith("stop")) "STOPPED" else "OPEN", body.getString("planStatus"))
        }
    }

    @Test fun `weekly default integrates available records and action inputs once and resumes without calls`() {
        seed()
        period.start(request(), "run")
        val result = period.process("run", provider)
        assertEquals(SynthesisProcessingStatus.INPUTS_PROCESSED, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertEquals(ActionPeriodIntegration.SINGLE_CALL_ALL_AVAILABLE_INPUTS, result.progress.integration)
        assertEquals(2, result.progress.processedActionEvents)
        assertEquals(2, result.progress.batches.single().originalFragments)
        assertEquals(0, period.process("run", provider).modelRequestsThisPass)
        repository.close(); repository = LifeRepository(context, clock, database); attach()
        assertEquals(result.progress, period.inspect("run"))
        assertEquals(0, period.process("run", provider).modelRequestsThisPass)
    }

    @Test fun `multiple batches honestly retain absence of cross batch integration`() {
        seed(); period.start(request(), "run")
        val first = period.process("run", provider, maxBatches = 1, inputsPerBatch = 1)
        assertEquals(SynthesisProcessingStatus.PASS_LIMIT_REACHED, first.status)
        assertEquals(ActionPeriodIntegration.INCOMPLETE, first.progress.integration)
        val final = period.process("run", provider, inputsPerBatch = 1)
        assertEquals(ActionPeriodIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED, final.progress.integration)
        assertFalse(final.progress.counterevidenceSearchExhaustive)
    }

    @Test fun `review rereads all original and action inputs not only claimed support`() {
        seed(); period.start(request(), "run"); period.process("run", provider)
        val ref = claim()
        AnalysisFeedback(repository, clock).respond("run", ref, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        analysis.reviewClaim("run", "review", ref, provider = provider)
        val envelope = provider.reviews.single()
        assertEquals(2, envelope.getJSONArray("actionEvidence").length())
        assertEquals(2, envelope.getJSONArray("evidence").length())
        val calls = analysis.task("run").state.calls
        assertEquals(calls.first().actionEvidence, calls.last().actionEvidence)
        assertEquals(calls.first().evidence, calls.last().evidence)
        val saved = KnowledgeLibrary(repository, clock).publish("run", ref, "review", KnowledgeCategory.INSIGHT, analysis.task("run").stored.version)
        assertEquals(2, saved.actionEvidence.size)
        assertEquals(saved, KnowledgeCodec.decode(KnowledgeCodec.encode(saved)))
        assertEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository, clock).get(saved.id)!!.evidenceState)
    }

    @Test fun `publication still requires explicit confirmation and current review`() {
        seed(); period.start(request(), "run"); period.process("run", provider)
        val ref = claim()
        analysis.reviewClaim("run", "review", ref, provider = provider)
        assertThrows(IllegalArgumentException::class.java) { publish(ref) }
        AnalysisFeedback(repository, clock).respond("run", ref, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        assertThrows(IllegalArgumentException::class.java) { publish(ref) }
        analysis.reviewClaim("run", "confirmed-review", ref, provider = provider)
        assertNotNull(publish(ref, "confirmed-review"))
    }

    @Test fun `publication rejects clock rollback after a later charged attempt`() {
        seed(); val saved = prepareAndPublish(); val ref = claim()
        val earlier = clock.now
        val generated = analysis.task("run").state.calls.first()
        clock.now = earlier.plusSeconds(60)
        provider.fail = true
        assertThrows(AnalysisValidationException::class.java) {
            analysis.synthesizeActions("run", "later-failed", generated.evidence.map { EvidenceSelection(it.recordId, it.field, it.start) },
                generated.actionEvidence, provider)
        }
        clock.now = earlier.plusSeconds(30)
        assertThrows(IllegalArgumentException::class.java) { publish(ref) }
        assertEquals(saved, repository.knowledgeRevision(saved.id))
    }

    @Test fun `in scope action update during dispatch blocks returning machine text`() {
        seed(); period.start(request(), "run")
        provider.beforeComplete = {
            actions.review("action", 2, "changed", ActionReview(ActionExecution.EXECUTED, "合成补充更正：仅完成一步", ActionDecision.DEFER, scope.through), from.plusDays(20))
        }
        val result = period.process("run", provider)
        assertEquals(SynthesisProcessingStatus.STALE, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertTrue(analysis.task("run").state.calls.none { it.status == CallStatus.SUCCEEDED })
    }

    @Test fun `retrospective action review invalidates published knowledge while future planning does not`() {
        seed(); val saved = prepareAndPublish()
        actions.update("action", 2, draft().copy(behavior = "合成下周新计划"))
        assertEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository, clock).get(saved.id)!!.evidenceState)
        actions.review("action", 3, "late-review", ActionReview(ActionExecution.EXECUTED, "合成补记：最后完成练习", ActionDecision.DEFER, scope.through), from.plusDays(20))
        assertEquals(KnowledgeEvidenceState.STALE, KnowledgeLibrary(repository, clock).get(saved.id)!!.evidenceState)
        assertEquals(SynthesisProcessingStatus.STALE, period.inspect("run").status)
    }

    @Test fun `action deletion purges linked machine content and keeps independent feedback and unrelated tasks`() {
        seed(); val saved = prepareAndPublish()
        val ref = claim()
        analysis.start(AnalysisRequest("合成无关任务", scope), "unrelated")
        actions.delete("action", 2)
        assertNull(repository.analysisRun("run"))
        assertNull(repository.knowledgeRevision(saved.id))
        assertNotNull(repository.claimFeedback(AnalysisRunCodec.feedbackId("run", ref)))
        assertNotNull(repository.analysisRun("unrelated"))
        assertEquals("合成独立确认文字", repository.claimFeedback(AnalysisRunCodec.feedbackId("run", ref))!!.userText)
    }

    @Test fun `too large single action cannot be truncated or sent beyond budget`() {
        clock.now = Instant.parse("2026-10-14T04:00:00Z")
        actions.create(draft().copy(behavior = "合".repeat(4_000)), "large")
        period.start(request().copy(limits = AnalysisLimits(inputTokenLimit = 600)), "run")
        val result = period.process("run", provider)
        assertEquals(SynthesisProcessingStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(0, result.modelRequestsThisPass)
        assertEquals(0, result.progress.processedActionEvents)
        assertTrue(analysis.task("run").state.calls.isEmpty())
    }

    @Test fun `failure consumes attempt and requires explicit continuation without automatic retry`() {
        seed(); period.start(request(), "run")
        provider.fail = true
        val result = period.process("run", provider)
        assertEquals(SynthesisProcessingStatus.RETRY_NEEDED, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertEquals(CallStatus.FAILED, analysis.task("run").state.calls.single().status)
        provider.fail = false
        assertEquals(1, period.process("run", provider).modelRequestsThisPass)
        assertEquals(2, analysis.task("run").state.calls.size)
    }

    @Test fun `empty period and unknown execution are not converted into failed behavior`() {
        period.start(request(), "empty")
        assertEquals(ActionPeriodIntegration.NO_INPUTS, period.process("empty", provider).progress.integration)
        assertTrue(provider.requests.isEmpty())
        seed(); period.start(request(), "run"); period.process("run", provider)
        val evidence = provider.requests.single().getJSONArray("actionEvidence")
        assertTrue(evidence.getJSONObject(0).isNull("review"))
        assertEquals("UNKNOWN", evidence.getJSONObject(1).getJSONObject("review").getString("execution"))
        assertTrue(provider.requests.single().getString("system").contains("UNKNOWN 或没有回看均不是未执行"))
    }

    @Test fun `action receipt cannot be injected into ordinary or another scoped task`() {
        seed(); period.start(request(), "run")
        val refs = analysis.actionPage("run").entries
        analysis.start(AnalysisRequest("合成普通任务", scope), "plain")
        assertThrows(IllegalArgumentException::class.java) { analysis.synthesizeActions("plain", "bad", emptyList(), refs, provider) }
        period.start(request().copy(scope = scope.copy(from = from.plusDays(30), through = from.plusDays(35))), "other")
        assertThrows(IllegalArgumentException::class.java) { analysis.synthesizeActions("other", "bad", emptyList(), refs, provider) }
        assertTrue(analysis.task("plain").state.calls.isEmpty())
    }

    @Test fun `full backup carries exact action proofs while missing action source is rejected`() {
        seed(); val saved = prepareAndPublish()
        val snapshot = capture()
        val bytes = java.io.ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toByteArray()
        val decoded = SnapshotJsonCodec.read(bytes.inputStream())
        assertEquals(saved, decoded.knowledgeRevisions.single())
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(decoded.copy(actionRevisions = emptyList(), actionHeads = emptyList())) }
        withTarget { target ->
            target.restoreSnapshot(decoded, directory(), target.currentGeneration())
            assertEquals(saved, target.knowledgeRevision(saved.id))
            assertEquals(2, target.actionHistory("action").size)
        }
    }

    @Test fun `local action chain wins restore and incompatible imported machine text is purged`() {
        seed(); val saved = prepareAndPublish(); val snapshot = capture()
        withTarget { target ->
            val localClock = Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC)
            ActionExperiments(target, localClock).create(draft().copy(behavior = "合成本地不同计划"), "action")
            target.restoreSnapshot(snapshot, directory(), target.currentGeneration(), true, target.currentAnalysisGeneration())
            assertEquals("合成本地不同计划", target.actionRevision("action")!!.draft.behavior)
            assertNull(target.analysisRun("run")); assertNull(target.knowledgeRevision(saved.id))
            assertNotNull(target.claimFeedback(AnalysisRunCodec.feedbackId("run", claim())))
        }
    }

    @Test fun `generic reading includes action knowledge while legacy period material reports its omission`() {
        seed(); prepareAndPublish(); val snapshot = capture()
        val current = snapshot.records.groupBy { it.id }.map { (_, versions) -> versions.maxBy { it.revision } }
        val selection = PublishedKnowledgeExport.select(snapshot, current, current, ReadingExportOptions())
        assertTrue(selection.omittedByReason.isEmpty())
        assertEquals(1, selection.entries.size)
        assertEquals(1, PeriodReports(repository, clock).open(scope).omittedActionEvidenceKnowledge)
    }

    @Test fun `new payload versions reject mixed legacy action fields and retain old task format`() {
        seed(); period.start(request(), "run"); period.process("run", provider)
        val payload = JSONObject(analysis.task("run").stored.payload)
        assertEquals(18, payload.getInt("formatVersion"))
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(payload.put("formatVersion", 9).toString()) }
        val ordinary = JSONObject(AnalysisRunCodec.encode(AnalysisRunState(AnalysisRequest("合成旧任务", scope))))
        ordinary.remove("feedbackContext"); ordinary.remove("historicalFeedbackResolutions"); ordinary.getJSONObject("request").remove("inheritHistoricalFeedback")
        ordinary.put("formatVersion", 9); ordinary.remove("actionPlan"); ordinary.getJSONObject("request").remove("actionPeriod")
        assertFalse(AnalysisRunCodec.decode(ordinary.toString()).request.actionPeriod)
    }

    private fun request() = AnalysisRequest("合成：对照本周行动与记录，下一周需要关注什么？", scope,
        limits = AnalysisLimits(inputTokenLimit = 30_000))
    private fun draft() = ActionDraft("合成练习计划", "合成学习开始时", "合成先做最小练习", "合成记录是否开始与遇到的问题", from.plusDays(12), "Asia/Shanghai")
    private fun seed() {
        clock.now = Instant.parse("2026-10-10T04:00:00Z")
        actions.create(draft(), "action")
        clock.now = Instant.parse("2026-10-19T04:00:00Z")
        actions.review("action", 1, "review-one", ActionReview(ActionExecution.UNKNOWN, "合成：没有足够执行记录，暂不能判断效果", ActionDecision.DEFER, scope.through), from.plusDays(15))
        repository.createRecord(RecordDraft("合成：这周开始看练习，但时间有限。", Occurrence(from)), "first")
        repository.createRecord(RecordDraft("合成：后来先列一个小目标，完成了一个步骤，仍有困难。", Occurrence(scope.through)), "last")
    }
    private fun claim() = period.inspect("run").batches.first().claims.first()
    private fun prepareAndPublish(): StoredKnowledgeRevision {
        period.start(request(), "run"); period.process("run", provider)
        val ref = claim()
        AnalysisFeedback(repository, clock).respond("run", ref, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED, "合成独立确认文字")
        analysis.reviewClaim("run", "review", ref, provider = provider)
        return publish(ref)
    }
    private fun publish(ref: ClaimRef, key: String = "review") = KnowledgeLibrary(repository, clock).publish("run", ref, key,
        KnowledgeCategory.INSIGHT, analysis.task("run").stored.version)
    private fun directory() = File(context.cacheDir, "synthetic-action-period-${UUID.randomUUID()}").apply { mkdirs(); directories += this }
    private fun capture() = repository.captureSnapshot(directory())
    private fun withTarget(block: (LifeRepository) -> Unit) {
        val name = "synthetic-action-period-target-${UUID.randomUUID()}.db"
        val target = LifeRepository(context, clock, name)
        try { block(target) } finally { target.close(); context.deleteDatabase(name) }
    }
    private class MutableClock : Clock() {
        var now = Instant.parse("2026-10-19T04:00:00Z")
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private class Provider : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-action-provider"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<JSONObject>()
        val reviews = mutableListOf<JSONObject>()
        var beforeComplete: (() -> Unit)? = null
        var fail = false
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        override fun complete(request: ModelRequest): BatchProposal {
            val json = JSONObject(request.payloadJson); requests += json
            beforeComplete?.also { beforeComplete = null }?.invoke()
            if (fail) error("synthetic failure")
            val refs = ids(json)
            return BatchProposal(listOf(ProposedClaim("合成：当前执行信息不足，可在下一次回看补充实际过程。", listOf(refs.first()), ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val json = JSONObject(request.original.payloadJson); reviews += json
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, ids(json)))
        }
        private fun ids(json: JSONObject) = listOf("actionEvidence", "evidence").flatMap { key ->
            val items = json.optJSONArray(key) ?: return@flatMap emptyList()
            (0 until items.length()).map { items.getJSONObject(it).getString("id") }
        }
    }
}
