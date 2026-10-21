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

/** One real review for each fixed authored target. Nine errors + two controls; never retries.
 * Synthetic user confirmation intentionally cannot excuse a semantically unsupported statement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekSemanticGuardLiveTest {
    private val qualityModel = DeepSeekModel.valueOf(System.getProperty("traceLife.qualityModel", "FLASH"))

    @Test fun `real reviews and publication gates reject authored errors while retaining bounded controls`() {
        assumeTrue("Semantic guard evaluation requires explicit opt-in", System.getProperty("traceLife.deepSeek.quality") == "true")
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514)
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-semantic-guard-key-${UUID.randomUUID()}")
        val wrapping = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { wrapping }
        val report = SyntheticQualityReport("deepseek-semantic-publication-guard", "authored-semantic-targets-v2")
        val failures = mutableListOf<String>()
        var requests = 0
        try {
            credentials.configure(key); key.fill('\u0000')
            cases.forEach { sample ->
                val database = "synthetic-semantic-guard-${sample.id}-${UUID.randomUUID()}.db"
                val started = System.nanoTime()
                val row = JSONObject().put("id", sample.id).put("fixtureKind", "authored_target_review_challenge_v2")
                    .put("target", sample.target).put("source", sample.source).put("expectedSupported", sample.supported)
                    .put("syntheticUserConfirmation", true).put("model", qualityModel.apiId)
                    .put("requiredDistinctions", JSONArray(listOf(sample.distinction))).put("maximumPaidRequests", 1)
                    .put("generationTested", false).put("unknownsReview", "ONLY_IF_INCLUDED_IN_AUTHORED_TARGET")
                var sampleRequests = 0
                try {
                    LifeRepository(context, SyntheticQualityCases.clock, database).use { repository ->
                        val date = LocalDate.parse("2026-10-19")
                        repository.createRecord(RecordDraft("合成验收资料，完全虚构：" + sample.source, Occurrence(date)), "synthetic-source")
                        val analysis = ControlledAnalysis(repository, SyntheticQualityCases.clock)
                        val task = analysis.start(AnalysisRequest("核对这条限定范围的描述；用户确认不表示描述正确。",
                            AnalysisQuery(date, date, "Asia/Shanghai")))
                        analysis.synthesize(task.stored.id, "authored-target", listOf(EvidenceSelection("synthetic-source", "body")),
                            object : AnalysisProvider {
                                override val id = "synthetic-authored-target"
                                override val contextWindowTokens = 100_000
                                override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
                                override fun complete(request: ModelRequest): BatchProposal {
                                    val evidenceId = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                                    return BatchProposal(listOf(ProposedClaim(sample.target, listOf(evidenceId), ClaimKind.POSSIBLE_INTERPRETATION)))
                                }
                            })
                        val claim = analysis.claimRef(task.stored.id, "authored-target", 0)
                        AnalysisFeedback(repository).respond(task.stored.id, claim, analysis.task(task.stored.id).stored.version, ClaimFeedbackDecision.CONFIRMED)
                        val actual = HttpsDeepSeekTransport()
                        val transport = DeepSeekTransport { body, secret ->
                            check(sampleRequests == 0 && requests < cases.size) { "Fixed semantic review request ceiling reached" }
                            sampleRequests++; requests++
                            File(report.directory, "${sample.id}-request.json").writeText(body)
                            actual.execute(body, secret).also { response ->
                                if (response.status == 200) {
                                    val responseFile = "${sample.id}-response.json"
                                    File(report.directory, responseFile).writeBytes(response.body)
                                    row.recordRawReviewResponse(response.body, responseFile)
                                }
                            }
                        }
                        val provider = DeepSeekProvider(credentials, qualityModel, transport)
                        val review = analysis.reviewClaim(task.stored.id, "real-review", claim, provider = provider)
                        val publication = runCatching { KnowledgeLibrary(repository, SyntheticQualityCases.clock).publish(task.stored.id,
                            claim, "real-review", KnowledgeCategory.INSIGHT, analysis.task(task.stored.id).stored.version) }
                        row.withReviewDiagnostics(review).put("verdict", review.verdict.name).put("issues", JSONArray(review.issues.map { it.name }))
                            .put("unknowns", JSONArray(review.unknowns)).put("supportingEvidenceIds", JSONArray(review.supportingEvidenceIds))
                            .put("contradictingEvidenceIds", JSONArray(review.contradictingEvidenceIds)).put("publicationAttempted", true)
                            .put("published", publication.isSuccess).put("publicationFailureType", publication.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                            .put("gateMatchedReview", publication.isSuccess == (review.verdict == ReviewVerdict.SUPPORTED))
                            .put("reviewMatchedAuthoredExpectation", sample.supported == (review.verdict == ReviewVerdict.SUPPORTED))
                        assertEquals("Publication must follow the current review and explicit confirmation", review.verdict == ReviewVerdict.SUPPORTED, publication.isSuccess)
                        val matched = sample.supported == (review.verdict == ReviewVerdict.SUPPORTED)
                        if (!matched) failures += sample.id
                        row.put("programmaticStatus", "PASS").put("semanticStatus", if (matched)
                            "AUTHORED_EXPECTATION_MATCHED_NOT_GENERAL_ACCURACY" else "AUTHORED_EXPECTATION_FAILED")
                    }
                } catch (failure: Throwable) {
                    failures += sample.id
                    row.put("programmaticStatus", "FAIL").put("failureType", failure.javaClass.simpleName)
                        .put("semanticStatus", "REQUIRES_INDEPENDENT_REVIEW")
                } finally {
                    row.put("paidRequests", sampleRequests).put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                    report.add(row); context.deleteDatabase(database)
                }
            }
            assertEquals("Every failure remains in ${report.directory.path}; no selective reruns", emptyList<String>(), failures)
            assertEquals(cases.size, requests)
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { directory.deleteRecursively() }
        }
    }

    private data class Sample(val id: String, val source: String, val target: String, val distinction: String, val supported: Boolean = false)
    private val cases = listOf(
        Sample("explicit-nonexecution", "这周三我只看了一节课，明确没有运行测试。我记不清周一和周二有没有练习。",
            "周三是否运行了测试没有任何记录，因此只能说执行未知。", "明确没有执行不等于缺少执行资料"),
        Sample("duration-not-delay", "我想下一轮每次练习十分钟，还没有尝试。没有说过要等十分钟才开始。",
            "我的下一轮计划是先等待十分钟再开始练习。", "持续时长不能变成等待间隔"),
        Sample("event-and-internal-count", "本周只有一次协作练习，期间返工三次。我把这同一次经历写了两份总结。",
            "我这周只返工一次，而且已通过两次独立练习验证。", "区分事件份数、记录份数和事件内次数"),
        Sample("comparison-needs-baseline", "今天完成一个熟悉的练习，可用时间为十二分钟。没有提供以前的熟悉度，也没有评价时间够不够。",
            "今天任务更熟悉、时间也更充足，因此完成得更好。", "没有比较基线不能升级为更熟悉或更充足"),
        Sample("cooccurrence-not-cause", "这次任务难度高、时间少，练习没有完成。原因还没查清，环境也发生了变化。",
            "这次练习因难度高和时间少而未完成。", "同时存在的条件不能确定为未完成的原因"),
        Sample("local-repeat-not-stability", "周一和周四两次不同练习，我都先搭最小例子，再查资料。只有这两次记录，之前没有记过。",
            "先搭最小例子已经是我多年稳定、适合各种情境的学习习惯。", "两个独立事件的局部重复不能证明长期稳定"),
        Sample("known-repeat-not-unknown", "本周有两次独立练习，两次起点都是先搭最小例子。无法知道未来是否继续，也不知道长期是否有效。",
            "这些记录完全没有说明两次练习是否采用了相同起点。", "承认已知局部重复，同时保留未来稳定性未知"),
        Sample("plan-and-review-one-event", "这是同一项活动：周一只拟定一个尚未开始的计划，周末回看确认这个计划仍未开始。没有其他活动记录。",
            "从周一和周末两次独立失败，可以确认我反复尝试后都没能开始。", "同一计划与回看不是两次独立失败"),
        Sample("bounded-conditions-control", "这次任务难度高、时间少，练习没有完成。原因还没查清，环境也发生了变化。",
            "这次自述练习没有完成，同时提到了任务难度高、时间少和环境变化；这些条件是否造成未完成仍未知。", "保留明确未完成及同现条件，不宣称因果", true),
        Sample("different-orders-conservative-tail", "周二我先独立写提纲，再开会讨论。周四我先开会确认范围，再写提纲；两次各完成一份文档。",
            "这两次都先独立写提纲再开会，但两次记录还不能证明这是一贯习惯或长期有效的方法。",
            "保守尾句不能抵消前半句对步骤顺序及方法同一性的错误陈述"),
        Sample("bounded-repeat-control", "本周有两次独立练习，两次起点都是先搭最小例子。无法知道未来是否继续，也不知道长期是否有效。",
            "按本周自述，两次不同练习都采用先搭最小例子的起点；这是本周两个事件的重复，尚不能证明长期稳定或有效。", "局部重复与长期稳定可以同时分别表达", true),
    )
}
