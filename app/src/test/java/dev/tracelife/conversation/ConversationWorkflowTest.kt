package dev.tracelife.conversation

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import dev.tracelife.providers.DeepSeekCredentials
import dev.tracelife.providers.DeepSeekProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationWorkflowTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var reflection: ReflectionWorkflow
    private lateinit var workflow: ConversationWorkflow
    private val database = "synthetic-conversation-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val provider = SyntheticProvider()

    @Before fun setup() { context = RuntimeEnvironment.getApplication(); open() }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `custom question runs complete time range including unclassified records and survives restart`() {
        seed()
        val plan = workflow.prepare("这三个月，我在学习上的方法发生了哪些变化，哪些问题反复出现？")
        assertNull(plan.clarification)
        val turn = workflow.submit(plan)
        assertEquals(turn, workflow.submit(plan))
        assertEquals(1, workflow.turns(turn.threadId).size)
        assertTrue(provider.requests.isEmpty())
        val result = reflection.process(turn.runId, provider)
        assertEquals(ReflectionStatus.READY, result.status)
        val request = JSONObject(provider.requests.single().payloadJson)
        assertTrue(request.getString("question").startsWith(plan.question + "\n"))
        assertEquals(2, request.getJSONArray("evidence").length())
        assertFalse(request.has("conversationContext"))
        assertTrue(repository.knowledgeHeads().isEmpty())
        repository.close(); open()
        assertEquals(turn, workflow.turns(turn.threadId).single())
        assertEquals(result.run.findings, workflow.inspect(turn)!!.findings)
    }

    @Test fun `ordinal followup uses visible numbering and passes only selected derived context`() {
        seed()
        val first = workflow.submit(workflow.prepare("这三个月学习方法变化和反复出现的问题"))
        reflection.process(first.runId, provider)
        val plan = workflow.prepare("第二个问题具体在哪几次出现", first.threadId)
        assertEquals(1, plan.parentFindingIndex) // Page number 2, not the second item in an invisible category.
        assertEquals(first.id, plan.parentTurnId)
        assertEquals(first.scope, plan.scope)
        assertEquals(1, plan.context!!.targets.size)
        val second = workflow.submit(plan)
        reflection.process(second.runId, provider)
        val wire = JSONObject(provider.requests.last().payloadJson)
        assertEquals(1, wire.getJSONObject("conversationContext").getJSONArray("selectedAnswers").length())
        assertFalse(wire.getJSONObject("conversationContext").getBoolean("originalEvidence"))
        assertEquals(2, wire.getJSONArray("evidence").length())
        assertEquals(2, workflow.turns(first.threadId).size)
        assertEquals(first.question, workflow.turns(first.threadId).first().question)
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertEquals(first, workflow.rephraseSource(second))
        assertNotNull(workflow.prepare("不是这样", first.threadId).clarification)
    }

    @Test fun `feedback change invalidates followup and source deletion retains independent questions`() {
        seed()
        val first = workflow.submit(workflow.prepare("这三个月学习方法变化"))
        val firstRun = reflection.process(first.runId, provider).run
        val second = workflow.submit(workflow.prepare("详细说说", first.threadId, first.id, 0))
        reflection.process(second.runId, provider)
        reflection.respond(first.runId, firstRun.findings.first().target, firstRun.taskVersion, ClaimFeedbackDecision.REJECTED, userText = "合成独立纠正")
        assertEquals(ReflectionStatus.STALE, workflow.inspect(second)!!.status)
        assertNotNull(workflow.prepare("后来有没有改善", first.threadId).clarification)
        repository.deleteRecord("synthetic-learning-a", 1)
        assertNull(workflow.inspect(first))
        assertNull(workflow.inspect(second))
        assertEquals(listOf(first.question, second.question), workflow.turns(first.threadId).map { it.question })
    }

    @Test fun `unknown references blocked and independent questions do not carry previous answer`() {
        seed()
        assertNotNull(workflow.prepare("第二个问题具体在哪几次出现").clarification)
        val first = workflow.submit(workflow.prepare("这三个月学习变化"))
        assertNotNull(workflow.prepare("后来有没有改善", first.threadId).clarification)
        reflection.process(first.runId, provider)
        assertNotNull(workflow.prepare("第九个问题具体在哪几次出现", first.threadId).clarification)
        val independent = workflow.prepare("今天工作中有什么可以注意的", first.threadId)
        assertNull(independent.context)
        assertNull(independent.parentTurnId)
        assertEquals(LocalDate.parse("2026-10-20"), independent.scope.from)
    }

    @Test fun `concurrent prepared turns do not silently branch and deleting a thread removes its machine tasks`() {
        seed()
        val first = workflow.submit(workflow.prepare("这三个月学习变化"))
        reflection.process(first.runId, provider)
        val a = workflow.prepare("今天工作变化", first.threadId)
        val b = workflow.prepare("今天运动情况", first.threadId)
        val second = workflow.submit(a)
        assertThrows(IllegalArgumentException::class.java) { workflow.submit(b) }
        assertEquals(2, workflow.turns(first.threadId).size)
        workflow.delete(first.threadId)
        assertTrue(workflow.history().isEmpty())
        assertNull(repository.analysisRun(first.runId)); assertNull(repository.analysisRun(second.runId))
        assertNotNull(repository.record("synthetic-learning-a"))
    }

    @Test fun `multiple batches integrate under the measured production wire budget including followup context`() {
        seed()
        repeat(15) { repository.createRecord(RecordDraft("合成测试：追加学习经历$it。", Occurrence(LocalDate.parse("2026-10-15"))), "synthetic-extra-$it") }
        val framing = DeepSeekProvider(DeepSeekCredentials(context))
        val longProvider = SyntheticProvider(longAnswers = true)
        val measured = object : AnalysisProvider by longProvider {
            override val id = framing.id
            override fun prepare(request: ModelRequest) = framing.prepare(request)
            override fun completePrepared(request: PreparedModelRequest) = ModelCompletion(longProvider.complete(request.original))
        }
        val first = workflow.submit(workflow.prepare("这三个月学习方法有什么变化"))
        val result = reflection.process(first.runId, measured, maxRequests = 5)
        assertEquals(ReflectionStatus.READY, result.status)
        assertEquals(3, result.modelRequestsThisPass)
        assertEquals(2, result.run.synthesizedBatches)
        assertTrue(analysis.task(first.runId).state.calls.any { it.inputTokens > 12_000 })
        assertTrue(analysis.task(first.runId).state.calls.all { it.inputTokens <= 32_000 && it.inputBytes <= 65_536 })
        val second = workflow.submit(workflow.prepare("后来有没有改善", first.threadId, first.id, 0))
        assertEquals(ReflectionStatus.READY, reflection.process(second.runId, measured, maxRequests = 5).status)
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    private fun open() {
        repository = LifeRepository(context, databaseName = database, clock = clock)
        analysis = ControlledAnalysis(repository, clock)
        reflection = ReflectionWorkflow(repository, analysis)
        workflow = ConversationWorkflow(repository, analysis, reflection, clock)
    }
    private fun seed() {
        repository.createRecord(RecordDraft("合成测试：八月主要看课没有练习，遇到题目仍需回看。", Occurrence(LocalDate.parse("2026-08-01")), topics = setOf("学习")), "synthetic-learning-a")
        repository.createRecord(RecordDraft("合成测试：十月改为先做练习，但分心和没有复习仍出现。", Occurrence(LocalDate.parse("2026-10-10"))), "synthetic-learning-b")
    }
    private class SyntheticProvider(private val longAnswers: Boolean = false) : AnalysisProvider {
        override val id = "synthetic-conversation"
        override val contextWindowTokens = 128_000
        val requests = mutableListOf<ModelRequest>()
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            val json = JSONObject(request.payloadJson)
            val evidence = json.getJSONArray(if (json.getString("purpose") == "REDUCTION") "derivedInputs" else "evidence")
            val ids = (0 until evidence.length()).map { evidence.getJSONObject(it).getString("id") }
            val texts = if (longAnswers) List(6) { "变化：合成发现$it。" + "记录中的条件需要与后来练习难度及可用时间对照。".repeat(8) }
                else listOf("变化：合成学习先看课再练习，后来先练习。", "反复出现：合成资料提到分心。", "反复出现：合成资料提到没有复习。")
            return BatchProposal(texts
                .map { ProposedClaim(it, ids, ClaimKind.POSSIBLE_INTERPRETATION) })
        }
    }
}
