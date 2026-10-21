package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.ModelRequest
import dev.tracelife.conversation.ConversationWorkflow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Frozen synthetic input from actual use. Preparation only; no response or network is simulated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekFollowupBudgetTest {
    @Test fun `followup after saved three month understanding fits while every semantic value is reconstructable`() {
        val fixture = fixture()
        assertTrue(fixture.getBoolean("synthetic"))
        assertEquals(34_580, fixture.getJSONObject("metricsBefore").getInt("budgetInputTokens"))
        val original = fixture.getJSONObject("original")
        val context: Context = RuntimeEnvironment.getApplication()
        val provider = DeepSeekProvider(DeepSeekCredentials(context), DeepSeekModel.FLASH,
            DeepSeekTransport { _, _ -> error("Preparation must not dispatch") })
        val request = ModelRequest(original.toString(), "synthetic-followup-budget", 8_000)
        val prepared = provider.prepare(request)
        assertEquals(request, prepared.original)
        assertEquals(prepared, provider.prepare(request))
        assertTrue("The actual followup must fit the unchanged budget: ${prepared.inputTokens}", prepared.inputTokens <= 32_000)
        assertEquals(prepared.payloadJson.toByteArray(Charsets.UTF_8).size + 1_024, prepared.inputTokens)

        val actualBody = JSONObject(prepared.payloadJson)
        val beforeBody = fixture.getJSONObject("wireBefore")
        val actual = JSONObject(actualBody.getJSONArray("messages").getJSONObject(1).getString("content"))
        val expected = JSONObject(beforeBody.getJSONArray("messages").getJSONObject(1).getString("content"))
        val suffix = "\n" + ConversationWorkflow.ANSWER_GUIDANCE
        assertEquals(expected.getString("question"), actual.getString("question") + suffix)
        assertEquals(expected.getJSONObject("conversationContext").getString("previousQuestion"),
            actual.getJSONObject("conversationContext").getString("previousQuestion") + suffix)
        expected.put("question", expected.getString("question").removeSuffix(suffix))
        expected.getJSONObject("conversationContext").let { it.put("previousQuestion", it.getString("previousQuestion").removeSuffix(suffix)) }
        val system = actualBody.getJSONArray("messages").getJSONObject(0).getString("content")
        assertEquals(1, system.windowed(ConversationWorkflow.ANSWER_GUIDANCE.length).count { it == ConversationWorkflow.ANSWER_GUIDANCE })
        val inputs = actual.getJSONArray("derivedInputs")
        assertEquals(2, inputs.length())
        repeat(inputs.length()) { index ->
            val source = original.getJSONArray("derivedInputs").getJSONObject(index)
            val sent = inputs.getJSONObject(index)
            assertEquals("Model output still references the complete derived input identity", source.getString("id"), sent.getString("id"))
            assertEquals(if (index == 0) 12 else 2, sent.getJSONArray("sourceIds").length())
            expected.getJSONArray("derivedInputs").put(index, DeepSeekWireProjectionAssertions.semanticInput(source))
            inputs.put(index, DeepSeekWireProjectionAssertions.restoreInput(sent))
        }
        // Full object equality includes every draft sentence, legacy unknown, source/date/span,
        // relation/modality, selected answer, earlier understanding/note and its distinct role.
        DeepSeekWireProjectionAssertions.assertJsonEquals(expected, actual)
        actualBody.remove("messages"); beforeBody.remove("messages")
        DeepSeekWireProjectionAssertions.assertJsonEquals(beforeBody, actualBody)
        DeepSeekWireProjectionAssertions.assertJsonEquals(original, JSONObject(prepared.original.payloadJson))
    }

    @Test fun `only two exact program question suffixes are shared and matching source text remains literal`() {
        val input = fixture().getJSONObject("original")
        val guidance = ConversationWorkflow.ANSWER_GUIDANCE
        val selected = input.getJSONObject("conversationContext").getJSONArray("selectedAnswers").getJSONObject(0)
        selected.put("text", guidance)
        val context: Context = RuntimeEnvironment.getApplication()
        val provider = DeepSeekProvider(DeepSeekCredentials(context))
        val exact = provider.prepare(ModelRequest(input.toString(), "synthetic-exact-question-suffix", 8_000))
        val sent = JSONObject(JSONObject(exact.payloadJson).getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(guidance, sent.getJSONObject("conversationContext").getJSONArray("selectedAnswers").getJSONObject(0).getString("text"))
        input.put("question", input.getString("question") + "（保留这句原话）")
        val different = provider.prepare(ModelRequest(input.toString(), "synthetic-different-question-suffix", 8_000))
        val unchanged = JSONObject(JSONObject(different.payloadJson).getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(input.getString("question"), unchanged.getString("question"))
        assertEquals(input.getJSONObject("conversationContext").getString("previousQuestion"),
            unchanged.getJSONObject("conversationContext").getString("previousQuestion"))
    }

    private fun fixture() = JSONObject(requireNotNull(javaClass.getResourceAsStream("/synthetic-life-journey-followup-reduction-request.json"))
        .bufferedReader().use { it.readText() })
}
