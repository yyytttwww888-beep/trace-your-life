package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.profile.*
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

/** Fabricated HTTP bodies only: no credentials or network from the user. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekMaintenanceProtocolTest {
    private lateinit var directory: File
    private lateinit var credentials: DeepSeekCredentials
    private val evidenceId = "a".repeat(64)
    private val target = mapOf("dimension" to ProfileDimension.ABILITIES_AND_STRATEGIES.name, "layer" to ProfileLayer.CONTEXTUAL_PATTERN.name)
    @Before fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        directory = File(context.noBackupFilesDir, "synthetic-maintenance-provider-${UUID.randomUUID()}")
        val master = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        credentials = DeepSeekCredentials(directory, "dev.tracelife.synthetic") { master }
        credentials.configure("synthetic-maintenance-provider-key".toCharArray())
    }
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun `relation dispatch measures exact body and returns strict nonauthoritative enum suggestion`() {
        var sent: String? = null
        val provider = provider { body, _ -> sent = body; response(relation()) }
        val request = request("PROFILE_RELATION")
        val prepared = provider.prepareRelation(request)
        val messages = JSONObject(prepared.payloadJson).getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").contains("不是本次原始证据"))
        assertEquals(request.payloadJson, messages.getJSONObject(1).getString("content"))
        assertEquals(prepared.payloadJson.toByteArray(Charsets.UTF_8).size + 1_024, prepared.inputTokens)
        val result = provider.relatePrepared(prepared)
        assertEquals(ProfileRelation.SAME, result.result.relation)
        assertEquals(ProfileMaintenanceRecommendation.KEEP, result.result.recommendation)
        assertEquals(prepared.payloadJson, sent)
    }

    @Test fun `profile scan uses candidate instructions and keeps typed receipt classifications`() {
        val content = AnalysisRunCodec.json(mapOf("findings" to listOf(mapOf("receiptId" to evidenceId,
            "classification" to "COUNTEREVIDENCE", "reason" to "合成同情境反例", "issues" to emptyList<String>())), "unknowns" to listOf("合成范围限制")))
        val provider = provider { _, _ -> response(content) }
        val prepared = provider.prepareScan(request("PROFILE_SCAN"))
        val system = JSONObject(prepared.payloadJson).getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("针对 candidate"))
        assertTrue(system.contains("缺上下文用 NEEDS_CONTEXT"))
        assertEquals(ReductionRelevance.COUNTEREVIDENCE, provider.scanPrepared(prepared).result.findings.single().classification)
    }

    @Test fun `maintenance review adds all constraint instructions while ordinary profile review stays separate`() {
        val provider = provider { _, _ -> error("must not dispatch") }
        val ordinary = provider.prepareReview(request("REVIEW"))
        val maintenance = provider.prepareReview(request("REVIEW", maintenance = true))
        fun system(value: PreparedModelRequest) = JSONObject(value.payloadJson).getJSONArray("messages").getJSONObject(0).getString("content")
        assertFalse(system(ordinary).contains("这是画像维护最终核对"))
        assertTrue(system(maintenance).contains("这是画像维护最终核对"))
        assertTrue(system(maintenance).contains("不能把新措辞当作新身份"))
        assertTrue(maintenance.inputTokens > ordinary.inputTokens)
    }

    @Test fun `wrong purpose missing fingerprint forged preparation and untyped relation outputs are rejected`() {
        var sent = 0
        var content = relation()
        val provider = provider { _, _ -> sent++; response(content) }
        assertThrows(IllegalArgumentException::class.java) { provider.prepareRelation(request("SYNTHESIS")) }
        val missing = request("PROFILE_SCAN").let { it.copy(payloadJson = JSONObject(it.payloadJson).apply { remove("maintenancePlanSha256") }.toString()) }
        assertThrows(IllegalArgumentException::class.java) { provider.prepareScan(missing) }
        val prepared = provider.prepareRelation(request("PROFILE_RELATION"))
        assertThrows(IllegalArgumentException::class.java) { provider.relatePrepared(prepared.copy(payloadJson = prepared.payloadJson + " ")) }
        assertEquals(0, sent)
        listOf(
            JSONObject(relation()).put("relation", "AUTO_ADOPTED").toString(),
            JSONObject(relation()).put("confidence", 1).toString(),
            JSONObject(relation()).put("relation", "UNCERTAIN").put("recommendation", "REVIEW").toString(),
            JSONObject(relation()).put("recommendation", "REPLACE").toString(),
        ).forEach { invalid ->
            content = invalid
            val failure = assertThrows(DeepSeekException::class.java) { provider.relatePrepared(prepared) }
            assertEquals(DeepSeekFailure.RESPONSE, failure.reason)
        }
        assertEquals(4, sent)
    }

    private fun request(purpose: String, maintenance: Boolean = false) = ModelRequest(AnalysisRunCodec.json(mapOf(
        "protocolVersion" to 8, "purpose" to purpose, "profileTarget" to target,
        "maintenancePlanSha256" to "b".repeat(64), "profileMaintenance" to maintenance,
        "candidate" to mapOf("text" to "合成候选"), "evidence" to listOf(mapOf("id" to evidenceId, "text" to "合成原文")),
    )), "synthetic-operation", 2_000)
    private fun relation() = AnalysisRunCodec.json(mapOf("relation" to "SAME", "recommendation" to "KEEP", "reason" to "合成同义描述", "unknowns" to emptyList<String>()))
    private fun provider(handler: (String, CharArray) -> DeepSeekHttpResponse) = DeepSeekProvider(credentials, DeepSeekModel.FLASH, DeepSeekTransport(handler))
    private fun response(content: String) = DeepSeekHttpResponse(200, AnalysisRunCodec.json(mapOf(
        "object" to "chat.completion", "model" to DeepSeekModel.FLASH.apiId,
        "choices" to listOf(mapOf("index" to 0, "finish_reason" to "stop", "message" to mapOf("role" to "assistant", "content" to content))),
        "usage" to mapOf("prompt_tokens" to 10, "completion_tokens" to 10, "total_tokens" to 20),
    )).toByteArray(Charsets.UTF_8))
}
