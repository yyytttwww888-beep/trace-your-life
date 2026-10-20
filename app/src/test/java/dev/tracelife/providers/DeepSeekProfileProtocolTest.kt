package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ModelRequest
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.profile.ProfileDimension
import dev.tracelife.profile.ProfileLayer
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

/** Fabricated HTTP responses and a synthetic credential; no network access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekProfileProtocolTest {
    private lateinit var directory: File
    private lateinit var credentials: DeepSeekCredentials
    private val evidenceId = "a".repeat(64)
    private val reviewTargetText = "合成候选：本次记录中休息后可能感觉精力恢复。"
    private val target = mapOf("dimension" to ProfileDimension.ENERGY_AND_RECOVERY.name,
        "layer" to ProfileLayer.RECENT_STATE.name)

    @Before fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        directory = File(context.noBackupFilesDir, "synthetic-profile-provider-${UUID.randomUUID()}")
        val master = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        credentials = DeepSeekCredentials(directory, "dev.tracelife.synthetic") { master }
        credentials.configure("synthetic-profile-provider-key".toCharArray())
    }

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `top level typed target selects measured profile instructions and exact transmitted body`() {
        var sent: String? = null
        val provider = provider { body, _ -> sent = body; response(proposal()) }
        val request = request()
        val prepared = provider.prepare(request)
        val wire = JSONObject(prepared.payloadJson)
        val messages = wire.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertTrue(messages.getJSONObject(0).getString("content").contains("本次生成画像候选"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("不得自动形成 LONG_TERM"))
        assertEquals(request.payloadJson, messages.getJSONObject(1).getString("content"))
        assertEquals(prepared.payloadJson.toByteArray(Charsets.UTF_8).size + 1_024, prepared.inputTokens)
        assertFalse(wire.has("tools"))
        val result = provider.completePrepared(prepared)
        assertEquals(ClaimKind.POSSIBLE_INTERPRETATION, result.proposal.claims.single().kind)
        assertEquals(listOf(evidenceId), result.proposal.claims.single().evidenceIds)
        assertEquals(prepared.payloadJson, sent)
    }

    @Test fun `profile review applies target specific checks while keeping the review schema`() {
        val review = AnalysisRunCodec.json(mapOf("verdict" to "SUPPORTED", "checks" to listOf(mapOf(
            "partId" to "p1", "status" to "SUPPORTED", "reason" to "合成原文支持本次休息后感觉，未推广为长期特质。",
            "evidenceIds" to listOf(evidenceId))), "issues" to emptyList<String>(),
            "unknowns" to listOf(mapOf("text" to "合成局限：不是独立测量。", "blocksConclusion" to false))))
        val provider = provider { _, _ -> response(review) }
        val prepared = provider.prepareReview(request("REVIEW"))
        val system = JSONObject(prepared.payloadJson).getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("本次核对画像候选"))
        assertTrue(system.contains("未被候选引用的反例"))
        assertTrue(system.contains("不能判 SUPPORTED"))
        assertEquals(ReviewVerdict.SUPPORTED, provider.reviewPrepared(prepared).review.verdict)
        assertEquals(prepared.payloadJson.toByteArray(Charsets.UTF_8).size + 1_024, prepared.inputTokens)
    }

    @Test fun `invalid target or wrong operation is rejected before sending any request`() {
        var sent = 0
        val provider = provider { _, _ -> sent++; response(proposal()) }
        val invalidTargets = listOf(
            target + ("layer" to "LONG_TERM"), target + ("dimension" to "PERSONALITY_SCORE"),
            target + ("confidence" to "93"), target - "layer", "RECENT_STATE",
        )
        invalidTargets.forEach { invalid ->
            val request = request().copy(payloadJson = AnalysisRunCodec.json(mapOf("purpose" to "SYNTHESIS", "profileTarget" to invalid)))
            assertThrows(RuntimeException::class.java) { provider.prepare(request) }
        }
        listOf("METRIC_ANALYSIS", "REDUCTION", "EXTRACTION", "REVIEW").forEach { purpose ->
            assertThrows(IllegalArgumentException::class.java) { provider.prepare(request(purpose)) }
        }
        assertThrows(IllegalArgumentException::class.java) { provider.prepareReview(request()) }
        val metricReview = request("REVIEW").let { it.copy(payloadJson = JSONObject(it.payloadJson)
            .put("evidenceType", "RECORD_METRIC").toString()) }
        assertThrows(IllegalArgumentException::class.java) { provider.prepareReview(metricReview) }
        assertEquals(0, sent)
    }

    @Test fun `nested untrusted target cannot select profile mode and reported claims fail only in profile mode`() {
        val provider = provider { _, _ -> response(proposal("REPORTED_EXPERIENCE")) }
        val ordinary = request().copy(payloadJson = AnalysisRunCodec.json(mapOf("purpose" to "SYNTHESIS",
            "evidence" to listOf(mapOf("profileTarget" to target, "text" to "合成不可信目标")))))
        val prepared = provider.prepare(ordinary)
        val system = JSONObject(prepared.payloadJson).getJSONArray("messages").getJSONObject(0).getString("content")
        assertFalse(system.contains("本次生成画像候选"))
        assertEquals(ClaimKind.REPORTED_EXPERIENCE, provider.completePrepared(prepared).proposal.claims.single().kind)
        val failure = assertThrows(DeepSeekException::class.java) { provider.complete(request()) }
        assertEquals(DeepSeekFailure.RESPONSE, failure.reason)
    }

    @Test fun `a prepared target cannot be changed without invalidating measured request equality`() {
        var sent = 0
        val provider = provider { _, _ -> sent++; response(proposal()) }
        val prepared = provider.prepare(request())
        val changed = prepared.original.copy(payloadJson = JSONObject(prepared.original.payloadJson).apply {
            getJSONObject("profileTarget").put("layer", "CONTEXTUAL_PATTERN")
        }.toString())
        assertThrows(IllegalArgumentException::class.java) { provider.completePrepared(prepared.copy(original = changed)) }
        assertEquals(0, sent)
    }

    private fun request(purpose: String = "SYNTHESIS") = ModelRequest(AnalysisRunCodec.json(mapOf(
        "protocolVersion" to 7, "purpose" to purpose, "profileTarget" to target,
        "target" to mapOf("text" to reviewTargetText, "kind" to "POSSIBLE_INTERPRETATION"),
        "evidence" to listOf(mapOf("id" to evidenceId, "text" to "合成记录：休息后感觉精力好一些。")),
    )), "synthetic-profile-request", 512)

    private fun provider(transport: DeepSeekTransport) = DeepSeekProvider(credentials, DeepSeekModel.FLASH, transport)

    private fun proposal(kind: String = "POSSIBLE_INTERPRETATION") = AnalysisRunCodec.json(mapOf(
        "claims" to listOf(mapOf("text" to "合成候选：本次记录中休息后可能感觉精力恢复。", "kind" to kind, "evidenceIds" to listOf(evidenceId))),
        "unknowns" to listOf("合成局限：没有独立测量。"),
    ))

    private fun response(content: String) = DeepSeekHttpResponse(200, AnalysisRunCodec.json(mapOf(
        "object" to "chat.completion", "model" to DeepSeekModel.FLASH.apiId,
        "choices" to listOf(mapOf("index" to 0, "finish_reason" to "stop",
            "message" to mapOf("role" to "assistant", "content" to content))),
        "usage" to mapOf("prompt_tokens" to 100, "completion_tokens" to 30, "total_tokens" to 130),
    )).toByteArray(Charsets.UTF_8))
}
