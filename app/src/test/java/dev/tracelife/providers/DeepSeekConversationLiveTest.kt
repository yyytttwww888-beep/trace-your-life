package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.conversation.ConversationWorkflow
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.crypto.KeyGenerator

/** Explicit opt-in: at most two paid requests, only declared synthetic records, no automatic retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekConversationLiveTest {
    @Test fun `real three month question and selected followup use independently cited originals`() {
        assumeTrue("Live conversation requires explicit opt-in", System.getProperty("traceLife.deepSeekConversation") == "true")
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514)
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-conversation-key-${UUID.randomUUID()}")
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { wrappingKey }
        val database = "synthetic-live-conversation-${UUID.randomUUID()}.db"
        val clock = Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
        try {
            credentials.configure(key); key.fill('\u0000')
            LifeRepository(context, databaseName = database, clock = clock).use { repository ->
                listOf(
                    "2026-08-03" to "学习代码时主要连续看视频，没有练习。遇到陌生概念就继续找课程，原定练习没开始。",
                    "2026-09-07" to "改为先写最小例子，卡住时才查资料，完成了例子。但这天题目更简单，不能确定是哪种因素造成的。",
                    "2026-09-21" to "今天又一直找新资料，没有开始原定练习。虽然曾打算先做，但遇到难题还是拖延了。",
                    "2026-10-15" to "继续先做最小练习，并把查阅限制在一个具体问题。这次完成了；因为时间更充足，还不能认定拖延已解决。",
                ).forEachIndexed { index, (date, body) -> repository.createRecord(RecordDraft(
                    "合成测试资料，不是真实个人经历：$body", Occurrence(LocalDate.parse(date)),
                    topics = if (index == 2) emptySet() else setOf("学习")), "synthetic-conversation-$index") }
                val analysis = ControlledAnalysis(repository, clock)
                val reflection = ReflectionWorkflow(repository, analysis)
                val workflow = ConversationWorkflow(repository, analysis, reflection, clock)
                val provider = DeepSeekProvider(credentials)
                val first = workflow.submit(workflow.prepare("这三个月，我在学习上的方法发生了哪些变化，哪些问题反复出现？"))
                val firstRun = reflection.process(first.runId, provider, maxRequests = 1).run
                assertEquals(ReflectionStatus.READY, firstRun.status)
                assertTrue(firstRun.findings.size in 1..6)
                assertTrue(firstRun.findings.any { it.text.startsWith("变化：") })
                val selected = firstRun.findings.indexOfFirst { it.text.startsWith("反复出现：") }
                assertTrue("Synthetic repetition should be visible", selected >= 0)
                val second = workflow.submit(workflow.prepare("这个问题后来有没有改善，有什么反例？", first.threadId, first.id, selected))
                val secondRun = reflection.process(second.runId, provider, maxRequests = 1).run
                assertEquals(ReflectionStatus.READY, secondRun.status)
                assertTrue(secondRun.findings.isNotEmpty())
                assertTrue(secondRun.findings.all { reflection.evidence(second.runId, it.target).total > 0 })
                assertEquals(4, secondRun.coverage!!.records)
                assertTrue(repository.knowledgeHeads().isEmpty())
                assertEquals(1, analysis.task(second.runId).state.request.conversationContext!!.targets.size)
                listOf(firstRun, secondRun).forEachIndexed { index, run ->
                    analysis.task(run.id).state.calls.forEach { call ->
                        val usage = requireNotNull(call.usage)
                        println("synthetic-conversation turn=${index + 1} purpose=${call.purpose} promptTokens=${usage.promptTokens} completionTokens=${usage.completionTokens}")
                    }
                    run.findings.forEach { println("synthetic-conversation turn=${index + 1} finding=${it.text}") }
                    run.unknowns.forEach { println("synthetic-conversation turn=${index + 1} unknown=$it") }
                }
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { directory.deleteRecursively(); context.deleteDatabase(database) }
        }
    }
}
