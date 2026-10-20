package dev.tracelife.conversation

import android.content.Context
import dev.tracelife.actions.ActionDraft
import dev.tracelife.actions.ActionExperiments
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
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
import java.util.UUID

/** Synthetic records and plans; exercises the phone's unified workflows against real SQLite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationActionDependencyTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var reflection: ReflectionWorkflow
    private lateinit var conversation: ConversationWorkflow
    private val database = "synthetic-conversation-action-dependency-${UUID.randomUUID()}.db"
    private val date = LocalDate.parse("2026-10-20")
    private val clock = Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val provider = SyntheticProvider()

    @Before fun setup() { context = RuntimeEnvironment.getApplication(); open() }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `reopened unified answer retains blocked ancestor without more calls or publication`() {
        repeat(17) { index ->
            repository.createRecord(RecordDraft("合成学习记录 $index：练习效果还需要继续观察。", Occurrence(date)), "synthetic-record-$index")
        }
        ActionExperiments(repository, clock).create(ActionDraft("合成学习计划", "合成午休后", "先练习五分钟", "合成练习回看报告",
            date.plusDays(7), "Asia/Shanghai"), "synthetic-action")
        val plan = conversation.prepare("今天学习计划和实际记录有什么差别？")
        assertTrue(plan.canStart)
        assertTrue(plan.includeActions)
        assertEquals(1, plan.actionEvents)
        val turn = conversation.submit(plan)
        assertTrue(provider.requests.isEmpty())

        val completed = reflection.process(turn.runId, provider, maxRequests = 5)
        assertEquals(ReflectionStatus.READY, completed.status)
        assertEquals(3, completed.modelRequestsThisPass)
        assertEquals(2, completed.run.synthesizedBatches)
        assertEquals(1, completed.run.processedActionEvents)
        val finding = completed.run.findings.single()
        assertTrue(finding.target is ReflectionTarget.Reduction)
        // Prior confirmation must not let an invalid ancestor bypass the current-result gate.
        reflection.respond(turn.runId, finding.target, completed.run.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        val task = analysis.task(turn.runId)
        val ancestor = task.state.calls.single { it.purpose == CallPurpose.ACTION_SYNTHESIS && it.actionEvidence.isNotEmpty() }
        AnalysisFeedback(repository, clock).respond(turn.runId, analysis.claimRef(turn.runId, ancestor.batchKey, 0),
            task.stored.version, ClaimFeedbackDecision.REJECTED, userText = "合成纠正：这条对行动的解释不成立。")
        val callCount = analysis.task(turn.runId).state.calls.size
        val dispatched = provider.requests.size

        repository.close()
        open()
        val reopenedTurn = conversation.turns(turn.threadId).single()
        assertEquals(turn, reopenedTurn)
        val reopened = requireNotNull(conversation.inspect(reopenedTurn))
        assertEquals(ReflectionStatus.DEPENDENCY_BLOCKED, reopened.status)
        assertFalse(reopened.canReviewFindings)
        assertEquals(finding.text, reopened.findings.single().text)
        assertEquals(finding.target, reopened.findings.single().target)
        assertNotNull(conversation.prepare("详细说说", turn.threadId, turn.id, 0).clarification)

        val continued = reflection.process(turn.runId, provider, maxRequests = 5)
        assertEquals(ReflectionStatus.DEPENDENCY_BLOCKED, continued.status)
        assertEquals(ReflectionStatus.DEPENDENCY_BLOCKED, continued.run.status)
        assertEquals(0, continued.modelRequestsThisPass)
        assertThrows(IllegalArgumentException::class.java) {
            reflection.respond(turn.runId, finding.target, continued.run.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            reflection.reviewAndSave(turn.runId, finding.target, provider, provider)
        }
        assertEquals(dispatched, provider.requests.size)
        assertEquals(callCount, analysis.task(turn.runId).state.calls.size)
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertFalse(requireNotNull(conversation.inspect(reopenedTurn)).canReviewFindings)
    }

    private fun open() {
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        reflection = ReflectionWorkflow(repository, analysis, AnalysisFeedback(repository, clock), KnowledgeLibrary(repository, clock))
        conversation = ConversationWorkflow(repository, analysis, reflection, clock)
    }

    private class SyntheticProvider : AnalysisProvider, SemanticReductionScanProvider, SemanticReviewProvider {
        override val id = "synthetic-conversation-action-dependency"
        override val contextWindowTokens = 128_000
        val requests = mutableListOf<ModelRequest>()
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            val json = JSONObject(request.payloadJson)
            val source = if (json.getString("purpose") == "REDUCTION") {
                // Deliberately cite the other leaf: every ancestor still has to remain valid.
                val inputs = json.getJSONArray("derivedInputs")
                (0 until inputs.length()).map(inputs::getJSONObject)
                    .single { it.getJSONObject("provenance").getInt("actionEvents") == 0 }
            } else {
                val actions = json.optJSONArray("actionEvidence")
                if (actions != null && actions.length() > 0) actions.getJSONObject(0)
                else json.getJSONArray("evidence").getJSONObject(0)
            }
            return BatchProposal(listOf(ProposedClaim("合成解释：计划表达意图，实际效果需要结合记录继续观察。",
                listOf(source.getString("id")), ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareScan(request: ModelRequest): PreparedModelRequest =
            throw AssertionError("An answer with a rejected ancestor must stop before preparing a scan")
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion =
            throw AssertionError("An answer with a rejected ancestor must not dispatch a scan")
        override fun prepareReview(request: ModelRequest): PreparedModelRequest =
            throw AssertionError("An answer with a rejected ancestor must stop before preparing a review")
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion =
            throw AssertionError("An answer with a rejected ancestor must not dispatch a review")
    }
}
