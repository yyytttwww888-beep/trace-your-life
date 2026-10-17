package dev.tracelife.actions

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.reports.PeriodReports
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.KnowledgeCategory
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic user decisions and model fixtures; no network or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActionExperimentsTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var actions: ActionExperiments
    private val name = "synthetic-actions-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val day = LocalDate.parse("2026-10-17")
    private val scope = AnalysisQuery(day, day, "Asia/Shanghai")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        actions = ActionExperiments(repository, clock)
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name) }

    @Test fun `create is explicit idempotent and preserved across reopen`() {
        val source = repository.analysisSourceStamp()
        val before = repository.currentAnalysisGeneration()
        val first = actions.create(draft(), "action")
        assertEquals(ActionStatus.OPEN, first.value.status)
        assertNull(first.value.review)
        assertEquals(ActionOriginState.NOT_LINKED, first.originState)
        val generation = repository.currentAnalysisGeneration()
        assertTrue(generation > before)
        clock.now = clock.now.plusSeconds(20)
        assertEquals(first.value, actions.create(draft(), "action").value)
        assertEquals(generation, repository.currentAnalysisGeneration())
        assertThrows(IllegalArgumentException::class.java) { actions.create(draft().copy(behavior = "合成另一做法"), "action") }
        repository.close()
        repository = LifeRepository(context, clock, name)
        actions = ActionExperiments(repository, clock)
        assertEquals(first.value, actions.get("action")!!.value)
        assertEquals(source, repository.analysisSourceStamp())
    }

    @Test fun `reviews retain evaluated plans observations and future adjustments`() {
        val first = actions.create(draft(), "action")
        val continued = actions.review("action", 1, "review-one", review(ActionDecision.CONTINUE, ActionExecution.EXECUTED), day.plusDays(2))
        assertEquals(first.value.draft, continued.value.reviewedDraft)
        assertEquals(ActionExecution.EXECUTED, continued.value.review!!.execution)
        val next = continued.value.draft.copy(behavior = "合成调整：只练习一个步骤。", reviewOn = day.plusDays(3))
        val adjusted = actions.review("action", 2, "review-two", review(ActionDecision.ADJUST, ActionExecution.NOT_EXECUTED), adjustedDraft = next)
        assertEquals(continued.value.draft, adjusted.value.reviewedDraft)
        assertEquals(next, adjusted.value.draft)
        val deferred = actions.review("action", 3, "review-three", review(ActionDecision.DEFER, ActionExecution.UNKNOWN), day.plusDays(4))
        assertEquals(ActionExecution.UNKNOWN, deferred.value.review!!.execution)
        val history = actions.history("action")
        assertEquals(listOf(4, 3, 2, 1), history.map { it.value.revision })
        assertEquals(1, history.count { it.isCurrentRevision })
        assertEquals(first.value, history.last().value)
    }

    @Test fun `review operation retry does not append twice or overwrite later changes`() {
        actions.create(draft(), "action")
        val request = review(ActionDecision.CONTINUE)
        val saved = actions.review("action", 1, "operation", request, day.plusDays(1))
        actions.update("action", 2, saved.value.draft.copy(title = "合成后来修改"))
        clock.now = clock.now.plusSeconds(3 * 86_400)
        val retried = actions.review("action", 1, "operation", request, day.plusDays(1))
        assertEquals(saved.value, retried.value)
        assertFalse(retried.isCurrentRevision)
        assertEquals(3, actions.get("action")!!.value.revision)
        assertThrows(IllegalArgumentException::class.java) {
            actions.review("action", 1, "operation", request.copy(observedText = "合成不同观察"), day.plusDays(1))
        }
        assertEquals(3, actions.history("action").size)
    }

    @Test fun `stale edits reviews and deletion cannot overwrite a newer decision`() {
        actions.create(draft(), "action")
        actions.update("action", 1, draft().copy(title = "合成新决定"))
        assertThrows(RevisionConflictException::class.java) { actions.update("action", 1, draft()) }
        assertThrows(RevisionConflictException::class.java) { actions.review("action", 1, "late", review(ActionDecision.CONTINUE), day.plusDays(1)) }
        assertThrows(RevisionConflictException::class.java) { actions.delete("action", 1) }
        assertEquals("合成新决定", actions.get("action")!!.value.draft.title)
    }

    @Test fun `stopping preserves history excludes due list and remains idempotent on another day`() {
        actions.create(draft(), "action")
        val stopped = actions.stop("action", 1, "stop", "合成结果：暂不继续。")
        assertEquals(ActionStatus.STOPPED, stopped.value.status)
        assertFalse(stopped.due)
        assertEquals(0, actions.list(ActionFilter(dueOnly = true)).total)
        clock.now = clock.now.plusSeconds(86_400)
        assertEquals(stopped.value, actions.stop("action", 1, "stop", "合成结果：暂不继续。").value)
        assertThrows(IllegalArgumentException::class.java) { actions.update("action", 2, draft()) }
        assertEquals(2, actions.history("action").size)
    }

    @Test fun `review validation preserves unknown and requires a later next date`() {
        actions.create(draft(), "action")
        assertThrows(IllegalArgumentException::class.java) { actions.review("action", 1, "missing", review(ActionDecision.CONTINUE)) }
        assertThrows(IllegalArgumentException::class.java) { actions.review("action", 1, "today", review(ActionDecision.DEFER), day) }
        assertThrows(IllegalArgumentException::class.java) { actions.review("action", 1, "adjust", review(ActionDecision.ADJUST), day.plusDays(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            actions.review("action", 1, "future", review(ActionDecision.STOP).copy(reviewedOn = day.plusDays(1)))
        }
        assertThrows(IllegalArgumentException::class.java) { actions.review("action", 1, "blank", review(ActionDecision.STOP).copy(observedText = " ")) }
        assertEquals(1, actions.get("action")!!.value.revision)
        val deferred = actions.review("action", 1, "unknown", review(ActionDecision.DEFER), day.plusDays(1))
        assertEquals(ActionExecution.UNKNOWN, deferred.value.review!!.execution)
    }

    @Test fun `due status uses each plan timezone and not a shared device date`() {
        clock.now = Instant.parse("2026-10-17T00:30:00Z")
        actions.create(draft().copy(zoneId = "Asia/Shanghai"), "asia")
        actions.create(draft().copy(zoneId = "America/Los_Angeles"), "america")
        assertEquals(listOf("asia"), actions.list(ActionFilter(dueOnly = true)).items.map { it.id })
        assertFalse(actions.get("america")!!.due)
        clock.now = Instant.parse("2026-10-17T08:00:00Z")
        assertTrue(actions.get("america")!!.due)
        assertEquals(2, actions.list(ActionFilter(dueOnly = true)).total)
    }

    @Test fun `bounded pages freeze due evaluation and reject later action changes`() {
        clock.now = Instant.parse("2026-10-17T00:30:00Z")
        actions.create(draft().copy(zoneId = "America/Los_Angeles"), "a")
        actions.create(draft(), "b")
        val first = actions.list(limit = 1)
        clock.now = Instant.parse("2026-10-18T10:00:00Z")
        val next = actions.next(first)
        assertEquals(first.evaluatedAt, next.evaluatedAt)
        assertEquals(listOf("b"), next.items.map { it.id })
        actions.update("a", 1, draft().copy(title = "合成已改变"))
        assertThrows(StaleSnapshotException::class.java) { actions.next(first) }
        assertThrows(IllegalArgumentException::class.java) { actions.list(limit = 101) }
        assertThrows(IllegalArgumentException::class.java) { actions.history("a", limit = 101) }
    }

    @Test fun `action changes invalidate period reports without changing record source generation`() {
        val reports = PeriodReports(repository, clock)
        val report = reports.open(scope)
        val source = repository.analysisSourceStamp()
        actions.create(draft(), "action")
        assertEquals(source, repository.analysisSourceStamp())
        assertThrows(StaleSnapshotException::class.java) { reports.assertCurrent(report) }
    }

    @Test fun `current knowledge origin is required but later deletion preserves independent action text`() {
        val origin = publishKnowledge()
        val plan = draft().copy(origin = origin)
        val saved = actions.create(plan, "action")
        assertEquals(ActionOriginState.CURRENT, saved.originState)
        assertEquals(plan.behavior, saved.value.draft.behavior)
        repository.updateRecord("source", 1, RecordDraft("合成后来纠正。", Occurrence(day)))
        assertEquals(ActionOriginState.STALE, actions.get("action")!!.originState)
        assertThrows(IllegalArgumentException::class.java) { actions.create(plan, "stale-adoption") }
        repository.deleteRecord("source", 2)
        assertEquals(ActionOriginState.UNKNOWN, actions.get("action")!!.originState)
        assertEquals(plan, actions.get("action")!!.value.draft)
        val reviewed = actions.review("action", 1, "surviving-user-review", review(ActionDecision.DEFER), day.plusDays(1))
        assertEquals(plan, reviewed.value.reviewedDraft)
        assertEquals(2, actions.history("action").size)
    }

    @Test fun `origin verification and CAS reject adopting an old knowledge revision`() {
        val origin = publishKnowledge()
        val library = KnowledgeLibrary(repository, clock)
        val previous = library.get(origin.knowledgeId)!!.value
        library.publish("run", previous.claimRef, previous.reviewBatchKey, KnowledgeCategory.KNOWLEDGE_NOTE,
            ControlledAnalysis(repository, clock).task("run").stored.version, 1)
        assertThrows(IllegalArgumentException::class.java) { actions.create(draft().copy(origin = origin), "action") }
        assertNull(actions.get("action"))
        assertEquals(ActionOriginState.CURRENT, actions.create(draft().copy(origin = origin.copy(revision = 2)), "action").originState)
    }

    @Test fun `delete removes the whole history and stable identity cannot be recreated`() {
        actions.create(draft(), "action")
        actions.update("action", 1, draft().copy(title = "合成第二版"))
        val source = repository.analysisSourceStamp()
        actions.delete("action", 2)
        assertNull(actions.get("action"))
        assertTrue(actions.history("action").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { actions.create(draft(), "action") }
        assertEquals(source, repository.analysisSourceStamp())
    }

    @Test fun `maximum allowed text can still be reviewed with a separate old plan snapshot`() {
        val large = draft().copy(title = "\u0001".repeat(200), trigger = "\u0001".repeat(2_000),
            behavior = "\u0001".repeat(4_000), expectedSignal = "\u0001".repeat(2_000))
        actions.create(large, "action")
        val result = actions.review("action", 1, "large-review", review(ActionDecision.CONTINUE).copy(observedText = "\u0001".repeat(4_000)), day.plusDays(1))
        assertEquals(large, result.value.reviewedDraft)
        assertEquals(result.value, ActionCodec.decode(ActionCodec.encode(result.value)))
        assertThrows(IllegalArgumentException::class.java) { actions.create(large.copy(behavior = "x".repeat(4_001))) }
    }

    private fun publishKnowledge(): ActionOrigin {
        repository.createRecord(RecordDraft("合成记录：我尝试分成小步骤练习。", Occurrence(day)), "source")
        val analysis = ControlledAnalysis(repository, clock)
        analysis.start(AnalysisRequest("合成练习整理", scope), "run")
        analysis.runBatch("run", "extract", listOf(EvidenceSelection("source", "body")), object : AnalysisProvider {
            override val id = "synthetic-action-extract"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = 100
            override fun complete(request: ModelRequest): BatchProposal {
                val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim("合成模型陈述：用户尝试分步骤。", listOf(id), ClaimKind.REPORTED_EXPERIENCE)))
            }
        })
        val ref = analysis.claimRef("run", "extract", 0)
        analysis.reviewClaim("run", "review", ref, provider = object : SemanticReviewProvider {
            override val id = "synthetic-action-review"
            override val contextWindowTokens = 100_000
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(id)))
            }
        })
        val note = KnowledgeLibrary(repository, clock).publish("run", ref, "review", KnowledgeCategory.OBSERVATION, analysis.task("run").stored.version)
        return ActionOrigin(note.id, note.revision)
    }

    private fun draft() = ActionDraft("合成行动实验", "晚饭后", "合成用户选择：练习一个小步骤。", "能记录一次实际结果", day, "Asia/Shanghai")
    private fun review(decision: ActionDecision, execution: ActionExecution = ActionExecution.UNKNOWN) =
        ActionReview(execution, "合成用户观察：当前资料只说明这一次尝试。", decision, day)
    private class MutableClock(var now: Instant = Instant.parse("2026-10-17T03:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
