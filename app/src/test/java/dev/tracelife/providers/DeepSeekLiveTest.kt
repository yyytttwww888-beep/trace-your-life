package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.AnalysisBatchProcessor
import dev.tracelife.analysis.AnalysisProcessingStatus
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.analysis.TokenCountKind
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

/** Explicitly opt-in, one paid request with synthetic data only. Never runs in ordinary CI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekLiveTest {
    @Test fun `configured key can run the production adapter through controlled synthetic analysis`() {
        assumeTrue("Live model testing is explicitly opt-in", System.getProperty("traceLife.deepSeekSmoke") == "true")
        val configuredPath = System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path
        val keyFile = File(configuredPath).canonicalFile
        val repositoryRoot = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(repositoryRoot.toPath())) { "API key file must be outside the repository" }
        check(keyFile.isFile && keyFile.length() in 8..514) { "Configure the external DeepSeek API key file first" }
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val databaseName = "synthetic-live-${UUID.randomUUID()}.db"
        val keyDirectory = File(context.noBackupFilesDir, "synthetic-live-key-${UUID.randomUUID()}")
        // JVM has no Android Keystore; only its wrapping key is substituted. Provider and HTTP are production code.
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(keyDirectory, context.packageName) { wrappingKey }
        try {
            credentials.configure(key)
            key.fill('\u0000')
            LifeRepository(context, databaseName = databaseName).use { repository ->
                val date = LocalDate.parse("2026-10-17")
                repository.createRecord(RecordDraft("合成测试资料，不是真实个人记录：我今天学习了番茄工作法，试做了一次25分钟练习。计划两次，实际只完成一次。", Occurrence(date)), "synthetic-note")
                val analysis = ControlledAnalysis(repository)
                val task = analysis.start(AnalysisRequest("整理这条合成记录的观察和未知项，不判断长期人格。", AnalysisQuery(date, date, "Asia/Shanghai")))
                val report = AnalysisBatchProcessor(analysis).process(task.stored.id, DeepSeekProvider(credentials), maxBatches = 1)
                val finalTask = analysis.task(task.stored.id)
                check(report.status == AnalysisProcessingStatus.ALL_TEXT_PROCESSED) {
                    "Synthetic DeepSeek test did not complete: ${finalTask.state.calls.lastOrNull()?.failureCode ?: report.status.name}"
                }
                val call = finalTask.state.calls.single()
                assertEquals(TokenCountKind.CONSERVATIVE_UTF8_BOUND, call.tokenCountKind)
                assertNotNull(call.usage)
                assertTrue(call.usage!!.promptTokens <= call.inputTokens)
                // Do not log source text, response text, request headers or keys in test output.
                assertNotNull(call.proposal)
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { keyDirectory.deleteRecursively(); context.deleteDatabase(databaseName) }
        }
    }
}
