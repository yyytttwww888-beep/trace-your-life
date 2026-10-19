package dev.tracelife.providers

import android.content.Context
import dev.tracelife.actions.*
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.nio.file.Files
import java.time.*
import java.util.UUID
import javax.crypto.KeyGenerator

/** Opt-in, synthetic only: one generation and one independent review, no retries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekActionPeriodLiveTest {
    @Test fun `real action comparison preserves original plan and separately reports future adjustment`() {
        assumeTrue("Live action reflection is opt-in", System.getProperty("traceLife.deepSeekActionPeriod") == "true")
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514)
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-action-key-${UUID.randomUUID()}")
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { wrappingKey }
        val database = "synthetic-live-action-period-${UUID.randomUUID()}.db"
        val clock = MutableClock()
        try {
            credentials.configure(key); key.fill('\u0000')
            LifeRepository(context, clock, database).use { repository ->
                val from = LocalDate.parse("2026-10-12")
                val original = ActionDraft("合成测试：代码练习", "合成：下班后有空时", "合成：每次完成一个二十分钟代码练习", "合成：记录是否执行、难度及可用时间",
                    from.plusDays(6), "Asia/Shanghai")
                val actions = ActionExperiments(repository, clock)
                actions.create(original, "synthetic-action")
                clock.now = Instant.parse("2026-10-19T04:00:00Z")
                actions.review("synthetic-action", 1, "synthetic-review", ActionReview(ActionExecution.NOT_EXECUTED,
                    "合成测试资料，不是真实经历：本周没有执行原定二十分钟练习。当时加班，未留下足够空闲时间。无法判断这个方法本身是否有效。",
                    ActionDecision.ADJUST, from.plusDays(6)), adjustedDraft = original.copy(behavior = "合成：下周只尝试五分钟的最小步骤", reviewOn = from.plusDays(13)))
                repository.createRecord(RecordDraft("合成测试资料，不是真实经历：本周加班且任务难度较高，没有开始计划中的代码练习。" +
                    "这不是方法无效的证据；下周想缩小练习，只是计划，尚未实施，效果未知。", Occurrence(from.plusDays(5))), "synthetic-context")
                val analysis = ControlledAnalysis(repository, clock)
                val period = ActionPeriodReflection(analysis)
                val task = period.start(AnalysisRequest("只提出一条有边界的行动复盘解释：区分本周原计划未执行与下周缩小练习的计划；" +
                    "说明尚不能判断方法效果，保留时间和难度条件。该条须同时引用至少一个 actionEvidence 和一个 evidence 的 ID。不要判断成长、因果或人格。",
                    AnalysisQuery(from, from.plusDays(6), "Asia/Shanghai"), limits = AnalysisLimits(inputTokenLimit = 30_000)))
                val real = DeepSeekProvider(credentials)
                var requests = 0
                val generation = object : AnalysisProvider by real {
                    override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                        check(++requests <= 2)
                        val envelope = JSONObject(request.original.payloadJson)
                        assertEquals(2, envelope.getJSONArray("actionEvidence").length())
                        val review = envelope.getJSONArray("actionEvidence").getJSONObject(1)
                        assertEquals(original.behavior, review.getJSONObject("reviewedPlan").getString("behavior"))
                        assertEquals("合成：下周只尝试五分钟的最小步骤", review.getJSONObject("nextPlan").getString("behavior"))
                        return real.completePrepared(request)
                    }
                }
                val result = period.process(task.stored.id, generation, maxBatches = 1)
                assertEquals(SynthesisProcessingStatus.INPUTS_PROCESSED, result.status)
                assertEquals(ActionPeriodIntegration.SINGLE_CALL_ALL_AVAILABLE_INPUTS, result.progress.integration)
                val generated = analysis.task(task.stored.id).state.calls.single()
                val claims = requireNotNull(generated.proposal).claims
                assertEquals(1, claims.size)
                assertTrue(claims.single().evidenceIds.any { id -> generated.actionEvidence.any { it.id == id } })
                assertTrue(claims.single().evidenceIds.any { id -> generated.evidence.any { it.id == id } })
                val target = analysis.claimRef(task.stored.id, generated.batchKey, 0)
                AnalysisFeedback(repository, clock).respond(task.stored.id, target, analysis.task(task.stored.id).stored.version,
                    ClaimFeedbackDecision.CONFIRMED, "合成测试明确确认，非真实个人决定")
                val reviewer = object : SemanticReviewProvider by real {
                    override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                        check(++requests <= 2)
                        val envelope = JSONObject(request.original.payloadJson)
                        assertEquals(2, envelope.getJSONArray("actionEvidence").length())
                        assertEquals(1, envelope.getJSONArray("evidence").length())
                        return real.reviewPrepared(request)
                    }
                }
                val review = analysis.reviewClaim(task.stored.id, "synthetic-live-action-review", target, provider = reviewer)
                assertEquals(ReviewVerdict.SUPPORTED, review.verdict)
                val saved = KnowledgeLibrary(repository, clock).publish(task.stored.id, target, "synthetic-live-action-review",
                    KnowledgeCategory.INSIGHT, analysis.task(task.stored.id).stored.version)
                assertEquals(2, saved.actionEvidence.size)
                assertEquals(2, actions.get("synthetic-action")!!.value.revision)
                assertEquals(2, requests)
                analysis.task(task.stored.id).state.calls.forEach { call ->
                    val usage = requireNotNull(call.usage)
                    println("synthetic-action-period purpose=${call.purpose} promptTokens=${usage.promptTokens} completionTokens=${usage.completionTokens}")
                }
                println("synthetic-action-period requests=$requests review=${review.verdict} integration=${result.progress.integration}")
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { directory.deleteRecursively(); context.deleteDatabase(database) }
        }
    }
    private class MutableClock : Clock() {
        var now = Instant.parse("2026-10-12T04:00:00Z")
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
