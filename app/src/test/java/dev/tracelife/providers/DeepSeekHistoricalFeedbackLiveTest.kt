package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
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
import java.time.LocalDate
import java.util.UUID
import javax.crypto.KeyGenerator

/** Explicit opt-in, one paid request, synthetic input. No production diagnostic logging. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekHistoricalFeedbackLiveTest {
    @Test fun `real reflection with a historical correction returns a complete bounded proposal`() {
        assumeTrue("Live reflection requires explicit opt-in", System.getProperty("traceLife.deepSeekReflection") == "true")
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514)
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val credentialDirectory = File(context.noBackupFilesDir, "synthetic-history-key-${UUID.randomUUID()}")
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(credentialDirectory, context.packageName) { wrappingKey }
        val database = "synthetic-history-live-${UUID.randomUUID()}.db"
        val output = File(System.getProperty("user.home"), "Library/Caches/trace-life-tools/experience-memory-20261020/history-live-${UUID.randomUUID()}").canonicalFile
        check(!output.toPath().startsWith(root.toPath()))
        check(output.mkdirs())
        var requests = 0
        val actual = HttpsDeepSeekTransport()
        val diagnostic = DeepSeekTransport { body, secret ->
            check(++requests == 1) { "Synthetic probe allows exactly one paid request" }
            File(output, "request.json").writeText(body)
            actual.execute(body, secret).also { response ->
                if (response.status == 200) {
                    File(output, "response.json").writeBytes(response.body)
                    val wire = JSONObject(response.body.toString(Charsets.UTF_8))
                    val choice = wire.getJSONArray("choices").getJSONObject(0)
                    val text = choice.getJSONObject("message").optString("content")
                    val content = runCatching { JSONObject(text) }.getOrNull()
                    println("synthetic-history-response model=${wire.optString("model")} object=${wire.optString("object")} finish=${choice.optString("finish_reason")} completionTokens=${wire.optJSONObject("usage")?.optInt("completion_tokens")} jsonComplete=${content != null} contentChars=${text.length}")
                    println("synthetic-history-probe output=${output.path}")
                }
            }
        }
        try {
            credentials.configure(key); key.fill('\u0000')
            LifeRepository(context, databaseName = database).use { repository ->
                val earlier = LocalDate.parse("2026-10-13")
                val today = LocalDate.parse("2026-10-20")
                repository.createRecord(RecordDraft("合成资料：复杂任务直接写反复返工，先画三个模块接口才跑通。但仅尝试过一次。", Occurrence(earlier)), "synthetic-old")
                val analysis = ControlledAnalysis(repository)
                analysis.start(AnalysisRequest("合成旧分析", AnalysisQuery(earlier, earlier, "Asia/Shanghai")), "synthetic-old-run")
                analysis.synthesize("synthetic-old-run", "synthetic-old-batch", listOf(EvidenceSelection("synthetic-old", "body")), object : AnalysisProvider {
                    override val id = "synthetic-old-proposal"
                    override val contextWindowTokens = 100_000
                    override fun countInputTokens(request: ModelRequest) = 100
                    override fun complete(request: ModelRequest): BatchProposal {
                        val receipt = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                        return BatchProposal(listOf(ProposedClaim("值得留下：10-13 的复杂任务直接写会反复返工，你先画出三个模块的接口、再写最小步骤才跑通，由此把做法按情境分开：简单练习可以先做，涉及多个模块时先厘清结构。这是对 10-06「先动手」经验的反例与边界补充，不是对先动手的全盘否定。",
                            listOf(receipt), ClaimKind.POSSIBLE_INTERPRETATION)))
                    }
                })
                AnalysisFeedback(repository).respond("synthetic-old-run", analysis.claimRef("synthetic-old-run", "synthetic-old-batch", 0),
                    analysis.task("synthetic-old-run").stored.version, ClaimFeedbackDecision.CORRECTED,
                    "合成纠正：复杂任务先画接口目前也只实践过一次。我还没有形成稳定做法；下次也可能先跑通一个小模块，不能把任务复杂度直接写成固定规则。")
                repository.createRecord(RecordDraft("合成资料｜2026-10-20 学习复盘：今天做一个有三个模块的小练习。我没有先画完整接口，而是先跑通其中一个小模块，再根据实际错误补接口说明，最终完成了练习。这次有四十分钟，问题也熟悉，不能据此断定复杂任务一定需要先设计，或先动手总是更有效。下次继续记录任务熟悉度、时间和返工情况。",
                    Occurrence(today), setOf("学习", "工作")), "synthetic-new-learning")
                repository.createRecord(RecordDraft("合成资料｜2026-10-20 运动随记：今天午后在小区散步二十分钟，看到桂花开了。这条记录与10月5日至13日的学习练习没有关联。这里只记录个人感受，不据此判断健康变化。",
                    Occurrence(today), setOf("运动")), "synthetic-new-walk")
                val workflow = ReflectionWorkflow(repository, analysis)
                val task = workflow.start(AnalysisQuery(today, today, "Asia/Shanghai"))
                val result = workflow.process(task.id, DeepSeekProvider(credentials, DeepSeekModel.FLASH, diagnostic), maxRequests = 1)
                val call = analysis.task(task.id).state.calls.single()
                assertEquals("Synthetic historical reflection failed: ${call.failureCode}", ReflectionStatus.READY, result.status)
                assertEquals(CallStatus.SUCCEEDED, call.status)
                assertEquals(1, call.historicalFeedbackReadIds.size)
                assertTrue(result.run.findings.size in 1..6)
                assertTrue(result.run.findings.all { it.historicalFeedback.total == 1 && it.historicalFeedback.resolved == 0 })
                assertTrue(repository.knowledgeHeads().isEmpty())
                println("synthetic-history-reflection findings=${result.run.findings.size} historyUsed=${call.historicalFeedbackReadIds.size} completionTokens=${call.usage?.completionTokens}")
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { credentialDirectory.deleteRecursively(); context.deleteDatabase(database) }
        }
    }
}
