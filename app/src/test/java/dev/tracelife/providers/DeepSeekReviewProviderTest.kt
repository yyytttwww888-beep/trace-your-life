package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.ModelRequest
import dev.tracelife.analysis.ModelUsage
import dev.tracelife.analysis.ReviewIssue
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.analysis.TokenCountKind
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

/** Synthetic records, credentials and fabricated responses only; no real model requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekReviewProviderTest {
    private lateinit var directory: File
    private lateinit var credentials: DeepSeekCredentials
    private val supportingId = "a".repeat(64)
    private val contradictingId = "b".repeat(64)
    private val syntheticKey = "synthetic-review-key"
    private val request = ModelRequest(AnalysisRunCodec.json(mapOf(
        "target" to mapOf("text" to "合成提案：周一觉得睡够了；我每晚睡眠充足", "kind" to "REPORTED_EXPERIENCE"),
        "feedback" to mapOf("version" to 1, "decision" to "CONFIRM", "userText" to "合成反馈：我确认"),
        "evidence" to listOf(
            mapOf("id" to supportingId, "text" to "合成记录：周一我觉得睡够了。"),
            mapOf("id" to contradictingId, "text" to "合成记录：周二只有三小时，明显没睡够。"),
        ),
    )), "synthetic-review-request", 512)

    @Before fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        directory = File(context.noBackupFilesDir, "synthetic-review-provider-${UUID.randomUUID()}")
        val master = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        credentials = DeepSeekCredentials(directory, "dev.tracelife.synthetic") { master }
        credentials.configure(syntheticKey.toCharArray())
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun `review measures and sends its distinct schema prompt with only supplied user payload`() {
        var sentBody: String? = null
        var keyAfterRequest: CharArray? = null
        val provider = provider { body, key ->
            sentBody = body
            keyAfterRequest = key
            assertArrayEquals(syntheticKey.toCharArray(), key)
            DeepSeekHttpResponse(200, response())
        }
        val prepared = provider.prepareReview(request)
        val json = JSONObject(prepared.payloadJson)
        assertEquals("deepseek:deepseek-flash:json-v1:utf8-bound-v1:review-clauses-v4", provider.id)
        assertEquals(TokenCountKind.CONSERVATIVE_UTF8_BOUND, prepared.tokenCountKind)
        assertEquals(prepared.payloadJson.toByteArray(Charsets.UTF_8).size + 1_024, prepared.inputTokens)
        val messages = json.getJSONArray("messages")
        assertEquals(2, messages.length())
        val prompt = messages.getJSONObject(0).getString("content")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(prompt.contains("target/feedback非证据"))
        assertTrue(prompt.contains("anchors"))
        assertTrue(prompt.contains("本次evidence"))
        ReviewVerdict.entries.forEach { assertTrue(prompt.contains(it.name)) }
        ReviewIssue.entries.forEach { assertTrue(prompt.contains(it.name)) }
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals(request, prepared.original)
        val originalPayload = JSONObject(request.payloadJson)
        val wirePayload = JSONObject(messages.getJSONObject(1).getString("content"))
        val originalTarget = originalPayload.getJSONObject("target")
        val wireTarget = wirePayload.getJSONObject("target")
        assertFalse(wireTarget.has("text"))
        val parts = wireTarget.getJSONArray("parts")
        assertEquals(2, parts.length())
        assertEquals("p1", parts.getJSONObject(0).getString("partId"))
        assertEquals("p2", parts.getJSONObject(1).getString("partId"))
        assertEquals(originalTarget.getString("text"), (0 until parts.length()).joinToString("") { parts.getJSONObject(it).getString("text") })
        assertEquals(originalPayload.getJSONArray("evidence").toString(), wirePayload.getJSONArray("evidence").toString())
        assertEquals(originalPayload.getJSONObject("feedback").toString(), wirePayload.getJSONObject("feedback").toString())
        assertEquals(originalTarget.getString("kind"), wireTarget.getString("kind"))
        assertEquals("disabled", json.getJSONObject("thinking").getString("type"))
        assertEquals("json_object", json.getJSONObject("response_format").getString("type"))
        assertEquals(request.maximumOutputTokens, json.getInt("max_tokens"))
        assertFalse(json.getBoolean("stream"))
        assertFalse(json.has("tools"))
        assertFalse(prepared.payloadJson.contains(syntheticKey))
        assertNotEquals(provider.prepare(request).payloadJson, prepared.payloadJson)
        val result = provider.reviewPrepared(prepared)
        assertEquals(prepared.payloadJson, sentBody)
        assertTrue(keyAfterRequest!!.all { it == '\u0000' })
        assertEquals(ModelUsage(100, 30), result.usage)
        assertEquals(ReviewVerdict.CONTRADICTED, result.review.verdict)
        assertEquals(listOf(supportingId), result.review.supportingEvidenceIds)
        assertEquals(listOf(contradictingId), result.review.contradictingEvidenceIds)
        assertEquals(listOf(ReviewIssue.COUNTEREVIDENCE_OMISSION), result.review.issues)
    }

    @Test fun `cross purpose or altered prepared requests are rejected before sending`() {
        var sent = 0
        val provider = provider { _, _ -> sent++; DeepSeekHttpResponse(200, response()) }
        val prepared = provider.prepareReview(request)
        listOf(
            provider.prepare(request),
            prepared.copy(payloadJson = prepared.payloadJson + " "),
            prepared.copy(inputTokens = prepared.inputTokens - 1),
            prepared.copy(tokenCountKind = TokenCountKind.EXACT),
            prepared.copy(original = request.copy(maximumOutputTokens = 128)),
        ).forEach { altered -> assertThrows(IllegalArgumentException::class.java) { provider.reviewPrepared(altered) } }
        assertThrows(IllegalArgumentException::class.java) { provider.completePrepared(prepared) }
        assertEquals(0, sent)
        credentials.clear()
        val failure = assertThrows(DeepSeekException::class.java) { provider.reviewPrepared(prepared) }
        assertEquals(DeepSeekFailure.CONFIGURATION, failure.reason)
        assertEquals(0, sent)
    }

    @Test fun `measured provider wire separately checks mixed factual premises and cannot accept its supported tail`() {
        val target = "这次任务更熟悉、时间也充足，所以这个做法是否适用于复杂或不熟悉的任务，目前没有依据。"
        val source = "今天先跑一个小模块，再根据报错补接口说明，完成一次熟悉的练习。有40分钟空闲，不能确认是启动方式导致完成。"
        val envelope = mapOf("purpose" to "REVIEW", "target" to mapOf("text" to target, "kind" to "POSSIBLE_INTERPRETATION"),
            "evidence" to listOf(mapOf("id" to supportingId, "text" to source)),
            "feedback" to mapOf("userText" to "只有一次经历，当时任务更熟悉且时间充足。"))
        val mixed = ModelRequest(AnalysisRunCodec.json(envelope), "synthetic-comma-review", 8_000)
        val checks = (1..3).map { index -> mapOf(
            "partId" to "p$index", "statementType" to if (index == 1) "REPORTED_FACT" else "LIMITATION",
            "status" to if (index == 1) "UNSUPPORTED" else "SUPPORTED",
            "reason" to if (index == 1) "当前原文无更熟悉的基线或时间充足判断，历史意见不能移植。" else "适用性仍未确定，保留相邻片段的条件。",
            "anchors" to listOf(mapOf("evidenceId" to supportingId, "field" to "/text", "quote" to source)),
        ) }
        val content = AnalysisRunCodec.json(mapOf("verdict" to "SUPPORTED", "checks" to checks,
            "issues" to emptyList<String>(), "unknowns" to emptyList<String>()))
        var sent = ""
        val provider = provider { body, _ -> sent = body; DeepSeekHttpResponse(200, response(content)) }
        val prepared = provider.prepareReview(mixed)
        val wire = JSONObject(prepared.payloadJson)
        val measuredEnvelope = JSONObject(wire.getJSONArray("messages").getJSONObject(1).getString("content"))
        val parts = measuredEnvelope.getJSONObject("target").getJSONArray("parts")
        assertEquals(listOf("这次任务更熟悉、时间也充足，", "所以这个做法是否适用于复杂或不熟悉的任务，", "目前没有依据。"),
            (0 until parts.length()).map { parts.getJSONObject(it).getString("text") })
        assertEquals(JSONObject(mixed.payloadJson).getJSONArray("evidence").toString(), measuredEnvelope.getJSONArray("evidence").toString())
        assertEquals(JSONObject(mixed.payloadJson).getJSONObject("feedback").toString(), measuredEnvelope.getJSONObject("feedback").toString())
        assertEquals(mixed, prepared.original)
        assertEquals(prepared.payloadJson.toByteArray(Charsets.UTF_8).size + 1_024, prepared.inputTokens)
        assertEquals(8_000, wire.getInt("max_tokens"))
        val result = provider.reviewPrepared(prepared)
        assertEquals(prepared.payloadJson, sent)
        assertEquals(ReviewVerdict.UNSUPPORTED, result.review.verdict)
        assertTrue(result.review.unknowns.any { it.contains("更熟悉、时间也充足") })
    }

    @Test fun `all four review verdicts retain supporting evidence counterevidence and limits`() {
        val cases = mapOf(
            ReviewVerdict.SUPPORTED to reviewMap("SUPPORTED", listOf(supportingId), emptyList<Any>(), emptyList<Any>()),
            ReviewVerdict.UNSUPPORTED to reviewMap("UNSUPPORTED", emptyList<Any>(), emptyList<Any>(), listOf("USER_REPORT_AS_FACT")),
            ReviewVerdict.CONTRADICTED to reviewMap(),
            ReviewVerdict.INSUFFICIENT_CONTEXT to reviewMap("INSUFFICIENT_CONTEXT", emptyList<Any>(), emptyList<Any>(), listOf("INSUFFICIENT_CONTEXT")),
        )
        cases.forEach { (verdict, content) ->
            val provider = provider { _, _ -> DeepSeekHttpResponse(200, response(AnalysisRunCodec.json(content))) }
            val result = provider.reviewPrepared(provider.prepareReview(request)).review
            assertEquals(verdict, result.verdict)
            if (verdict == ReviewVerdict.SUPPORTED) assertTrue(result.unknowns.isEmpty())
            if (verdict != ReviewVerdict.SUPPORTED) assertTrue(result.issues.isNotEmpty())
        }
    }

    @Test fun `review schema rejects unknown fields wrong types invalid enums and duplicate keys`() {
        val valid = reviewMap()
        val malformed = listOf(
            valid + ("verdict" to "TRUE"),
            valid + ("verdict" to true),
            valid + ("issues" to listOf("UNKNOWN_ISSUE")),
            valid + ("issues" to listOf(7)),
            valid + ("issues" to listOf("OTHER", "OTHER")),
            valid + ("issues" to "OTHER"),
            valid + ("unknowns" to listOf(7)),
            valid + ("unknowns" to null),
            withFirstCheck(valid, "evidenceIds", listOf(7)),
            withFirstCheck(valid, "evidenceIds", supportingId),
            withFirstCheck(valid, "evidenceIds", listOf("invalid-hash")),
            withFirstCheck(valid, "status", "INSUFFICIENT_CONTEXT"),
            withFirstCheck(valid, "unexpected", true),
            valid + ("claims" to emptyList<Any>()),
            valid - "unknowns",
            mapOf("claims" to emptyList<Any>(), "unknowns" to emptyList<Any>()),
        ).map(AnalysisRunCodec::json) + listOf(
            "{\"verdict\":\"SUPPORTED\",\"verdict\":\"UNSUPPORTED\"}",
            AnalysisRunCodec.json(valid) + " trailing",
            "[]",
        )
        malformed.forEach { assertRejected(response(it)) }
    }

    @Test fun `review validates per check evidence uniqueness counts and prerequisites`() {
        val valid = reviewMap()
        val tooMany = List(33) { it.toString(16).padStart(64, '0') }
        listOf(
            withFirstCheck(valid, "evidenceIds", listOf(supportingId, supportingId)),
            withFirstCheck(valid, "evidenceIds", tooMany),
            withFirstCheck(valid, "evidenceIds", emptyList<String>()),
            withFirstCheck(valid, "evidenceIds", listOf("c".repeat(64))),
            reviewMap("SUPPORTED", emptyList<Any>(), emptyList<Any>(), emptyList<Any>()),
            reviewMap("CONTRADICTED", listOf(supportingId), emptyList<Any>()),
        ).forEach { assertRejected(response(AnalysisRunCodec.json(it))) }
    }

    @Test fun `overall support cannot bypass a counterexample or unresolved issue`() {
        listOf(
            reviewMap("SUPPORTED", listOf(supportingId), listOf(contradictingId), emptyList<Any>()),
            reviewMap("SUPPORTED", listOf(supportingId), emptyList<Any>(), listOf("CAUSAL_OVERREACH")),
        ).forEach { value ->
            val provider = provider { _, _ -> DeepSeekHttpResponse(200, response(AnalysisRunCodec.json(value))) }
            val actual = provider.reviewPrepared(provider.prepareReview(request)).review
            assertNotEquals(ReviewVerdict.SUPPORTED, actual.verdict)
            assertTrue(actual.issues.isNotEmpty())
        }
    }

    @Test fun `review limits are bounded and require meaningful nonblank strings`() {
        val valid = reviewMap()
        listOf(
            valid + ("unknowns" to listOf(mapOf("text" to " ", "blocksConclusion" to false))),
            valid + ("unknowns" to listOf(mapOf("text" to "x".repeat(501), "blocksConclusion" to false))),
            valid + ("unknowns" to List(13) { mapOf("text" to "synthetic gap", "blocksConclusion" to false) }),
            withFirstCheck(valid, "reason", "x".repeat(161)),
            withFirstCheck(valid, "partId", " "),
        ).forEach { assertRejected(response(AnalysisRunCodec.json(it))) }
        assertRejected(response(" ".repeat(65_537)))
    }

    @Test fun `truncated mismatched and malformed responses cannot become reviews`() {
        listOf(
            response(finish = "length"),
            response(finish = "content_filter"),
            response(model = DeepSeekModel.PRO.apiId),
            response(extras = mapOf("tool_calls" to listOf(mapOf("id" to "synthetic-tool")))),
            response(extras = mapOf("reasoning_content" to "synthetic reasoning")),
            response(extras = mapOf("content" to null)),
            response().toString(Charsets.UTF_8).replaceFirst("\"object\":", "\"object\":\"chat.completion\",\"object\":").toByteArray(),
            response() + byteArrayOf(0xc3.toByte(), 0x28),
        ).forEach(::assertRejected)
    }

    @Test fun `review usage is independently checked against its final wire and output reserve`() {
        val measured = provider { _, _ -> error("unexpected request") }.prepareReview(request).inputTokens
        listOf(
            mapOf("prompt_tokens" to measured + 1, "completion_tokens" to 30, "total_tokens" to measured + 31),
            mapOf("prompt_tokens" to 100, "completion_tokens" to 513, "total_tokens" to 613),
            mapOf("prompt_tokens" to 0, "completion_tokens" to 30, "total_tokens" to 30),
            mapOf("prompt_tokens" to 100, "completion_tokens" to 30, "total_tokens" to 131),
        ).forEach { usage -> assertRejected(response(usage = usage), DeepSeekFailure.USAGE) }
        assertRejected(response(usage = mapOf("prompt_tokens" to "100", "completion_tokens" to 30, "total_tokens" to 130)))
        assertRejected(response(usage = emptyMap()))
    }

    @Test fun `review HTTP failures retain sanitized categories without returning private body`() {
        mapOf(401 to DeepSeekFailure.AUTHENTICATION, 429 to DeepSeekFailure.RATE_LIMIT, 503 to DeepSeekFailure.SERVER).forEach { (status, reason) ->
            val provider = provider { _, _ -> DeepSeekHttpResponse(status, "synthetic private $syntheticKey".toByteArray()) }
            val failure = assertThrows(DeepSeekException::class.java) { provider.reviewPrepared(provider.prepareReview(request)) }
            assertEquals(reason, failure.reason)
            assertEquals("模型请求未完成：${reason.name}", failure.message)
            assertNull(failure.cause)
        }
    }

    private fun provider(transport: DeepSeekTransport) = DeepSeekProvider(credentials, DeepSeekModel.FLASH, transport)

    private fun assertRejected(body: ByteArray, reason: DeepSeekFailure = DeepSeekFailure.RESPONSE) {
        val provider = provider { _, _ -> DeepSeekHttpResponse(200, body) }
        val failure = assertThrows(DeepSeekException::class.java) { provider.reviewPrepared(provider.prepareReview(request)) }
        assertEquals(reason, failure.reason)
        assertNull(failure.cause)
    }

    private fun reviewMap(
        verdict: String = "CONTRADICTED",
        supporting: List<*> = listOf(supportingId),
        contradicting: List<*> = listOf(contradictingId),
        issues: List<*> = listOf("COUNTEREVIDENCE_OMISSION"),
        unknowns: List<*> = emptyList<Any>(),
    ): Map<String, Any?> {
        fun check(partId: String, status: String, ids: List<*>) = mapOf("partId" to partId, "statementType" to "REPORTED_FACT", "status" to status,
            "reason" to "合成局部核对依据", "anchors" to ids.map(::anchor))
        val checks = if (contradicting.isNotEmpty()) listOf(
            check("p1", if (supporting.isEmpty()) "UNSUPPORTED" else "SUPPORTED", supporting),
            check("p2", "CONTRADICTED", contradicting))
        else listOf("p1", "p2").map { partId -> check(partId, when {
            verdict == "CONTRADICTED" -> "CONTRADICTED"
            verdict == "SUPPORTED" || supporting.isNotEmpty() -> "SUPPORTED"
            else -> "UNSUPPORTED"
        }, if (verdict == "CONTRADICTED") contradicting else supporting) }
        return mapOf("verdict" to verdict, "checks" to checks, "issues" to issues,
            "unknowns" to unknowns.map { mapOf("partId" to "p1", "text" to it, "blocksConclusion" to true) })
    }

    private fun anchor(id: Any?) = mapOf("evidenceId" to id, "field" to "/text", "quote" to
        if (id == contradictingId) "合成记录：周二只有三小时，明显没睡够。" else "合成记录：周一我觉得睡够了。")

    @Suppress("UNCHECKED_CAST")
    private fun withFirstCheck(value: Map<String, Any?>, key: String, changed: Any?): Map<String, Any?> {
        val checks = value.getValue("checks") as List<Map<String, Any?>>
        val field = if (key == "evidenceIds") "anchors" else key
        val replacement = if (key == "evidenceIds" && changed is List<*>) changed.map(::anchor) else changed
        return value + ("checks" to listOf(checks.first() + (field to replacement)) + checks.drop(1))
    }

    private fun response(
        content: String = AnalysisRunCodec.json(reviewMap()),
        finish: String = "stop",
        model: String = DeepSeekModel.FLASH.apiId,
        extras: Map<String, Any?> = emptyMap(),
        usage: Map<String, Any?> = mapOf("prompt_tokens" to 100, "completion_tokens" to 30, "total_tokens" to 130),
    ): ByteArray = AnalysisRunCodec.json(mapOf(
        "object" to "chat.completion", "model" to model,
        "choices" to listOf(mapOf("index" to 0, "finish_reason" to finish,
            "message" to (mapOf("role" to "assistant", "content" to content) + extras))),
        "usage" to usage,
    )).toByteArray(Charsets.UTF_8)
}
