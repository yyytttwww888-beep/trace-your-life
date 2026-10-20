package dev.tracelife.quality

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.conversation.ConversationWorkflow
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.providers.DeepSeekCredentials
import dev.tracelife.providers.DeepSeekProvider
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
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
import java.time.LocalDate
import java.util.UUID

/** Real SQLite, production request framing, scripted responses. No network or semantic score. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LongTermConversationScaleTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var reflection: ReflectionWorkflow
    private lateinit var conversation: ConversationWorkflow
    private lateinit var provider: MeasuredSyntheticProvider
    private val database = "synthetic-year-scale-${UUID.randomUUID()}.db"
    private val lastDay = LocalDate.parse("2026-10-20")
    private val scope = AnalysisQuery(lastDay.minusDays(364), lastDay, "Asia/Shanghai")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        open()
        provider = MeasuredSyntheticProvider(DeepSeekProvider(DeepSeekCredentials(context)))
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `one year routes every day and resumes a bounded conversation through complete integration`() {
        val started = System.nanoTime()
        val expectedIds = seedYear()
        val seedMillis = (System.nanoTime() - started) / 1_000_000
        val turn = conversation.submit(conversation.prepare("这一年我的学习方法发生了哪些变化？", scopeOverride = scope),
            AnalysisLimits(pageSize = 17, inputTokenLimit = 32_000))
        assertEquals(365, analysis.route(turn.runId).records)
        val pagedIds = mutableListOf<String>()
        var next: Int? = 0
        var pages = 0
        while (next != null) {
            val page = analysis.evidencePage(turn.runId, next).page
            assertTrue(page.items.size in 1..17)
            assertEquals(365, page.total)
            pagedIds += page.items.map { it.id }
            pages++; next = page.nextOffset
        }
        assertEquals(expectedIds, pagedIds.toSet())
        assertEquals(365, pagedIds.size)
        assertEquals(22, pages)
        assertTrue(provider.requests.isEmpty())

        val passes = mutableListOf<Int>()
        var result = reflection.process(turn.runId, provider, maxRequests = 5)
        passes += result.modelRequestsThisPass
        assertEquals(5, result.modelRequestsThisPass)
        assertEquals(ReflectionStatus.ANALYZING, result.status)
        assertFalse(result.run.canReviewFindings)
        val persistedVersion = result.run.taskVersion
        val persistedCoverage = result.run.coverage
        repository.close(); open()
        assertEquals(turn, conversation.turns(turn.threadId).single())
        assertEquals(persistedVersion, reflection.inspect(turn.runId).taskVersion)
        assertEquals(persistedCoverage, reflection.inspect(turn.runId).coverage)
        assertEquals(5, provider.requests.size) // Reading restored progress must not dispatch.
        var continuations = 0
        while (result.status in setOf(ReflectionStatus.ANALYZING, ReflectionStatus.INTEGRATING) && continuations++ < 20) {
            result = reflection.process(turn.runId, provider, maxRequests = 5)
            passes += result.modelRequestsThisPass
            assertTrue(result.modelRequestsThisPass in 1..5)
        }
        assertEquals("The fixed short-entry year should complete under the standard task ceiling", ReflectionStatus.READY, result.status)
        val coverage = requireNotNull(result.run.coverage)
        assertEquals(365, coverage.records)
        assertEquals(coverage.textCodePoints, coverage.completedTextCodePoints)
        assertEquals(0L, coverage.remainingTextFragments)
        val calls = analysis.task(turn.runId).state.calls
        val originals = calls.filter { it.purpose == CallPurpose.SYNTHESIS }
        assertEquals(expectedIds, originals.flatMap { it.evidence }.map { it.recordId }.toSet())
        assertEquals(365, originals.sumOf { it.evidence.size })
        assertTrue(originals.all { it.evidence.size in 1..16 })
        assertTrue(calls.any { it.purpose == CallPurpose.REDUCTION })
        assertTrue(calls.size < 100)
        assertEquals(calls.size, passes.sum())
        assertMeasuredBudget(turn.runId)
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertTrue(result.run.findings.all { reflection.evidence(turn.runId, it.target, limit = 100).total == 365 })
        assertEquals(0, reflection.process(turn.runId, provider, maxRequests = 5).modelRequestsThisPass)
        report("365-day-conversation", turn.runId, started, seedMillis, pages, passes)
    }

    @Test fun `long unicode record reads every bounded fragment including the final counterexample`() {
        val started = System.nanoTime()
        val tail = "末尾反例唯一标记：在协作接口未知时仍然失败，不能概括为总是有效。"
        val body = buildString {
            append("合成验收资料，不是真实个人经历：开头的一次简单练习成功。\n")
            repeat(340) { append("合成过程第${it + 1}段🙂，只是同一条长文中的过程，不构成额外独立实验。\n") }
            append(tail)
        }
        val points = body.codePointCount(0, body.length)
        assertTrue(points > 10_000)
        assertTrue(points < body.length) // Non-BMP characters exercise code-point offsets.
        repository.createRecord(RecordDraft(body, Occurrence(lastDay), setOf("学习")), "synthetic-long-unicode")
        val turn = conversation.submit(conversation.prepare("今天学习方法有没有反例？", scopeOverride = scope.copy(from = lastDay)),
            AnalysisLimits(fragmentCodePoints = 1_024, inputTokenLimit = 32_000))
        val passes = mutableListOf<Int>()
        var result: ReflectionPass
        var continuations = 0
        do {
            result = reflection.process(turn.runId, provider, maxRequests = 5)
            passes += result.modelRequestsThisPass
        } while (result.status in setOf(ReflectionStatus.ANALYZING, ReflectionStatus.INTEGRATING) && continuations++ < 20)
        assertEquals(ReflectionStatus.READY, result.status)
        val spans = analysis.task(turn.runId).state.calls.filter { it.purpose == CallPurpose.SYNTHESIS }
            .flatMap { it.evidence }.sortedBy { it.start }
        assertEquals(0, spans.first().start)
        assertEquals(points, spans.last().end)
        assertEquals(points, spans.sumOf { it.end - it.start })
        assertTrue(spans.all { it.end - it.start <= 1_024 })
        assertTrue(spans.zipWithNext().all { (a, b) -> a.end == b.start })
        assertEquals(points.toLong(), result.run.coverage!!.completedTextCodePoints)
        assertEquals(1, provider.requests.count { it.payloadJson.contains(tail) })
        assertTrue(provider.requests.filter { JSONObject(it.payloadJson).getString("purpose") == "REDUCTION" }
            .none { it.payloadJson.contains("合成过程第") })
        assertMeasuredBudget(turn.runId)
        assertTrue(repository.knowledgeHeads().isEmpty())
        report("long-unicode-tail", turn.runId, started, 0, 1, passes)
    }

    @Test fun `annual partial budget stops without fake completion and repeated continuation sends nothing`() {
        val started = System.nanoTime()
        seedYear()
        val turn = conversation.submit(conversation.prepare("这一年学习方法有什么变化？", scopeOverride = scope),
            AnalysisLimits(inputTokenLimit = 32_000, maxCalls = 2))
        val first = reflection.process(turn.runId, provider, maxRequests = 5)
        assertEquals(ReflectionStatus.BUDGET_EXHAUSTED, first.status)
        assertEquals(2, first.modelRequestsThisPass)
        assertTrue(first.run.coverage!!.remainingTextFragments > 0)
        assertFalse(first.run.canReviewFindings)
        repository.close(); open()
        repeat(3) {
            val resumed = reflection.process(turn.runId, provider, maxRequests = 5)
            assertEquals(ReflectionStatus.BUDGET_EXHAUSTED, resumed.status)
            assertEquals(0, resumed.modelRequestsThisPass)
        }
        assertEquals(2, provider.requests.size)
        assertEquals(2, analysis.task(turn.runId).state.calls.size)
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertMeasuredBudget(turn.runId)
        report("annual-budget-stop", turn.runId, started, 0, 0, listOf(2, 0, 0, 0))
    }

    private fun open() {
        repository = LifeRepository(context, clock = SyntheticQualityCases.clock, databaseName = database)
        analysis = ControlledAnalysis(repository, SyntheticQualityCases.clock)
        reflection = ReflectionWorkflow(repository, analysis)
        conversation = ConversationWorkflow(repository, analysis, reflection, SyntheticQualityCases.clock)
    }

    private fun seedYear(): Set<String> = (0 until 365).map { index ->
        val id = "synthetic-year-${index.toString().padStart(3, '0')}"
        repository.createRecord(RecordDraft("合成验收资料，不是真实个人经历：第${index + 1}天做了一次学习小练习，未测试是否掌握。",
            Occurrence(scope.from.plusDays(index.toLong()), zoneId = "Asia/Shanghai"),
            if (index % 7 == 0) emptySet() else setOf("学习")), id)
        id
    }.toSet()

    private fun assertMeasuredBudget(runId: String) {
        val task = analysis.task(runId)
        val limits = task.state.request.limits
        assertTrue(task.state.calls.all { it.inputTokens <= limits.inputTokenLimit && it.inputBytes <= limits.maxInputBytes })
        assertTrue(task.state.calls.all { it.tokenCountKind == TokenCountKind.CONSERVATIVE_UTF8_BOUND })
        assertTrue(task.state.calls.sumOf { it.inputBytes.toLong() } <= limits.totalInputBytes)
        assertEquals(provider.preparedBytes, task.state.calls.map { it.inputBytes })
    }

    private fun report(id: String, runId: String, started: Long, seedMillis: Long, pages: Int, passes: List<Int>) {
        val task = analysis.task(runId)
        val run = reflection.inspect(runId)
        SyntheticQualityReport("offline-scale").add(JSONObject().put("id", id).put("programmaticStatus", "PASS")
            .put("provider", "scripted-responses-with-production-DeepSeek-framing").put("networkRequests", 0)
            .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000).put("seedMillis", seedMillis)
            .put("metadataPages", pages).put("requestsPerPass", JSONArray(passes)).put("status", run.status.name)
            .put("originalRequests", task.state.calls.count { it.purpose == CallPurpose.SYNTHESIS })
            .put("reductionRequests", task.state.calls.count { it.purpose == CallPurpose.REDUCTION })
            .put("maxRequestBytes", task.state.calls.maxOfOrNull { it.inputBytes } ?: 0)
            .put("maxBudgetTokens", task.state.calls.maxOfOrNull { it.inputTokens } ?: 0)
            .put("totalInputBytes", task.state.calls.sumOf { it.inputBytes.toLong() })
            .put("records", run.coverage?.records).put("completedCodePoints", run.coverage?.completedTextCodePoints)
            .put("remainingFragments", run.coverage?.remainingTextFragments)
            .put("semanticStatus", "NOT_MEASURED_SCRIPTED_PROVIDER").put("published", false))
    }

    private class MeasuredSyntheticProvider(private val framing: DeepSeekProvider) : AnalysisProvider {
        override val id = framing.id
        override val contextWindowTokens = framing.contextWindowTokens
        val requests = mutableListOf<ModelRequest>()
        val preparedBytes = mutableListOf<Int>()
        override fun countInputTokens(request: ModelRequest) = framing.countInputTokens(request)
        override fun prepare(request: ModelRequest) = framing.prepare(request)
        override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
            preparedBytes += request.payloadJson.toByteArray(Charsets.UTF_8).size
            return ModelCompletion(complete(request.original))
        }
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            val json = JSONObject(request.payloadJson)
            val rows = json.getJSONArray(if (json.getString("purpose") == "REDUCTION") "derivedInputs" else "evidence")
            val ids = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
            return BatchProposal(listOf(ProposedClaim("合成规模测试描述，仅用于验证分页与来源链，不代表真实分析质量。", ids,
                ClaimKind.POSSIBLE_INTERPRETATION)), listOf("脚本化输出不判断生活情况或语义正确性。"))
        }
    }
}
