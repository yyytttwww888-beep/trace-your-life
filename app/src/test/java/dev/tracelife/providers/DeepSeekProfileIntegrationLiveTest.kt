package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.profile.*
import dev.tracelife.quality.SyntheticQualityReport
import org.json.JSONArray
import org.json.JSONObject
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

/** Five paid calls at most, synthetic records only; does not confirm, publish or adopt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekProfileIntegrationLiveTest {
    private val qualityModel = DeepSeekModel.valueOf(System.getProperty("traceLife.qualityModel", "FLASH"))

    @Test fun `real longitudinal batches retain exceptions in an integrated draft with complete maintenance origins`() {
        assumeTrue("Live profile integration is explicitly opt-in", System.getProperty("traceLife.deepSeekProfile") == "true")
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514)
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-integrated-profile-key-${UUID.randomUUID()}")
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { wrappingKey }
        val database = "synthetic-live-integrated-profile-${UUID.randomUUID()}.db"
        val diagnostics = SyntheticQualityReport("deepseek-integrated-profile", "integrated-profile-original-v1")
        val started = System.nanoTime()
        val wireRequests = JSONArray()
        var wireCount = 0
        val row = JSONObject().put("id", "integrated-profile").put("model", qualityModel.apiId)
            .put("maximumPaidRequests", 5).put("maintained", false).put("published", false).put("adopted", false)
            .put("semanticStatus", "REQUIRES_INDEPENDENT_REVIEW")
        try {
            credentials.configure(key); key.fill('\u0000')
            LifeRepository(context, databaseName = database).use { repository ->
                val periods = listOf(ProfileComparisonPeriod(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31")),
                    ProfileComparisonPeriod(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30")))
                val sources = listOf(
                    "2026-08-05" to "练习A：我先读教程，再把目标缩成一个小例子，最后做出了结果。当天练习简单，时间充足，不能归因于某一种方法。",
                    "2026-08-19" to "练习B：继续先读教程，但没有开始动手。当天被工作打断。这个记录不能证明我总是拖延。",
                    "2026-09-06" to "练习C：我开始先写一个可运行的小例子，再按报错查资料，完成了练习。时间和难度与练习A不同；只是一条新经历。",
                    "2026-09-20" to "练习D：仍先写小例子，但任务难度较高、时间很少，没能完成。小例子并不总有效。此前练习C在周总结里再次被提到，两份文字都是同一个事件，不能增加独立支持。",
                )
                row.put("sources", JSONArray(sources.map { (date, body) -> JSONObject().put("date", date).put("body", body) }))
                sources.forEachIndexed { index, (date, body) -> repository.createRecord(RecordDraft(
                    "合成测试资料，不是真实个人经历：$body", Occurrence(LocalDate.parse(date)), topics = setOf("学习")), "synthetic-integrated-$index") }
                val analysis = ControlledAnalysis(repository)
                val generator = LongitudinalProfileGenerator(repository, analysis)
                generator.start(AnalysisRequest("只提出一条有情境限制的长期候选，比较八月至九月的练习方法如何变化。保留时间和难度的替代解释、未完成的例外；" +
                    "重复提到同一次练习不能增加事件数。每批只输出一条有跨期原文引用的claims，并保留unknowns；整体整理也只保留一条候选。不得声称稳定人格或方法必然有效。",
                    AnalysisQuery(periods.first().from, periods.last().through, "Asia/Shanghai"), AnalysisLimits(inputTokenLimit = 40_000),
                    profileTarget = ProfileAnalysisTarget(ProfileDimension.ABILITIES_AND_STRATEGIES, ProfileLayer.LONG_TERM, periods)), "synthetic-integrated-profile")
                val actual = HttpsDeepSeekTransport()
                val transport = DeepSeekTransport { body, secret ->
                    check(++wireCount <= 5) { "Synthetic integration request cap exceeded" }
                    val requestNumber = wireCount
                    File(diagnostics.directory, "request-$requestNumber.json").writeText(body)
                    val wire = JSONObject().put("index", requestNumber).put("request", "request-$requestNumber.json")
                    wireRequests.put(wire)
                    try {
                        actual.execute(body, secret).also { response ->
                            wire.put("httpStatus", response.status)
                            if (response.status == 200) {
                                File(diagnostics.directory, "response-$requestNumber.json").writeBytes(response.body)
                                wire.put("response", "response-$requestNumber.json")
                            }
                        }
                    } catch (failure: Throwable) {
                        wire.put("failureType", failure.javaClass.simpleName)
                        throw failure
                    }
                }
                val real = DeepSeekProvider(credentials, qualityModel, transport)
                var dispatched = 0
                val producer = object : AnalysisProvider by real {
                    override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                        check(++dispatched <= 5) { "Synthetic integration request cap exceeded" }
                        return real.completePrepared(request)
                    }
                }
                val reviewer = object : SemanticReviewProvider by real {
                    override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                        check(++dispatched <= 5) { "Synthetic integration request cap exceeded" }
                        return real.reviewPrepared(request)
                    }
                }
                val report = try {
                    generator.process("synthetic-integrated-profile", producer, reviewer, maxModelRequests = 5, fragmentsPerBatch = 3)
                } finally {
                    val state = analysis.task("synthetic-integrated-profile").state
                    File(diagnostics.directory, "task-ledger.json").writeText(AnalysisRunCodec.encode(state))
                    row.put("calls", JSONArray(state.calls.map { call -> JSONObject().put("purpose", call.purpose.name)
                        .put("status", call.status.name).put("failureCode", call.failureCode ?: JSONObject.NULL)
                        .put("inputBytes", call.inputBytes).put("inputTokens", call.inputTokens)
                        .put("promptTokens", call.usage?.promptTokens ?: JSONObject.NULL)
                        .put("completionTokens", call.usage?.completionTokens ?: JSONObject.NULL)
                        .put("originals", call.evidence.size) }))
                }
                row.put("status", report.status.name).put("dispatched", dispatched)
                println("synthetic-integrated-profile status=${report.status} requests=$dispatched")
                analysis.task(report.runId).state.calls.forEach { call ->
                    call.usage?.let { assertTrue(it.promptTokens <= call.inputTokens) }
                    println("synthetic-integrated-profile purpose=${call.purpose} status=${call.status} failure=${call.failureCode} promptTokens=${call.usage?.promptTokens} completionTokens=${call.usage?.completionTokens} originals=${call.evidence.size}")
                    call.proposal?.claims?.forEach { println("synthetic-integrated-profile local=${it.text}") }
                    call.review?.let { println("synthetic-integrated-profile localReview=${it.verdict}") }
                }
                assertEquals(LongitudinalProfileStatus.INTEGRATED, report.status)
                assertEquals(5, dispatched)
                assertEquals(2, report.progress.integration!!.leafCount)
                assertEquals(4, report.progress.integration!!.originalEvidenceFragments)
                val claim = report.progress.integratedCandidates.single()
                val detail = generator.integratedCandidate(report.progress, claim)
                assertEquals(4, detail.originalEvidence.size)
                val progress = ProfileMaintenance(repository, analysis).begin(report.runId, claim)
                assertEquals(ProfileMaintenanceStatus.SCAN_READY, progress.status)
                val proof = analysis.task(report.runId).state.profileMaintenancePlans.single().integration!!
                assertEquals(2, proof.leafRefs.size)
                assertEquals(detail.originalEvidence, proof.originalEvidence)
                assertTrue(repository.knowledgeHeads().isEmpty())
                assertTrue(repository.profileHeads().isEmpty())
                row.put("findings", JSONArray(listOf(JSONObject().put("text", detail.text))))
                    .put("unknowns", JSONArray(detail.unknowns)).put("leafBatches", 2).put("originals", 4)
                    .put("programmaticStatus", "PASS")
                println("synthetic-integrated-profile integrated=${detail.text}")
                println("synthetic-integrated-profile unknowns=${detail.unknowns}")
                println("synthetic-integrated-profile requests=$dispatched leafBatches=2 originals=4 maintained=false published=false")
            }
        } catch (failure: Throwable) {
            row.put("programmaticStatus", "FAIL").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            row.put("paidRequests", wireCount).put("wireRequests", wireRequests)
                .put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
            key.fill('\u0000')
            try { diagnostics.add(row) } finally {
                try { credentials.clear() } finally { directory.deleteRecursively(); context.deleteDatabase(database) }
            }
        }
    }
}
