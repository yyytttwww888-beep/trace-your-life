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

/** Explicit opt-in; at most three paid requests. Synthetic semantic benchmark, never personal data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekHistoricalFeedbackAssistLiveTest {
    @Test fun `real bounded comparisons distinguish constraints paraphrased conflict unrelated context and missing history`() {
        assumeTrue("Historical comparison quality probe requires its own opt-in",
            System.getProperty("traceLife.deepSeekHistoricalAssist") == "true")
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514)
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-history-assist-key-${UUID.randomUUID()}")
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { wrappingKey }
        val database = "synthetic-history-assist-live-${UUID.randomUUID()}.db"
        val transport = HttpsDeepSeekTransport()
        var requests = 0
        val boundedTransport = DeepSeekTransport { body, secret ->
            check(++requests <= 3) { "Synthetic comparison benchmark permits at most three paid requests" }
            transport.execute(body, secret)
        }
        try {
            credentials.configure(key); key.fill('\u0000')
            LifeRepository(context, databaseName = database).use { repository ->
                val analysis = ControlledAnalysis(repository)
                val feedback = AnalysisFeedback(repository)
                val previous = LocalDate.parse("2026-10-06")
                val current = LocalDate.parse("2026-10-20")
                fun oldFeedback(id: String, statement: String, correction: String): String {
                    repository.createRecord(RecordDraft("合成资料：本次只尝试一次，任务较简单，时间也更充足。", Occurrence(previous)), "$id-source")
                    analysis.start(AnalysisRequest("合成旧判断", AnalysisQuery(previous, previous, "Asia/Shanghai", recordIds = setOf("$id-source"))), id)
                    analysis.synthesize(id, "old-batch", listOf(EvidenceSelection("$id-source", "body")), SyntheticStatements(listOf(statement)))
                    val target = analysis.claimRef(id, "old-batch", 0)
                    feedback.respond(id, target, analysis.task(id).stored.version, ClaimFeedbackDecision.CORRECTED, correction)
                    return AnalysisRunCodec.feedbackId(id, target)
                }
                val known = oldFeedback("synthetic-known-history", "合成旧判断：只要任务开始前列清单，你的执行效果总会更好。",
                    "合成纠正：任务简单且时间充足，只尝试过一次。不能据此认定列清单有效，更不能说这是稳定规律。")
                val orphan = oldFeedback("synthetic-missing-history", "合成待删除的旧描述。", "合成独立意见：我不认同当时的那个结论。")
                repository.deleteRecord("synthetic-missing-history-source", 1)
                assertNull(repository.analysisRun("synthetic-missing-history"))
                repository.createRecord(RecordDraft("合成资料：这周做了一次练习，也在午后散步时看到桂花开放。", Occurrence(current)), "synthetic-current")
                val statements = listOf(
                    "合成新描述：这次先列清单后完成了一次较简单的任务，当时也有充足时间；目前无法认定清单提升了效果。",
                    "合成新描述：把步骤提前写下来能稳定提升我的执行表现。",
                    "合成新描述：这周午后散步时看到桂花开放；这句话只描述散步所见，没有评估任务或执行方法。",
                )
                val runId = "synthetic-comparison-quality"
                analysis.start(AnalysisRequest("合成比较质量边界", AnalysisQuery(current, current, "Asia/Shanghai"),
                    inheritHistoricalFeedback = true), runId)
                analysis.synthesize(runId, "synthetic-current-batch", listOf(EvidenceSelection("synthetic-current", "body")), SyntheticStatements(statements))
                val provider = DeepSeekProvider(credentials, DeepSeekModel.FLASH, boundedTransport)
                val expected = listOf(HistoricalFeedbackAssessment.ALIGNED, HistoricalFeedbackAssessment.CONFLICT, HistoricalFeedbackAssessment.UNRELATED)
                val observed = mutableListOf<Pair<HistoricalFeedbackAssessment, HistoricalFeedbackAssessment>>()
                repeat(3) { index ->
                    val target = analysis.claimRef(runId, "synthetic-current-batch", index)
                    analysis.scanHistoricalFeedbackNext(runId, target, provider)
                    val page = analysis.historicalFeedbackAssistPage(runId, target)
                    assertEquals(2, page.scanned)
                    assertEquals(2, page.unresolved)
                    val results = page.items.associate { it.content.reference.id to requireNotNull(it.suggestion).assessment }
                    observed += results.getValue(known) to results.getValue(orphan)
                    val call = analysis.task(runId).state.calls.last()
                    println("synthetic-history-quality case=$index expected=${expected[index]} observed=${results.getValue(known)} missingHistory=${results.getValue(orphan)} compared=${page.scanned}/${page.total} inputTokens=${call.usage?.promptTokens} outputTokens=${call.usage?.completionTokens}")
                }
                assertEquals(3, requests)
                assertTrue(analysis.task(runId).state.historicalFeedbackResolutions.isEmpty())
                observed.forEachIndexed { index, (actual, missing) ->
                    assertEquals("Synthetic case $index semantic classification", expected[index], actual)
                    assertEquals("Missing old statement must remain uncertain", HistoricalFeedbackAssessment.UNCERTAIN, missing)
                }
                assertTrue(repository.knowledgeHeads().isEmpty())
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { directory.deleteRecursively(); context.deleteDatabase(database) }
        }
    }

    private class SyntheticStatements(private val statements: List<String>) : AnalysisProvider {
        override val id = "synthetic-history-quality-fixture"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return BatchProposal(statements.map { ProposedClaim(it, listOf(id), ClaimKind.POSSIBLE_INTERPRETATION) })
        }
    }
}
