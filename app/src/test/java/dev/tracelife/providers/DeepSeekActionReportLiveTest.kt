package dev.tracelife.providers

import android.content.Context
import dev.tracelife.actions.*
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import dev.tracelife.transfer.DataTransfer
import dev.tracelife.transfer.ExportScope
import dev.tracelife.transfer.ReadingExportOptions
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
import java.util.zip.ZipFile
import javax.crypto.KeyGenerator

/** Synthetic leaves; at most three real requests: reduction, source scan, independent review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekActionReportLiveTest {
    @Test fun `real action reduction scan and review publish a portable report with original plans`() {
        assumeTrue("Live action report is opt-in", System.getProperty("traceLife.deepSeekActionReport") == "true")
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514)
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-action-report-${UUID.randomUUID()}").apply { check(mkdir()) }
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(File(directory, "credentials"), context.packageName) { wrappingKey }
        val database = "synthetic-live-action-report-${UUID.randomUUID()}.db"
        val clock = MutableClock()
        try {
            credentials.configure(key); key.fill('\u0000')
            LifeRepository(context, clock, database).use { repository ->
                val from = LocalDate.parse("2026-10-12")
                val through = from.plusDays(6)
                val original = ActionDraft("合成测试：代码练习", "合成：下班有空时", "合成：二十分钟代码练习",
                    "合成：记录执行情况与可用时间", through, "Asia/Shanghai")
                val actions = ActionExperiments(repository, clock)
                actions.create(original, "synthetic-action")
                clock.now = Instant.parse("2026-10-19T04:00:00Z")
                actions.review("synthetic-action", 1, "synthetic-review", ActionReview(ActionExecution.NOT_EXECUTED,
                    "合成测试资料，不是真实经历：本周未执行二十分钟练习。加班后没有足够空闲时间，无法判断方法效果。",
                    ActionDecision.ADJUST, through), adjustedDraft = original.copy(behavior = "合成：下周五分钟最小步骤",
                        reviewOn = through.plusDays(7)))
                repository.createRecord(RecordDraft("合成测试资料，不是真实经历：本周未开始二十分钟代码练习，" +
                    "自述加班后没有足够时间；不能判断方法效果。下周五分钟步骤仅为计划，尚未实施。", Occurrence(through)), "synthetic-context")
                val analysis = ControlledAnalysis(repository, clock)
                val period = ActionPeriodReflection(analysis)
                val task = period.start(AnalysisRequest("把合成资料整合为恰好一条有限的自述判断：用户报告本周未执行二十分钟练习，" +
                    "下周调整为五分钟仍是未实施计划，因此尚不能判断方法效果。保留用户报告性质，不推断因果、成长或人格。",
                    AnalysisQuery(from, through, "Asia/Shanghai"), AnalysisLimits(inputTokenLimit = 30_000)))
                val fixture = object : AnalysisProvider {
                    override val id = "synthetic-action-report-fixture"
                    override val contextWindowTokens = 100_000
                    override fun countInputTokens(request: ModelRequest) = 10
                    override fun complete(request: ModelRequest): BatchProposal {
                        val json = JSONObject(request.payloadJson)
                        val actionInputs = json.getJSONArray("actionEvidence")
                        val recordInputs = json.getJSONArray("evidence")
                        val inputs = if (actionInputs.length() > 0) actionInputs else recordInputs
                        val text = when (actionInputs.optJSONObject(0)?.optString("kind")) {
                            "OPENING_PLAN" -> "合成期初计划是二十分钟练习；制定计划不能证明执行。"
                            "USER_REVIEW" -> "合成回看自述本周未执行二十分钟练习；下周五分钟步骤只是新计划，方法效果未知。"
                            else -> "合成记录自述本周未开始二十分钟练习，加班后时间不足；下周五分钟步骤尚未实施，不能判断方法效果。"
                        }
                        return BatchProposal(listOf(ProposedClaim(text,
                            (0 until inputs.length()).map { inputs.getJSONObject(it).getString("id") }, ClaimKind.POSSIBLE_INTERPRETATION)))
                    }
                }
                val generated = period.process(task.stored.id, fixture, maxBatches = 3, inputsPerBatch = 1)
                assertEquals(ActionPeriodIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED, generated.progress.integration)
                assertEquals(3, generated.completedBatchesThisPass)
                val real = DeepSeekProvider(credentials)
                var requests = 0
                fun count() { check(++requests <= 3) { "Synthetic action report request cap exceeded" } }
                val reductionProvider = object : AnalysisProvider by real {
                    override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                        count()
                        val envelope = JSONObject(request.original.payloadJson)
                        assertTrue(envelope.getBoolean("actionPeriod"))
                        assertEquals(3, envelope.getJSONArray("derivedInputs").length())
                        return real.completePrepared(request)
                    }
                }
                val reduced = period.reduce(task.stored.id, reductionProvider, maxRequests = 1)
                assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, reduced.status)
                val rootRef = requireNotNull(reduced.progress.root)
                assertEquals(1, analysis.reductionNode(task.stored.id, rootRef.batchKey).digest.statements.size)
                val target = analysis.reductionStatementRef(task.stored.id, rootRef, 0)
                AnalysisFeedback(repository, clock).respondReduction(task.stored.id, target, analysis.task(task.stored.id).stored.version,
                    ClaimFeedbackDecision.CONFIRMED, "合成测试显式模拟确认，不是真实用户判断")
                val scan = object : SemanticReductionScanProvider by real {
                    override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
                        count()
                        val envelope = JSONObject(request.original.payloadJson)
                        assertEquals(1, envelope.getJSONArray("evidence").length())
                        assertEquals(2, envelope.getJSONArray("actionEvidence").length())
                        return real.scanPrepared(request)
                    }
                }
                val review = object : SemanticReviewProvider by real {
                    override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                        count()
                        assertTrue(JSONObject(request.original.payloadJson).getBoolean("actionPeriod"))
                        return real.reviewPrepared(request)
                    }
                }
                val checked = PeriodReductionReviewProcessor(analysis).process(task.stored.id, target, scan, review, maxRequests = 2)
                assertEquals(ReductionReviewStatus.SUPPORTED, checked.status)
                val final = requireNotNull(analysis.currentReductionReview(task.stored.id, target))
                val library = KnowledgeLibrary(repository, clock)
                val note = library.publishReduction(task.stored.id, target, final.batchKey, analysis.task(task.stored.id).stored.version)
                assertEquals(KnowledgeEvidenceState.CURRENT, library.get(note.id)!!.evidenceState)
                assertEquals(2, requireNotNull(note.reductionProof).originalActionEvidence.size)
                val archive = File(directory, "synthetic-reading.zip")
                DataTransfer(context, repository, clock).exportReadingPackage(archive,
                    ReadingExportOptions(scope = ExportScope(from, through), pageSize = 1))
                ZipFile(archive).use { zip ->
                    val markdown = zip.entries().asSequence().filter { it.name.endsWith(".md") }
                        .joinToString("\n") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                    assertTrue(markdown.contains(note.claim.text))
                    assertTrue(markdown.contains(original.behavior))
                    assertTrue(markdown.contains("合成：下周五分钟最小步骤"))
                }
                assertEquals(3, requests)
                analysis.task(task.stored.id).state.calls.filter { it.providerId == real.id }.forEach { call ->
                    val usage = requireNotNull(call.usage)
                    println("synthetic-action-report purpose=${call.purpose} promptTokens=${usage.promptTokens} completionTokens=${usage.completionTokens}")
                }
                println("synthetic-action-report requests=$requests review=${checked.status} knowledgeExported=true")
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { directory.deleteRecursively(); context.deleteDatabase(database) }
        }
    }
    private class MutableClock : Clock() {
        var now = Instant.parse("2026-10-11T04:00:00Z")
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
