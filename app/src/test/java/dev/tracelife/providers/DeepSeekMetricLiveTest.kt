package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.LifeRepository
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

/** Opt-in, synthetic-only, at most two paid calls: numeric explanation and recomputed review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekMetricLiveTest {
    @Test fun `real metric explanation and independently recomputed review publish confirmed synthetic insight`() {
        assumeTrue("Live metric analysis is explicitly opt-in", System.getProperty("traceLife.deepSeekMetric") == "true")
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514) {
            "Configure the external DeepSeek API key file first"
        }
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-metric-key-${UUID.randomUUID()}")
        val master = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { master }
        val database = "synthetic-live-metric-${UUID.randomUUID()}.db"
        try {
            credentials.configure(key); key.fill('\u0000')
            LifeRepository(context, databaseName = database).use { repository ->
                val day = LocalDate.parse("2026-10-12")
                listOf(day, day, day.plusDays(2), null).forEachIndexed { index, date ->
                    repository.createRecord(RecordDraft("合成资料：这段原文不会提供给指标模型。", Occurrence(date)), "synthetic-$index")
                }
                val analysis = ControlledAnalysis(repository)
                analysis.start(AnalysisRequest("这是合成测试，只输出一条简短记录覆盖说明：准确写出范围内记录总数、" +
                    "精确日期记录数、有精确日期记录的天数、未知时间记录数。保留覆盖和缺失限制，不计算额外比例、" +
                    "不作因果、成长、人格或健康判断。", AnalysisQuery(day, day.plusDays(6), "Asia/Shanghai"),
                    AnalysisLimits(inputTokenLimit = 30_000)), "run")
                val real = DeepSeekProvider(credentials)
                var requests = 0
                val producer = object : AnalysisProvider by real {
                    override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                        check(++requests <= 2) { "Live metric request cap exceeded" }
                        return real.completePrepared(request)
                    }
                }
                val reviewer = object : SemanticReviewProvider by real {
                    override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                        check(++requests <= 2) { "Live metric request cap exceeded" }
                        return real.reviewPrepared(request)
                    }
                }
                val proposal = analysis.analyzeMetrics("run", "synthetic-metric", producer)
                assertEquals(1, proposal.claims.size)
                val claim = analysis.claimRef("run", "synthetic-metric", 0)
                AnalysisFeedback(repository).respond("run", claim, analysis.task("run").stored.version,
                    ClaimFeedbackDecision.CONFIRMED, "测试程序模拟用户对合成指标说明的确认。")
                val result = analysis.reviewClaim("run", "synthetic-metric-review", claim, provider = reviewer)
                assertEquals(ReviewVerdict.SUPPORTED, result.verdict)
                val library = KnowledgeLibrary(repository)
                val published = library.publish("run", claim, "synthetic-metric-review", KnowledgeCategory.INSIGHT,
                    analysis.task("run").stored.version, topics = setOf("合成记录覆盖"))
                assertEquals(KnowledgeEvidenceState.CURRENT, library.get(published.id)!!.evidenceState)
                assertTrue(published.evidence.isEmpty())
                assertEquals(4, published.metricEvidence.single().values.totalRecords)
                assertEquals(2, requests)
                val calls = analysis.task("run").state.calls
                assertEquals(calls[0].metricEvidence, calls[1].metricEvidence)
                calls.forEach { call ->
                    val usage = requireNotNull(call.usage)
                    println("synthetic-metric purpose=${call.purpose} promptTokens=${usage.promptTokens} completionTokens=${usage.completionTokens}")
                }
                println("synthetic-metric requests=$requests sources=${published.metricEvidence.single().sources.size} publications=${repository.knowledgeHeads().size} review=${result.verdict}")
                println("synthetic-metric claim=${proposal.claims.single().text}")
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { directory.deleteRecursively(); context.deleteDatabase(database) }
        }
    }
}
