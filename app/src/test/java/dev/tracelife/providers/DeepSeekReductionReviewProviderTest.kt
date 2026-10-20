package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

/** Fabricated API responses and explicit synthetic credentials; no network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekReductionReviewProviderTest {
    private lateinit var credentials: DeepSeekCredentials
    private lateinit var directory: File
    private val receipt = "a".repeat(64)
    private val request = ModelRequest(AnalysisRunCodec.json(mapOf(
        "purpose" to "REDUCTION_SCAN", "target" to mapOf("text" to "合成陈述：周一记录了练习", "kind" to "POSSIBLE_INTERPRETATION"),
        "evidence" to listOf(mapOf("id" to receipt, "text" to "合成周一：完成一个练习")),
    )), "synthetic-period-scan", 1_000)

    @Before fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        directory = File(context.noBackupFilesDir, "synthetic-period-review-provider-${UUID.randomUUID()}")
        val master = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        credentials = DeepSeekCredentials(directory, "dev.tracelife.synthetic") { master }
        credentials.configure("synthetic-period-review-key".toCharArray())
    }
    @After fun teardown() { directory.deleteRecursively() }

    @Test fun `scan sends measured independent prompt and preserves each relevance class`() {
        ReductionRelevance.entries.forEach { relevance ->
            var sent = ""
            val provider = provider(AnalysisRunCodec.json(scan(relevance.name))) { sent = it }
            val prepared = provider.prepareScan(request)
            val json = JSONObject(prepared.payloadJson)
            val prompt = json.getJSONArray("messages").getJSONObject(0).getString("content")
            ReductionRelevance.entries.forEach { assertTrue(prompt.contains(it.name)) }
            assertTrue(prompt.contains("恰好一次"))
            assertTrue(prompt.contains("不能归为 IRRELEVANT"))
            assertEquals(request.payloadJson, json.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals(prepared.payloadJson.toByteArray(Charsets.UTF_8).size + 1_024, prepared.inputTokens)
            val result = provider.scanPrepared(prepared)
            assertEquals(prepared.payloadJson, sent)
            assertEquals(relevance, result.result.findings.single().classification)
            assertEquals(receipt, result.result.findings.single().receiptId)
            assertEquals(ModelUsage(100, 30), result.usage)
        }
    }

    @Test fun `scan rejects malformed duplicate incomplete or oversized structures`() {
        val finding = (scan().getValue("findings") as List<*>).single() as Map<*, *>
        val malformed = listOf(
            scan() + ("verdict" to "SUPPORTED"),
            scan() - "unknowns",
            scan() + ("findings" to emptyList<Any>()),
            scan() + ("findings" to listOf(finding, finding)),
            scan() + ("findings" to listOf(finding + ("classification" to "SUPPORTED"))),
            scan() + ("findings" to listOf(finding + ("receiptId" to "derived-input"))),
            scan() + ("findings" to listOf(finding + ("reason" to "x".repeat(501)))),
            scan() + ("findings" to listOf(finding + ("issues" to listOf("OTHER", "OTHER")))),
            scan() + ("unknowns" to listOf("x".repeat(1_001))),
            scan() + ("unknowns" to List(21) { "合成缺口" }),
        ).map(AnalysisRunCodec::json) + listOf("{\"findings\":[],\"findings\":[],\"unknowns\":[]}")
        malformed.forEach { content ->
            val provider = provider(content)
            val failure = assertThrows(DeepSeekException::class.java) { provider.scanPrepared(provider.prepareScan(request)) }
            assertEquals(DeepSeekFailure.RESPONSE, failure.reason)
        }
    }

    @Test fun `cross purpose and altered scan requests cannot cross network seam`() {
        var sent = 0
        val provider = provider(AnalysisRunCodec.json(scan())) { sent++ }
        val prepared = provider.prepareScan(request)
        listOf(provider.prepare(request), provider.prepareReview(request), prepared.copy(inputTokens = 1),
            prepared.copy(payloadJson = prepared.payloadJson + " ")).forEach { altered ->
            assertThrows(IllegalArgumentException::class.java) { provider.scanPrepared(altered) }
        }
        assertThrows(IllegalArgumentException::class.java) { provider.reviewPrepared(prepared) }
        assertEquals(0, sent)
    }

    @Test fun `final review has independent original rereading instructions and standard verdict validation`() {
        val content = AnalysisRunCodec.json(mapOf("verdict" to "SUPPORTED", "checks" to listOf(mapOf(
            "partId" to "p1", "statementType" to "REPORTED_FACT", "status" to "SUPPORTED", "reason" to "合成周一原文记载完成一个练习。",
            "anchors" to listOf(mapOf("evidenceId" to receipt, "field" to "/text", "quote" to "周一")))), "issues" to emptyList<Any>(),
            "unknowns" to emptyList<String>()))
        val provider = provider(content)
        val final = request.copy(payloadJson = request.payloadJson.replace("REDUCTION_SCAN", "REDUCTION_REVIEW"))
        val prepared = provider.prepareReview(final)
        val prompt = JSONObject(prepared.payloadJson).getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(prompt.contains("最终独立核对"))
        assertTrue(prompt.contains("全部未标无关"))
        assertTrue(prompt.contains("扫描意见不是证据"))
        assertNotEquals(provider.prepareReview(request).payloadJson, prepared.payloadJson)
        assertEquals(ReviewVerdict.SUPPORTED, provider.reviewPrepared(prepared).review.verdict)
    }

    @Test fun `mixed action scan sends both source types through the measured request`() {
        val actionId = "b".repeat(64)
        val envelope = JSONObject(request.payloadJson).put("actionPeriod", true).put("actionEvidence",
            org.json.JSONArray().put(JSONObject().put("id", actionId).put("kind", "USER_REVIEW")
                .put("execution", "UNKNOWN").put("observedText", "合成回看：执行情况未知")))
        val mixed = request.copy(payloadJson = envelope.toString())
        val result = mapOf("findings" to listOf(
            mapOf("receiptId" to receipt, "classification" to "SUPPORT", "reason" to "合成记录提供支持", "issues" to emptyList<String>()),
            mapOf("receiptId" to actionId, "classification" to "CONTEXT", "reason" to "合成回看保留未知", "issues" to emptyList<String>()),
        ), "unknowns" to listOf("合成资料，未知不表示失败"))
        var sent = ""
        val provider = provider(AnalysisRunCodec.json(result)) { sent = it }
        val prepared = provider.prepareScan(mixed)
        val prompt = systemPrompt(prepared)
        assertTrue(prompt.contains("evidence 和 actionEvidence"))
        assertTrue(prompt.contains("两者合计1至32项"))
        assertTrue(prompt.contains("UNKNOWN 和未回看不等于失败"))
        assertTrue(prompt.contains("reviewedPlan"))
        assertTrue(prompt.contains("nextPlan"))
        assertEquals(setOf(receipt, actionId), provider.scanPrepared(prepared).result.findings.map { it.receiptId }.toSet())
        assertEquals(prepared.payloadJson, sent)
        assertEquals(mixed.payloadJson, JSONObject(sent).getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test fun `record scan retains its legacy prompt while final review pins the explicit v4 wire identity`() {
        // Scans keep their prompt. Review v4 is a new provider identity, never a retry of earlier parts.
        val provider = provider("{}")
        assertTrue(provider.id.endsWith(":review-clauses-v4"))
        assertEquals("2dd9d2a987a729f9de844cd63da48be85a5f7ccb8c6f5079de44966335468a6f",
            AnalysisRunCodec.sha256(systemPrompt(provider.prepareScan(request))))
        val final = request.copy(payloadJson = request.payloadJson.replace("REDUCTION_SCAN", "REDUCTION_REVIEW"))
        assertTrue(systemPrompt(provider.prepareReview(final)).contains("anchors"))
    }

    @Test fun `action final review keeps scan obligations even when all action originals were irrelevant`() {
        val provider = provider("{}")
        listOf(emptyList<Map<String, String>>(), listOf(mapOf("id" to "b".repeat(64), "kind" to "USER_REVIEW"))).forEach { actions ->
            val final = ModelRequest(AnalysisRunCodec.json(mapOf("purpose" to "REDUCTION_REVIEW", "actionPeriod" to true,
                "target" to mapOf("text" to "合成行动陈述：执行情况仍未知。", "kind" to "POSSIBLE_INTERPRETATION"),
                "actionEvidence" to actions, "evidence" to listOf(mapOf("id" to receipt, "text" to "合成原文")))),
                "synthetic-action-final", 1_000)
            val prompt = systemPrompt(provider.prepareReview(final))
            assertTrue(prompt.contains("最终独立核对"))
            assertTrue(prompt.contains("扫描意见不是证据"))
            assertTrue(prompt.contains("不能把多个局部 SUPPORT 相加"))
            assertTrue(prompt.contains("计划不等于执行"))
            assertTrue(prompt.contains("UNKNOWN 和未回看不等于失败"))
            assertTrue(prompt.contains("旧计划"))
        }
    }

    @Test fun `action reduction only cites derived inputs and retains action distinctions`() {
        val provider = provider("{}")
        val reduction = ModelRequest(AnalysisRunCodec.json(mapOf("purpose" to "REDUCTION", "actionPeriod" to true,
            "derivedInputs" to emptyList<Any>())), "synthetic-action-reduction", 1_000)
        val prompt = systemPrompt(provider.prepare(reduction))
        assertTrue(prompt.contains("本次没有提供原文"))
        assertTrue(prompt.contains("引用仅用本次派生输入id"))
        assertTrue(prompt.contains("不能计为独立实验"))
        assertTrue(prompt.contains("不能用新计划解释旧结果"))
        val malformed = reduction.copy(payloadJson = reduction.payloadJson.replace("\"actionPeriod\":true", "\"actionPeriod\":\"true\""))
        assertThrows(IllegalArgumentException::class.java) { provider.prepare(malformed) }
    }

    private fun systemPrompt(prepared: PreparedModelRequest) =
        JSONObject(prepared.payloadJson).getJSONArray("messages").getJSONObject(0).getString("content")

    private fun scan(classification: String = "SUPPORT"): Map<String, Any> = mapOf(
        "findings" to listOf(mapOf("receiptId" to receipt, "classification" to classification,
            "reason" to "合成片段提到周一练习", "issues" to emptyList<Any>())), "unknowns" to listOf("合成自述，非独立测量"))
    private fun provider(content: String, sent: (String) -> Unit = {}): DeepSeekProvider =
        DeepSeekProvider(credentials, DeepSeekModel.FLASH, DeepSeekTransport { body, _ ->
            sent(body)
            DeepSeekHttpResponse(200, AnalysisRunCodec.json(mapOf("object" to "chat.completion", "model" to "deepseek-flash",
                "choices" to listOf(mapOf("index" to 0, "finish_reason" to "stop", "message" to mapOf("role" to "assistant", "content" to content))),
                "usage" to mapOf("prompt_tokens" to 100, "completion_tokens" to 30, "total_tokens" to 130))).toByteArray(Charsets.UTF_8))
        })
}
