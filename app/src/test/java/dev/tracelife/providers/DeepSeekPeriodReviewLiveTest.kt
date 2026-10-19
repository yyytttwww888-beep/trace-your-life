package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
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

/** At most two paid requests: one source scan and one final review. All material is synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekPeriodReviewLiveTest {
    @Test fun `real source scan and independent final review permit confirmed synthetic publication`() {
        assumeTrue("Live period review is explicitly opt-in", System.getProperty("traceLife.deepSeekPeriodReview") == "true")
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514) {
            "Configure the external DeepSeek API key file first"
        }
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-period-review-key-${UUID.randomUUID()}")
        val master = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { master }
        val database = "synthetic-live-period-review-${UUID.randomUUID()}.db"
        try {
            credentials.configure(key); key.fill('\u0000')
            LifeRepository(context, databaseName = database).use { repository ->
                val day = LocalDate.parse("2026-10-12")
                listOf("合成周一：我完成了一个小练习，没有对照实验，也不知道为何能完成。",
                    "合成周二：我完成了一个小练习，任务较简单，不能证明学习方法有效。").forEachIndexed { index, body ->
                    repository.createRecord(RecordDraft("明确标注的合成测试资料，不是真实个人经历。$body", Occurrence(day.plusDays(index.toLong()))), "note-$index")
                }
                val analysis = ControlledAnalysis(repository)
                analysis.start(AnalysisRequest("只核对合成草稿的有限自述，不推断因果或实际能力。",
                    AnalysisQuery(day, day.plusDays(6), "Asia/Shanghai"), AnalysisLimits(inputTokenLimit = 30_000)), "run")
                val fixtureProvider = object : AnalysisProvider {
                    override val id = "synthetic-period-review-fixture"
                    override val contextWindowTokens = 100_000
                    override fun countInputTokens(request: ModelRequest) = 10
                    override fun complete(request: ModelRequest): BatchProposal {
                        val json = JSONObject(request.payloadJson)
                        val isReduction = json.getString("purpose") == "REDUCTION"
                        val refs = json.getJSONArray(if (isReduction) "derivedInputs" else "evidence")
                        return BatchProposal(listOf(ProposedClaim(if (isReduction)
                            "两条合成自述均提到完成一个小练习，但不能据此认定任何方法有效。" else "合成自述提到完成一个小练习，未证明方法有效。",
                            (0 until refs.length()).map { refs.getJSONObject(it).getString("id") }, ClaimKind.POSSIBLE_INTERPRETATION)))
                    }
                }
                repeat(2) { analysis.synthesize("run", "leaf-$it", listOf(EvidenceSelection("note-$it", "body")), fixtureProvider) }
                val reduction = PeriodReductionProcessor(analysis).process("run", fixtureProvider)
                val target = analysis.reductionStatementRef("run", requireNotNull(reduction.progress.root), 0)
                AnalysisFeedback(repository).respondReduction("run", target, analysis.task("run").stored.version,
                    ClaimFeedbackDecision.CONFIRMED, "测试程序模拟用户对合成陈述的明确确认。")
                val real = DeepSeekProvider(credentials)
                var requests = 0
                val scan = object : SemanticReductionScanProvider by real {
                    override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
                        check(++requests <= 2) { "Live period review request cap exceeded" }
                        return real.scanPrepared(request)
                    }
                }
                val review = object : SemanticReviewProvider by real {
                    override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                        check(++requests <= 2) { "Live period review request cap exceeded" }
                        return real.reviewPrepared(request)
                    }
                }
                val report = PeriodReductionReviewProcessor(analysis).process("run", target, scan, review, maxRequests = 2)
                val calls = analysis.task("run").state.calls.filter { it.purpose in setOf(CallPurpose.REDUCTION_SCAN, CallPurpose.REDUCTION_REVIEW) }
                check(report.status == ReductionReviewStatus.SUPPORTED) {
                    "Synthetic period review not supported: ${calls.lastOrNull()?.failureCode ?: report.status.name}"
                }
                assertEquals(2, requests)
                assertTrue(report.progress.allOriginalsScanned)
                assertEquals(2, report.progress.finalRereadFragments)
                val final = requireNotNull(analysis.currentReductionReview("run", target))
                val library = KnowledgeLibrary(repository)
                val publication = library.publishReduction("run", target, final.batchKey, analysis.task("run").stored.version,
                    topics = setOf("合成学习"))
                assertEquals(KnowledgeEvidenceState.CURRENT, library.get(publication.id)!!.evidenceState)
                assertNotNull(publication.reductionProof)
                calls.forEach { call ->
                    val usage = requireNotNull(call.usage)
                    println("synthetic-period-review purpose=${call.purpose} promptTokens=${usage.promptTokens} completionTokens=${usage.completionTokens}")
                }
                println("synthetic-period-review requests=$requests scanned=${report.progress.scannedFragments} finalReread=${report.progress.finalRereadFragments} publications=${repository.knowledgeHeads().size}")
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { directory.deleteRecursively(); context.deleteDatabase(database) }
        }
    }
}
