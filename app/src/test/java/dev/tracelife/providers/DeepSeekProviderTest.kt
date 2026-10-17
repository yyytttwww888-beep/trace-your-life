package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ModelRequest
import dev.tracelife.analysis.ModelUsage
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

/** Only synthetic keys and fabricated HTTP responses; no request leaves the test process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekProviderTest {
    private lateinit var directory: File
    private lateinit var credentials: DeepSeekCredentials
    private val evidenceId = "a".repeat(64)
    private val syntheticKey = "synthetic-provider-key"
    private val request = ModelRequest("{\"evidence\":[{\"text\":\"合成记录：今天练习了十分钟。\"}]}", "synthetic-request", 256)

    @Before fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        directory = File(context.noBackupFilesDir, "synthetic-provider-${UUID.randomUUID()}")
        val master = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        credentials = DeepSeekCredentials(directory, "dev.tracelife.synthetic") { master }
        credentials.configure(syntheticKey.toCharArray())
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun `prepared wire body is the measured and transmitted body without credentials or hidden inputs`() {
        var sentBody: String? = null
        var escapedKey: CharArray? = null
        val provider = provider { body, key ->
            sentBody = body
            escapedKey = key
            assertArrayEquals(syntheticKey.toCharArray(), key)
            DeepSeekHttpResponse(200, response())
        }
        val prepared = provider.prepare(request)
        assertEquals(TokenCountKind.CONSERVATIVE_UTF8_BOUND, prepared.tokenCountKind)
        assertEquals(prepared.payloadJson.toByteArray(Charsets.UTF_8).size + 1_024, prepared.inputTokens)
        assertEquals(prepared.inputTokens, provider.countInputTokens(request))
        assertFalse(prepared.payloadJson.contains(syntheticKey))
        val json = JSONObject(prepared.payloadJson)
        assertEquals(DeepSeekModel.FLASH.apiId, json.getString("model"))
        assertEquals("disabled", json.getJSONObject("thinking").getString("type"))
        assertEquals("json_object", json.getJSONObject("response_format").getString("type"))
        assertEquals(request.maximumOutputTokens, json.getInt("max_tokens"))
        assertFalse(json.getBoolean("stream"))
        assertEquals(0, json.getInt("temperature"))
        assertFalse(json.has("tools"))
        val messages = json.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals(request.payloadJson, messages.getJSONObject(1).getString("content"))
        val result = provider.completePrepared(prepared)
        assertEquals(prepared.payloadJson, sentBody)
        assertTrue(escapedKey!!.all { it == '\u0000' })
        assertEquals(ModelUsage(100, 30), result.usage)
        assertEquals("合成记录中的练习体验", result.proposal.claims.single().text)
        assertEquals(listOf(evidenceId), result.proposal.claims.single().evidenceIds)
        assertEquals(ClaimKind.REPORTED_EXPERIENCE, result.proposal.claims.single().kind)
        assertEquals(listOf("合成缺口：没有后续表现记录"), result.proposal.unknowns)
    }

    @Test fun `changed measured requests fail before transport and missing key does not make a request`() {
        var calls = 0
        val provider = provider { _, _ -> calls++; DeepSeekHttpResponse(200, response()) }
        val prepared = provider.prepare(request)
        listOf(
            prepared.copy(payloadJson = prepared.payloadJson + " "),
            prepared.copy(inputTokens = prepared.inputTokens - 1),
            prepared.copy(tokenCountKind = TokenCountKind.EXACT),
            prepared.copy(original = request.copy(maximumOutputTokens = 100)),
        ).forEach { changed -> assertThrows(IllegalArgumentException::class.java) { provider.completePrepared(changed) } }
        assertEquals(0, calls)
        credentials.clear()
        val missing = assertThrows(DeepSeekException::class.java) { provider.completePrepared(prepared) }
        assertEquals(DeepSeekFailure.CONFIGURATION, missing.reason)
        assertEquals(0, calls)
    }

    @Test fun `HTTP errors expose only a stable failure category`() {
        mapOf(401 to DeepSeekFailure.AUTHENTICATION, 403 to DeepSeekFailure.AUTHENTICATION,
            402 to DeepSeekFailure.BALANCE, 429 to DeepSeekFailure.RATE_LIMIT, 500 to DeepSeekFailure.SERVER,
            503 to DeepSeekFailure.SERVER, 302 to DeepSeekFailure.REDIRECT, 400 to DeepSeekFailure.REQUEST).forEach { (status, reason) ->
            val provider = provider { _, _ -> DeepSeekHttpResponse(status, "synthetic private error $syntheticKey".toByteArray()) }
            val failure = assertThrows(DeepSeekException::class.java) { provider.complete(request) }
            assertEquals(reason, failure.reason)
            assertEquals("模型请求未完成：${reason.name}", failure.message)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains(syntheticKey))
        }
    }

    @Test fun `malformed duplicate trailing and invalid UTF8 responses fail closed`() {
        val valid = response().toString(Charsets.UTF_8)
        val cases = listOf(
            "{broken".toByteArray(),
            valid.replaceFirst("\"object\":", "\"object\":\"chat.completion\",\"object\":").toByteArray(),
            (valid + " {}").toByteArray(),
            valid.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28),
            ByteArray(262_145) { ' '.code.toByte() },
            response(content = "{\"claims\":[],\"claims\":[],\"unknowns\":[]}"),
            response(content = "{\"claims\":[],\"unknowns\":[]} trailing"),
        )
        cases.forEach { assertResponseRejected(it) }
    }

    @Test fun `truncated or unexpected model messages cannot be treated as a successful proposal`() {
        listOf(
            response(finish = "length"),
            response(finish = "content_filter"),
            response(model = DeepSeekModel.PRO.apiId),
            response(messageExtras = mapOf("reasoning_content" to "synthetic hidden reasoning")),
            response(messageExtras = mapOf("tool_calls" to listOf(mapOf("id" to "synthetic-tool")))),
            response(messageExtras = mapOf("role" to "user")),
            response(messageExtras = mapOf("content" to null)),
            response(choiceIndex = 1),
            response(choicesCount = 0),
            response(choicesCount = 2),
        ).forEach { assertResponseRejected(it) }
    }

    @Test fun `proposal schema requires bounded claims typed kinds and unique evidence IDs`() {
        val validClaim = claim()
        listOf(
            proposal(claims = listOf(claim(kind = "PERSONALITY"))),
            proposal(claims = listOf(claim(evidence = emptyList<String>()))),
            proposal(claims = listOf(claim(evidence = listOf(evidenceId, evidenceId)))),
            proposal(claims = listOf(claim(evidence = listOf("not-an-evidence-hash")))),
            proposal(claims = listOf(claim(evidence = listOf(7)))),
            proposal(claims = listOf(claim(text = " "))),
            proposal(claims = listOf(claim(text = "x".repeat(2_001)))),
            proposal(claims = List(21) { validClaim }),
            proposal(claims = listOf(validClaim + ("untrusted" to true))),
            proposal(unknowns = listOf(7)),
            proposal(unknowns = listOf(" ")),
            proposal(unknowns = List(21) { "synthetic gap" }),
            AnalysisRunCodec.json(mapOf("claims" to emptyList<Any>(), "unknowns" to emptyList<Any>(), "extra" to true)),
        ).forEach { assertResponseRejected(response(content = it)) }
        val empty = provider { _, _ -> DeepSeekHttpResponse(200, response(content = proposal(claims = emptyList<Any>()))) }.complete(request)
        assertTrue(empty.claims.isEmpty())
        val possible = provider { _, _ ->
            DeepSeekHttpResponse(200, response(content = proposal(claims = listOf(claim(kind = "POSSIBLE_INTERPRETATION")))) )
        }.complete(request)
        assertEquals(ClaimKind.POSSIBLE_INTERPRETATION, possible.claims.single().kind)
    }

    @Test fun `reported usage must fit measured input output reserve and sum`() {
        val measured = provider { _, _ -> error("no request") }.prepare(request).inputTokens
        listOf(
            mapOf("prompt_tokens" to measured + 1, "completion_tokens" to 30, "total_tokens" to measured + 31),
            mapOf("prompt_tokens" to 100, "completion_tokens" to 257, "total_tokens" to 357),
            mapOf("prompt_tokens" to 0, "completion_tokens" to 30, "total_tokens" to 30),
            mapOf("prompt_tokens" to 100, "completion_tokens" to 30, "total_tokens" to 131),
        ).forEach { usage ->
            val failure = assertThrows(DeepSeekException::class.java) {
                provider { _, _ -> DeepSeekHttpResponse(200, response(usage = usage)) }.complete(request)
            }
            assertEquals(DeepSeekFailure.USAGE, failure.reason)
        }
        listOf(
            mapOf("prompt_tokens" to Long.MAX_VALUE, "completion_tokens" to 30, "total_tokens" to 130),
            mapOf("prompt_tokens" to "100", "completion_tokens" to 30, "total_tokens" to 130),
            mapOf("prompt_tokens" to -1, "completion_tokens" to 30, "total_tokens" to 29),
            mapOf("prompt_tokens" to 100.0, "completion_tokens" to 30, "total_tokens" to 130),
        ).forEach { assertResponseRejected(response(usage = it)) }
    }

    private fun provider(transport: DeepSeekTransport) = DeepSeekProvider(credentials, DeepSeekModel.FLASH, transport)

    private fun assertResponseRejected(body: ByteArray) {
        val failure = assertThrows(DeepSeekException::class.java) {
            provider { _, _ -> DeepSeekHttpResponse(200, body) }.complete(request)
        }
        assertEquals(DeepSeekFailure.RESPONSE, failure.reason)
        assertNull(failure.cause)
    }

    private fun claim(text: String = "合成记录中的练习体验", evidence: List<*> = listOf(evidenceId), kind: String = "REPORTED_EXPERIENCE") =
        mapOf("text" to text, "evidenceIds" to evidence, "kind" to kind)

    private fun proposal(claims: List<*> = listOf(claim()), unknowns: List<*> = listOf("合成缺口：没有后续表现记录")) =
        AnalysisRunCodec.json(mapOf("claims" to claims, "unknowns" to unknowns))

    private fun response(
        content: String = proposal(),
        finish: String = "stop",
        model: String = DeepSeekModel.FLASH.apiId,
        messageExtras: Map<String, Any?> = emptyMap(),
        choiceIndex: Int = 0,
        choicesCount: Int = 1,
        usage: Map<String, Any?> = mapOf("prompt_tokens" to 100, "completion_tokens" to 30, "total_tokens" to 130),
    ): ByteArray = AnalysisRunCodec.json(mapOf(
        "object" to "chat.completion", "model" to model,
        "choices" to List(choicesCount) { mapOf("index" to choiceIndex, "finish_reason" to finish,
            "message" to (mapOf("role" to "assistant", "content" to content) + messageExtras)) },
        "usage" to usage,
    )).toByteArray(Charsets.UTF_8)
}
