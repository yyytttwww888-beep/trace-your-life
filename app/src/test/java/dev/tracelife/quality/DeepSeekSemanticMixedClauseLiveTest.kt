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

/** Natural mixed-clause regressions frozen from archived failures: three errors and three controls.
 * A scripted proposal fixes each target; the production review and publication gates are real.
 * One transport dispatch per case, at most six for the entire group, and no selective retries.
 * Gold expectations never enter the model request. This does not evaluate free generation or
 * certify all returned reasons/unknowns: every response remains available for independent review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekSemanticMixedClauseLiveTest {
    private val qualityModel = DeepSeekModel.valueOf(System.getProperty("traceLife.qualityModel") ?: "FLASH")

    @Test fun `mixed clauses preserve historical scope duration and attribution through real publication gates`() {
        assumeTrue("Mixed-clause semantic evaluation requires explicit quality opt-in",
            System.getProperty("traceLife.deepSeek.quality") == "true")
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514)
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val keyDirectory = File(context.noBackupFilesDir, "synthetic-semantic-mixed-clause-key-${UUID.randomUUID()}")
        val wrapping = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(keyDirectory, context.packageName) { wrapping }
        val report = SyntheticQualityReport("deepseek-semantic-mixed-clause", FIXTURE_VERSION)
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
                val database = "synthetic-semantic-mixed-clause-${sample.id}-${UUID.randomUUID()}.db"
                val started = System.nanoTime()
                var sampleRequests = 0
                val row = sampleJson(sample).put("fixtureKind", "mixed_clause_authored_target_review_v1")
                    .put("fixtureSha256", fixtureHash).put("syntheticUserConfirmation", true)
                    .put("model", qualityModel.apiId).put("maximumPaidRequests", 1)
                    .put("maximumGroupPaidRequests", MAXIMUM_REQUESTS).put("generationTested", false)
                    .put("unknownsReview", "REQUIRES_INDEPENDENT_REVIEW")
                    .put("publicationAttempted", false).put("published", false)
                try {
                    LifeRepository(context, SyntheticQualityCases.clock, database).use { repository ->
                        val analysis = ControlledAnalysis(repository, SyntheticQualityCases.clock)
                        if (sample.seedCase != null) SyntheticQualityCases.seed(repository, analysis, sample.seedCase)
                        else sample.records.forEach { source ->
                            repository.createRecord(RecordDraft(source.body,
                                Occurrence(LocalDate.parse(source.date), zoneId = "Asia/Shanghai"), setOf("学习")), source.id)
                        }
                        val task = analysis.start(AnalysisRequest("核对这条限定范围的描述；用户确认不表示描述正确。",
                            AnalysisQuery(LocalDate.parse(sample.from), LocalDate.parse(sample.through), "Asia/Shanghai"),
                            limits = AnalysisLimits(maxCalls = 2), focusTopics = setOf("学习"),
                            inheritHistoricalFeedback = sample.seedCase?.prior != null))
                        row.put("configuredLimits", JSONObject().put("maxCalls", task.state.request.limits.maxCalls)
                            .put("inputTokenLimit", task.state.request.limits.inputTokenLimit)
                            .put("outputTokenReserve", task.state.request.limits.outputTokenReserve))
                        analysis.synthesize(task.stored.id, "authored-target", sample.records.map {
                            EvidenceSelection(it.id, "body")
                        }, object : AnalysisProvider {
                            override val id = "synthetic-mixed-clause-authored-target-v1"
                            override val contextWindowTokens = 100_000
                            override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
                            override fun complete(request: ModelRequest): BatchProposal {
                                val evidence = JSONObject(request.payloadJson).getJSONArray("evidence")
                                val ids = (0 until evidence.length()).map { evidence.getJSONObject(it).getString("id") }
                                return BatchProposal(listOf(ProposedClaim(sample.target, ids, ClaimKind.POSSIBLE_INTERPRETATION)))
                            }
                        })
                        val claim = analysis.claimRef(task.stored.id, "authored-target", 0)
                        val history = analysis.task(task.stored.id).state.feedbackContext?.references.orEmpty()
                        assertEquals(if (sample.seedCase?.prior != null) 1 else 0, history.size)
                        history.forEach { opinion ->
                            analysis.resolveHistoricalFeedback(task.stored.id, claim, opinion.id,
                                analysis.task(task.stored.id).stored.version, HistoricalFeedbackChoice.RESPECTED,
                                "合成门禁验收：模拟人工完成对照；这不是对陈述正确性的证明。")
                        }
                        row.put("historicalOpinions", history.size)
                            .put("syntheticHistoryResolution", if (history.isEmpty()) "NOT_APPLICABLE"
                                else "RESPECTED_FOR_GATE_EXERCISE_NOT_SEMANTIC_PROOF")
                        AnalysisFeedback(repository, SyntheticQualityCases.clock).respond(task.stored.id, claim,
                            analysis.task(task.stored.id).stored.version, ClaimFeedbackDecision.CONFIRMED)
                        val actual = HttpsDeepSeekTransport()
                        val transport = DeepSeekTransport { body, secret ->
                            check(sampleRequests == 0 && requests < MAXIMUM_REQUESTS) { "Mixed-clause request ceiling reached" }
                            File(report.directory, "${sample.id}-request.json").writeText(body)
                            sample.seedCase?.prior?.let { prior ->
                                val messages = JSONObject(body).getJSONArray("messages")
                                val envelope = (0 until messages.length()).map { messages.getJSONObject(it) }
                                    .single { it.getString("role") == "user" }.getString("content").let(::JSONObject)
                                val feedback = envelope.getJSONObject("historicalFeedback")
                                assertFalse("Historical correction remains control background", feedback.getBoolean("originalEvidence"))
                                val entries = feedback.getJSONArray("entries")
                                assertEquals(1, entries.length())
                                assertEquals(prior.correction, entries.getJSONObject(0).getString("userText"))
                            }
                            sampleRequests++; requests++
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
                            task.stored.id, claim, "real-review", KnowledgeCategory.INSIGHT, analysis.task(task.stored.id).stored.version,
                            topics = setOf("学习")) }
                        val review = reviewed.getOrNull()
                        row.withReviewDiagnostics(review).put("publicationAttempted", true).put("published", publication.isSuccess)
                            .put("publicationFailureType", publication.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                            .put("reviewFailureType", reviewed.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                            .put("calls", JSONArray(analysis.task(task.stored.id).state.calls.map { call -> JSONObject().withCallDiagnostics(call)
                                .put("purpose", call.purpose.name).put("status", call.status.name)
                                .put("requestSha256", call.requestSha256).put("providerId", call.providerId)
                                .put("inputBytes", call.inputBytes).put("inputTokens", call.inputTokens)
                                .put("historicalFeedbackReadIds", JSONArray(call.historicalFeedbackReadIds))
                                .put("failureCode", call.failureCode ?: JSONObject.NULL)
                                .put("promptTokens", call.usage?.promptTokens ?: JSONObject.NULL)
                                .put("completionTokens", call.usage?.completionTokens ?: JSONObject.NULL) }))
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
        .put("from", sample.from).put("through", sample.through).put("zoneId", "Asia/Shanghai")
        .put("source", sample.records.joinToString("\n") { "${it.date}：${it.body}" })
        .put("sources", JSONArray(sample.records.map { JSONObject().put("id", it.id)
            .put("date", it.date).put("body", it.body) }))
        .put("priorFeedback", sample.seedCase?.prior?.let { prior -> JSONObject()
            .put("sourceId", prior.source.id).put("sourceDate", prior.source.date).put("sourceBody", prior.source.body)
            .put("oldClaim", prior.oldClaim).put("correction", prior.correction) } ?: JSONObject.NULL)
        .put("inheritHistoricalFeedback", sample.seedCase?.prior != null)
        .put("target", sample.target).put("expectedSupported", ReviewVerdict.SUPPORTED in sample.expectedVerdicts)
        .put("expectedVerdicts", JSONArray(sample.expectedVerdicts.map { it.name }.sorted()))
        .put("requiredDistinctions", JSONArray(listOf(sample.distinction)))
        .put("targetOrigin", sample.origin).put("generationTested", false)

    private data class Source(val id: String, val date: String, val body: String)
    private data class Sample(val id: String, val records: List<Source>, val from: String, val through: String,
        val target: String, val expectedVerdicts: Set<ReviewVerdict>, val distinction: String,
        val origin: String, val seedCase: SyntheticQualityCases.Case? = null)

    private val supported = setOf(ReviewVerdict.SUPPORTED)
    private val contradicted = setOf(ReviewVerdict.CONTRADICTED)
    private val missingBasis = setOf(ReviewVerdict.UNSUPPORTED, ReviewVerdict.INSUFFICIENT_CONTEXT)
    private val history = SyntheticQualityCases.all.single { it.id == "historical-correction" }
    private val plan = SyntheticQualityCases.all.single { it.id == "plan-execution" }
    private fun sources(case: SyntheticQualityCases.Case) = case.sources.map { Source(it.id, it.date, it.body) }
    private val profile = listOf(
        Source("synthetic-integrated-0", "2026-08-05", "合成测试资料，不是真实个人经历：练习A：我先读教程，再把目标缩成一个小例子，最后做出了结果。当天练习简单，时间充足，不能归因于某一种方法。"),
        Source("synthetic-integrated-2", "2026-09-06", "合成测试资料，不是真实个人经历：练习C：我开始先写一个可运行的小例子，再按报错查资料，完成了练习。时间和难度与练习A不同；只是一条新经历。"),
        Source("synthetic-integrated-1", "2026-08-19", "合成测试资料，不是真实个人经历：练习B：继续先读教程，但没有开始动手。当天被工作打断。这个记录不能证明我总是拖延。"),
    )
    // Verbatim target from round 2 profile response-1, not rewritten into an easier isolated assertion.
    private val profileTarget = "跨期候选（待验证解释，非稳定人格或方法有效性的结论）：八月记录中，2026-08-05练习A自述先读教程、把目标缩成小例子并做出结果，并明确当天练习简单、时间充足，不能归因于某一种方法；2026-08-19练习B自述继续先读教程但未开始动手，当天被工作打断，记录者明确说这不能证明总是拖延。九月记录中，2026-09-06练习C自述改为先写一个可运行的小例子、再按报错查资料并完成练习，记录者指出其时间和难度与练习A不同，且只是一条新经历。据此可提出可修订假设：练习的起步方式可能从八月以先读教程为主，转向九月以先动手写小例子、再按报错查资料为主；但八月已有一次先读教程后动手的完成记录，九月仅一条记录，且记录者本人把差异归因于时间和难度不同，因此不能据此认定方法已稳定改变或新方法必然更有效。"
    private val cases = listOf(
        Sample("historical-conditions-with-conservative-tail", sources(history), history.from, history.through,
            "这次任务更熟悉、时间也充足，所以这个做法是否适用于复杂或不熟悉的任务，目前没有依据。",
            missingBasis, "谨慎结尾不抵消本次条件增强；旧纠正的更熟悉和充足不能移植到新事件。",
            "deepseek-fixed-cases-4cddb9e5-8e67-421d-b3bc-a98bf9afc1de/historical-correction-response-2.json#/claims/2/text", history),
        Sample("historical-conditions-bounded-control", sources(history), history.from, history.through,
            "这次是熟悉的练习、有40分钟空闲；记录没有说明比以往更熟悉或时间是否充足，也不能据此确认做法适用于复杂或不熟悉的任务。",
            supported, "承认当前熟悉和40分钟，保留比较与适用性未知，旧纠正仍在背景中。",
            "AUTHORED_CORRECT_CONTROL_OF_historical-conditions-with-conservative-tail", history),
        Sample("duration-with-conservative-tail", sources(plan), plan.from, plan.through,
            "想改成下周五分钟，但到10月18日还没试这个新安排；所以五分钟后练习只是设想，不是已尝试或已验证有效的方法。",
            contradicted, "五分钟是练习时长，不是等待间隔；未试与效果未知不抵消前面的角色错误。",
            "deepseek-fixed-cases-4cddb9e5-8e67-421d-b3bc-a98bf9afc1de/plan-execution-response-2.json#/claims/2/text", plan),
        Sample("duration-bounded-control", sources(plan), plan.from, plan.through,
            "想把下周的练习时长改成五分钟，但到10月18日还没试这个新安排；它仍是练习五分钟的设想，不是已尝试或已验证有效的方法。",
            supported, "保留五分钟练习的持续时长、下一周与截至记录时尚未尝试三个边界。",
            "AUTHORED_CORRECT_CONTROL_OF_duration-with-conservative-tail", plan),
        Sample("user-attribution-with-conservative-tail", profile, "2026-08-05", "2026-09-06", profileTarget,
            missingBasis, "条件差异或排除方法归因不能变成用户已认定另一原因；整条谨慎模态不能豁免嵌入的归属断言。",
            "deepseek-integrated-profile-f2b8140a-d674-42f4-96e3-03289f28ec18/response-1.json#/claims/0/text"),
        Sample("user-attribution-bounded-control", profile, "2026-08-05", "2026-09-06",
            profileTarget.replace("且记录者本人把差异归因于时间和难度不同", "且练习C只说明时间和难度与练习A不同，没有说明这些差异就是结果的原因"),
            supported, "保留方法起步变化、单次与跨情境限制，只将条件差异说成待核对背景，不伪造用户归因。",
            "AUTHORED_CORRECT_CONTROL_OF_user-attribution-with-conservative-tail"),
    )

    companion object {
        private const val FIXTURE_VERSION = "semantic-mixed-clause-v1"
        private const val MAXIMUM_REQUESTS = 6
    }
}
