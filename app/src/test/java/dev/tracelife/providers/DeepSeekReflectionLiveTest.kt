package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
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

/** Explicit opt-in; two paid requests at most, entirely synthetic source text, no retries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekReflectionLiveTest {
    @Test fun `real reflection exposes reusable findings and saves only after confirmation and rereading`() {
        assumeTrue("Live reflection requires explicit opt-in", System.getProperty("traceLife.deepSeekReflection") == "true")
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514) {
            "Configure the external test credential first"
        }
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-reflection-key-${UUID.randomUUID()}")
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { wrappingKey }
        val database = "synthetic-live-reflection-${UUID.randomUUID()}.db"
        try {
            credentials.configure(key)
            key.fill('\u0000')
            LifeRepository(context, databaseName = database).use { repository ->
                val from = LocalDate.parse("2026-10-12")
                repository.createRecord(RecordDraft("合成测试资料，不是真实个人经历：周一原计划完成一个代码练习，" +
                    "实际不断查新概念，没有开始写代码。我觉得缺少一个明确的停止查阅条件。", Occurrence(from)), "synthetic-first")
                repository.createRecord(RecordDraft("合成测试资料，不是真实个人经历：周二先写最小练习，遇到具体问题才查资料，完成了练习。" +
                    "今天任务更容易，可用时间更长，不能确定是先动手起了作用。", Occurrence(from.plusDays(1))), "synthetic-second")
                repository.createRecord(RecordDraft("合成测试资料，不是真实个人经历：学到的经验是先把练习缩小到一个可运行步骤，" +
                    "遇到问题写清楚再查资料。下次愿意继续尝试，并记下难度、时间及是否完成。资料只有这几天。", Occurrence(from.plusDays(2))), "synthetic-third")
                val analysis = ControlledAnalysis(repository)
                val workflow = ReflectionWorkflow(repository, analysis)
                val provider = DeepSeekProvider(credentials)
                val started = workflow.start(AnalysisQuery(from, from.plusDays(6), "Asia/Shanghai"))
                val result = workflow.process(started.id, provider, maxRequests = 1)
                check(result.status == ReflectionStatus.READY) { "Synthetic reflection did not complete: ${result.status}" }
                val findings = result.run.findings
                assertTrue("Expected useful bounded findings", findings.size in 1..6)
                assertTrue("Findings should expose purpose without a separate interpretation step", findings.all {
                    listOf("值得留下：", "值得关注：", "可以尝试：").any(it.text::startsWith)
                })
                assertTrue("Wording should address the person reading their own findings",
                    findings.none { it.text.substringAfter("：").trimStart().startsWith("用户") })
                assertTrue(findings.any { it.text.startsWith("值得留下：") })
                assertTrue(findings.any { it.text.startsWith("值得关注：") })
                val generated = requireNotNull(analysis.task(started.id).state.calls.single().proposal)
                assertTrue("At least one finding should compare multiple original records",
                    generated.claims.any { it.evidenceIds.distinct().size >= 2 })
                assertTrue(findings.all { it.knowledgeId == null && it.feedback == null })
                val chosen = findings.first { it.text.startsWith("值得留下：") }
                assertTrue(workflow.evidence(started.id, chosen.target).items.size >= 2)
                workflow.respond(started.id, chosen.target, result.run.taskVersion, ClaimFeedbackDecision.CONFIRMED)
                val saved = workflow.reviewAndSave(started.id, chosen.target, provider, provider, maxRequests = 1, topics = setOf("学习"))
                val adopted = saved.run.findings.single { it.target == chosen.target }
                assertEquals(ReviewVerdict.SUPPORTED, adopted.reviewVerdict)
                val id = requireNotNull(adopted.knowledgeId) { "Confirmed and supported finding should be available in knowledge" }
                assertEquals(chosen.text, KnowledgeLibrary(repository).get(id)!!.value.claim.text)
                assertEquals(2, saved.run.totalRequests)
                val reopened = ReflectionWorkflow(repository, ControlledAnalysis(repository)).history().single()
                assertEquals(saved.run.id, reopened.id)
                assertEquals(id, reopened.findings.single { it.target == chosen.target }.knowledgeId)
                analysis.task(started.id).state.calls.forEach { call ->
                    val usage = requireNotNull(call.usage)
                    println("synthetic-reflection purpose=${call.purpose} promptTokens=${usage.promptTokens} completionTokens=${usage.completionTokens}")
                }
                println("synthetic-reflection findings=${findings.size} saved=1 review=${adopted.reviewVerdict}")
                // All input here is declared synthetic; this output permits human quality inspection.
                findings.forEach { println("synthetic-reflection finding=${it.text}") }
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { directory.deleteRecursively(); context.deleteDatabase(database) }
        }
    }
}
