package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
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

/** Explicit opt-in: at most one synthesis and one review request, synthetic notes, no retries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekSynthesisLiveTest {
    @Test fun `real bounded synthesis creates an interpretation and reviewer rereads every input`() {
        assumeTrue("Live synthesis is explicitly opt-in", System.getProperty("traceLife.deepSeekSynthesis") == "true")
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514) {
            "Configure the external DeepSeek API key file first"
        }
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-synthesis-key-${UUID.randomUUID()}")
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { wrappingKey }
        val database = "synthetic-live-synthesis-${UUID.randomUUID()}.db"
        try {
            credentials.configure(key)
            key.fill('\u0000')
            LifeRepository(context, databaseName = database).use { repository ->
                val from = LocalDate.parse("2026-10-12")
                repository.createRecord(RecordDraft("合成测试资料，不是真实个人经历：周一计划完成一个最小代码练习。实际先阅读资料，" +
                    "又不断查新概念，最后没有动手。只记录了这一次，不能据此判断长期能力。", Occurrence(from)), "synthetic-first")
                repository.createRecord(RecordDraft("合成测试资料，不是真实个人经历：周二先尝试一个最小练习，卡住后才查资料，完成了练习。" +
                    "当日练习更容易，时间也更充足，不能确定先后顺序就是原因。下次愿意尝试先动手再查，并记录难度和可用时间。", Occurrence(from.plusDays(1))), "synthetic-second")
                val analysis = ControlledAnalysis(repository)
                val task = analysis.start(AnalysisRequest("仅根据这两条合成记录，给出一条有依据的可能解释，" +
                    "对照期望与实际，保留难度和时间的替代解释。最多两条待核对陈述，不判断长期人格或整周表现。",
                    AnalysisQuery(from, from.plusDays(6), "Asia/Shanghai")))
                val provider = DeepSeekProvider(credentials)
                val result = PeriodSynthesisProcessor(analysis).process(task.stored.id, provider, maxBatches = 1)
                val call = analysis.task(task.stored.id).state.calls.single()
                check(result.status == SynthesisProcessingStatus.INPUTS_PROCESSED) {
                    "Synthetic synthesis did not complete: ${call.failureCode ?: result.status.name}"
                }
                val claims = requireNotNull(call.proposal).claims
                assertTrue("Expected a bounded synthetic comparison", claims.size in 1..2)
                assertTrue(claims.all { it.kind == ClaimKind.POSSIBLE_INTERPRETATION })
                val index = claims.indexOfFirst { it.evidenceIds.size >= 2 }
                assertTrue("Expected original evidence from both synthetic notes", index >= 0)
                val target = analysis.claimRef(task.stored.id, call.batchKey, index)
                val review = analysis.reviewClaim(task.stored.id, "synthetic-live-review", target, provider = provider)
                val reviewCall = requireNotNull(analysis.currentReview(task.stored.id, target))
                assertEquals(call.evidence.toSet(), reviewCall.evidence.toSet())
                assertEquals(ReviewVerdict.SUPPORTED, review.verdict)
                listOf(call, reviewCall).forEach { saved ->
                    val usage = requireNotNull(saved.usage)
                    assertTrue(usage.promptTokens <= saved.inputTokens)
                    println("synthetic-synthesis purpose=${saved.purpose} promptTokens=${usage.promptTokens} completionTokens=${usage.completionTokens}")
                }
                println("synthetic-synthesis claims=${claims.size} review=${review.verdict} integration=${result.progress.integration}")
                assertTrue(repository.knowledgeHeads().isEmpty()) // A successful model check is not user adoption.
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { directory.deleteRecursively(); context.deleteDatabase(database) }
        }
    }
}
