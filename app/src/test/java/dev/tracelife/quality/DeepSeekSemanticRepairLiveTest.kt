package dev.tracelife.quality

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.providers.*
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.LifeRepository
import org.json.JSONArray
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

/** Independent held-out targets, frozen before the repair evaluation: six errors and six controls.
 * A scripted proposal fixes each target; the production review and publication gates are real.
 * One transport dispatch per case, at most twelve for the entire group, and no selective retries.
 * Gold expectations never enter the model request. This does not evaluate free generation or
 * certify all returned reasons/unknowns: every response remains available for independent review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekSemanticRepairLiveTest {
    private val qualityModel = DeepSeekModel.valueOf(System.getProperty("traceLife.qualityModel") ?: "FLASH")

    @Test fun `held out real reviews preserve time frequency suggestions causal limits and evidence strength`() {
        assumeTrue("Held-out semantic evaluation requires explicit quality opt-in",
            System.getProperty("traceLife.deepSeek.quality") == "true")
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514)
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val keyDirectory = File(context.noBackupFilesDir, "synthetic-semantic-repair-key-${UUID.randomUUID()}")
        val wrapping = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(keyDirectory, context.packageName) { wrapping }
        val report = SyntheticQualityReport("deepseek-semantic-repair-heldout", FIXTURE_VERSION)
        val fixture = JSONObject().put("synthetic", true).put("fixtureVersion", FIXTURE_VERSION)
            .put("maximumPaidRequests", MAXIMUM_REQUESTS).put("cases", JSONArray(cases.map(::sampleJson)))
            .toString(2)
        File(report.directory, "fixed-inputs.json").writeText(fixture)
        val fixtureHash = AnalysisRunCodec.sha256(fixture)
        val failures = mutableListOf<String>()
        var requests = 0
        try {
            credentials.configure(key); key.fill('\u0000')
            cases.forEach { sample ->
                val database = "synthetic-semantic-repair-${sample.id}-${UUID.randomUUID()}.db"
                val started = System.nanoTime()
                var sampleRequests = 0
                val row = sampleJson(sample).put("fixtureKind", "heldout_authored_target_review_v1")
                    .put("fixtureSha256", fixtureHash).put("syntheticUserConfirmation", true)
                    .put("model", qualityModel.apiId).put("maximumPaidRequests", 1)
                    .put("maximumGroupPaidRequests", MAXIMUM_REQUESTS).put("generationTested", false)
                    .put("unknownsReview", "REQUIRES_INDEPENDENT_REVIEW")
                    .put("publicationAttempted", false).put("published", false)
                try {
                    LifeRepository(context, SyntheticQualityCases.clock, database).use { repository ->
                        sample.records.forEachIndexed { index, source ->
                            repository.createRecord(RecordDraft("合成验收资料，完全虚构：" + source.text,
                                Occurrence(LocalDate.parse(source.date))), "synthetic-source-$index")
                        }
                        val analysis = ControlledAnalysis(repository, SyntheticQualityCases.clock)
                        val task = analysis.start(AnalysisRequest("核对这条限定范围的描述；用户确认不表示描述正确。",
                            AnalysisQuery(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-20"), "Asia/Shanghai"),
                            limits = AnalysisLimits(maxCalls = 2)))
                        analysis.synthesize(task.stored.id, "authored-target", sample.records.indices.map {
                            EvidenceSelection("synthetic-source-$it", "body")
                        }, object : AnalysisProvider {
                            override val id = "synthetic-heldout-authored-target-v1"
                            override val contextWindowTokens = 100_000
                            override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
                            override fun complete(request: ModelRequest): BatchProposal {
                                val evidence = JSONObject(request.payloadJson).getJSONArray("evidence")
                                val ids = (0 until evidence.length()).map { evidence.getJSONObject(it).getString("id") }
                                return BatchProposal(listOf(ProposedClaim(sample.target, ids, ClaimKind.POSSIBLE_INTERPRETATION)))
                            }
                        })
                        val claim = analysis.claimRef(task.stored.id, "authored-target", 0)
                        AnalysisFeedback(repository, SyntheticQualityCases.clock).respond(task.stored.id, claim,
                            analysis.task(task.stored.id).stored.version, ClaimFeedbackDecision.CONFIRMED)
                        val actual = HttpsDeepSeekTransport()
                        val transport = DeepSeekTransport { body, secret ->
                            check(sampleRequests == 0 && requests < MAXIMUM_REQUESTS) { "Held-out request ceiling reached" }
                            sampleRequests++; requests++
                            File(report.directory, "${sample.id}-request.json").writeText(body)
                            actual.execute(body, secret).also { response ->
                                row.put("httpStatus", response.status).put("responseBytes", response.body.size)
                                // Preserve even malformed/semantically wrong successful HTTP responses.
                                // Error bodies may contain account information, so retain status only.
                                if (response.status == 200) {
                                    val responseFile = "${sample.id}-response.json"
                                    File(report.directory, responseFile).writeBytes(response.body)
                                    row.recordRawReviewResponse(response.body, responseFile)
                                }
                            }
                        }
                        val provider = DeepSeekProvider(credentials, qualityModel, transport)
                        row.put("adapter", provider.id)
                        val reviewed = runCatching { analysis.reviewClaim(task.stored.id, "real-review", claim, provider = provider) }
                        val publication = runCatching { KnowledgeLibrary(repository, SyntheticQualityCases.clock).publish(
                            task.stored.id, claim, "real-review", KnowledgeCategory.INSIGHT, analysis.task(task.stored.id).stored.version) }
                        val review = reviewed.getOrNull()
                        row.withReviewDiagnostics(review).put("publicationAttempted", true).put("published", publication.isSuccess)
                            .put("publicationFailureType", publication.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                            .put("reviewFailureType", reviewed.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                            .put("calls", JSONArray(analysis.task(task.stored.id).state.calls.map { call -> JSONObject().withCallDiagnostics(call)
                                .put("purpose", call.purpose.name).put("status", call.status.name)
                                .put("requestSha256", call.requestSha256).put("providerId", call.providerId)
                                .put("inputBytes", call.inputBytes).put("inputTokens", call.inputTokens) }))
                        review?.let {
                            row.put("verdict", it.verdict.name).put("issues", JSONArray(it.issues.map { issue -> issue.name }))
                                .put("unknowns", JSONArray(it.unknowns))
                                .put("supportingEvidenceIds", JSONArray(it.supportingEvidenceIds))
                                .put("contradictingEvidenceIds", JSONArray(it.contradictingEvidenceIds))
                        }
                        val gateMatched = publication.isSuccess == (review?.verdict == ReviewVerdict.SUPPORTED)
                        row.put("gateMatchedReview", gateMatched)
                        assertTrue("Publication follows an accepted current review, including failure blocking", gateMatched)
                        if (review == null) throw requireNotNull(reviewed.exceptionOrNull())
                        val expectedSupported = ReviewVerdict.SUPPORTED in sample.expectedVerdicts
                        val supportMatched = expectedSupported == (review.verdict == ReviewVerdict.SUPPORTED)
                        val labelMatched = review.verdict in sample.expectedVerdicts
                        val publishedMatched = publication.isSuccess == expectedSupported
                        row.put("reviewMatchedAuthoredExpectation", supportMatched)
                            .put("verdictLabelMatchedAuthoredExpectation", labelMatched)
                            .put("publicationMatchedAuthoredExpectation", publishedMatched)
                        val matched = supportMatched && labelMatched && publishedMatched
                        if (!matched) failures += sample.id
                        row.put("programmaticStatus", "PASS").put("semanticStatus", if (matched)
                            "FIXED_EXPECTATION_MATCHED_NOT_GENERAL_ACCURACY" else "FIXED_EXPECTATION_FAILED")
                    }
                } catch (failure: Throwable) {
                    if (sample.id !in failures) failures += sample.id
                    row.put("programmaticStatus", "FAIL").put("failureType", failure.javaClass.simpleName)
                        .put("semanticStatus", "NOT_ESTABLISHED_DUE_TO_EXECUTION_FAILURE")
                } finally {
                    row.put("paidRequests", sampleRequests).put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                    report.add(row); context.deleteDatabase(database)
                }
            }
            assertEquals("All fixed cases and failures retained in ${report.directory.path}; no selective reruns",
                emptyList<String>(), failures)
            assertEquals(cases.size, requests)
            assertTrue(requests <= MAXIMUM_REQUESTS)
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { keyDirectory.deleteRecursively() }
        }
    }

    private fun sampleJson(sample: Sample) = JSONObject().put("id", sample.id)
        .put("source", sample.records.joinToString("\n") { "${it.date}：${it.text}" })
        .put("sources", JSONArray(sample.records.map { JSONObject().put("date", it.date).put("text", it.text) }))
        .put("target", sample.target).put("expectedSupported", ReviewVerdict.SUPPORTED in sample.expectedVerdicts)
        .put("expectedVerdicts", JSONArray(sample.expectedVerdicts.map { it.name }.sorted()))
        .put("requiredDistinctions", JSONArray(listOf(sample.distinction)))

    private data class Source(val date: String, val text: String)
    private data class Sample(val id: String, val records: List<Source>, val target: String,
        val expectedVerdicts: Set<ReviewVerdict>, val distinction: String)

    private val supported = setOf(ReviewVerdict.SUPPORTED)
    private val contradicted = setOf(ReviewVerdict.CONTRADICTED)
    private val unsupportedWithoutCounterevidence = setOf(ReviewVerdict.UNSUPPORTED, ReviewVerdict.INSUFFICIENT_CONTEXT)
    private val readingPlan = listOf(Source("2026-09-16", "我打算从下周开始，每次午饭结束就立即读书八分钟。还没有试过这个安排。"))
    private val weeklyPractice = listOf(
        Source("2026-09-15", "今天有一次口语练习，我把一段对话跟读了两遍。"),
        Source("2026-09-20", "本周总结：这一整周只有周二那一次口语练习，之后没有再练。我没有每天写日记，也没有统计其他阅读活动。"),
    )
    private val forgottenLesson = listOf(Source("2026-09-17", "今晚跟着视频看懂了一个算法，但关掉视频后想不起关键步骤。我还没试过合书口述或独立做题。"))
    private val remoteDemo = listOf(Source("2026-09-18", "这次远程演示没有跑通。当天网络有延迟，代理配置也变了；究竟哪里导致失败还没排查。"))
    private val absentHistory = listOf(Source("2026-09-19", "今天练打字时使用了分组练习。没有更早的练习记录，我也没有回忆过去半年是否采用过这个做法。"))
    private val submission = listOf(Source("2026-09-19", "截至周六睡前，我明确还没有提交这一份练习。之后有没有补交，这份记录没有记载。"))

    private val cases = listOf(
        Sample("duration-became-wait", readingPlan,
            "新安排是午饭结束后先等八分钟再开始读书；它还只是打算，不能说已经有效。", contradicted,
            "时长不能改成启动前等待，尚未生效的限定不能抵消错误步骤"),
        Sample("actual-wait-control", listOf(Source("2026-09-16", "我计划周六先等十五分钟，再出门散步；散步要走多久还没定。这个计划尚未开始。")),
            "这项未开始的计划包含出门前等待十五分钟；记录没有给出散步持续时长。", supported,
            "确实写了等待时须承认等待，不能一概把所有分钟都当持续时长"),
        Sample("weekly-count-erased-by-missing-diaries", weeklyPractice,
            "由于没有每天写日记，本周究竟做过几次口语练习完全没有说明，只能记为次数未知。", contradicted,
            "完整周总结的明确次数不被逐日日记缺失抹去；一次内跟读两遍不是两次练习"),
        Sample("weekly-count-and-other-activities-control", weeklyPractice,
            "按本周总结，整周只有周二一次口语练习；其他阅读活动未统计，不能把没写日记的日子全部认定为没有学习。", supported,
            "承认指定活动的明确全周次数，同时保留其他活动未知"),
        Sample("future-experiment-control", forgottenLesson,
            "下次看完算法示例后，可合书口述关键步骤，再独立做一道小题，记录能否完成并在当天回看。这是待尝试的办法，还不能证明它改善记忆。", supported,
            "有原文针对性、启动及观察信号的未来建议不要求已经执行或已有成效"),
        Sample("untried-experiment-claimed-effective", forgottenLesson,
            "合书口述并独立做题已经减少了我忘记步骤的问题，所以应继续采用这一有效做法。", contradicted,
            "尚未尝试的方法不能被描述为已经使用并有效"),
        Sample("network-cooccurrence-became-cause", remoteDemo,
            "这次远程演示是因为网络延迟才没有跑通。", unsupportedWithoutCounterevidence,
            "同时出现的网络延迟不是已确认原因；原因未查清也不等于已经证明网络绝非原因"),
        Sample("network-conditions-control", remoteDemo,
            "这次演示未跑通，当天也记录了网络延迟和代理配置变更；哪些条件造成失败仍未查明。", supported,
            "保留明确结果和同现条件，不把可能原因升级为确定因果"),
        Sample("unrecorded-history-is-not-counterevidence", absentHistory,
            "分组练习是我过去半年一直坚持的打字方法。", unsupportedWithoutCounterevidence,
            "缺少半年历史只是不支持长期判断，不能标成原文已证明该判断相反"),
        Sample("unknown-history-control", absentHistory,
            "今天使用分组练习有记录；过去半年是否一直这样练习未知，既不能确认持续坚持，也不能仅因缺记录断言从未坚持。", supported,
            "准确保留当前已知与历史未知，区分无支持和有反证"),
        Sample("explicit-nonsubmission-became-unknown", submission,
            "这份记录完全没有说明周六睡前是否已经提交练习，因此当时的提交状态只能说未知。", contradicted,
            "截至明确时间的未提交不能因之后缺少记录而被改成未知"),
        Sample("known-cutoff-and-unknown-future-control", submission,
            "截至周六睡前这份练习明确未提交；记录之后是否补交没有说明，两种时间范围需要分开。", supported,
            "已知截止状态与后续未知可以同时成立"),
    )

    companion object {
        private const val FIXTURE_VERSION = "semantic-repair-heldout-v1"
        private const val MAXIMUM_REQUESTS = 12
    }
}
