package dev.tracelife.quality

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.conversation.ConversationWorkflow
import dev.tracelife.knowledge.*
import dev.tracelife.providers.*
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.crypto.KeyGenerator

/** Frozen responses from synthetic use, replayed through production storage, workflow and wire.
 * The transport is local; this checks budget and preservation, not model semantic accuracy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekJourneyReductionBudgetTest {
    @Test fun `ordinary three month journey integrates its complete leaves inside the unchanged wire budget`() {
        replayJourney("/synthetic-life-journey-response-replay.json")
    }

    @Test fun `three month journey with longer real drafts still fits without dropping their meaning`() {
        replayJourney("/synthetic-life-journey-third-response-replay.json")
    }

    private fun replayJourney(resource: String) {
        val context: Context = RuntimeEnvironment.getApplication()
        val fixture = JSONObject(requireNotNull(javaClass.getResourceAsStream(resource))
            .bufferedReader().use { it.readText() })
        assertTrue(fixture.getBoolean("synthetic"))
        val frames = fixture.getJSONArray("frames")
        val clock = ReplayClock()
        val database = "synthetic-journey-budget-${UUID.randomUUID()}.db"
        val directory = File(context.noBackupFilesDir, database + "-key")
        val wrapping = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { wrapping }
        credentials.configure("synthetic-journey-budget-key".toCharArray())
        val sent = mutableListOf<String>()
        val reductions = mutableListOf<PreparedModelRequest>()
        val actual = DeepSeekProvider(credentials, DeepSeekModel.FLASH, DeepSeekTransport { body, _ ->
            val input = userEnvelope(body)
            val index = sent.size
            sent += body
            if (index < frames.length()) {
                val frame = frames.getJSONObject(index)
                assertEquals("Frozen production journey must keep the same calls", frame.getString("purpose"), input.getString("purpose"))
                val records = input.optJSONArray("evidence")?.let { entries -> (0 until entries.length()).map { entries.getJSONObject(it).getString("recordId") } }.orEmpty()
                assertEquals(frame.getJSONArray("recordIds").let { entries -> (0 until entries.length()).map(entries::getString) }, records)
                DeepSeekHttpResponse(200, frame.getJSONObject("response").toString().toByteArray(Charsets.UTF_8))
            } else {
                assertEquals(9, index)
                assertEquals("REDUCTION", input.getString("purpose"))
                val inputs = input.getJSONArray("derivedInputs")
                val content = AnalysisRunCodec.json(mapOf("claims" to listOf(mapOf(
                    "text" to "合成预算回放：保持完整派生输入，不作语义验收。", "kind" to "POSSIBLE_INTERPRETATION",
                    "evidenceIds" to (0 until inputs.length()).map { inputs.getJSONObject(it).getString("id") })), "unknowns" to emptyList<Any>()))
                DeepSeekHttpResponse(200, AnalysisRunCodec.json(mapOf("object" to "chat.completion", "model" to DeepSeekModel.FLASH.apiId,
                    "choices" to listOf(mapOf("index" to 0, "finish_reason" to "stop", "message" to mapOf("role" to "assistant", "content" to content))),
                    "usage" to mapOf("prompt_tokens" to 100, "completion_tokens" to 100, "total_tokens" to 200))).toByteArray(Charsets.UTF_8))
            }
        })
        val provider = object : AnalysisProvider by actual, SemanticNavigationProvider by actual {
            override val id = actual.id
            override val contextWindowTokens = actual.contextWindowTokens
            override fun prepare(request: ModelRequest) = actual.prepare(request).also {
                if (JSONObject(request.payloadJson).getString("purpose") == "REDUCTION") reductions += it
            }
        }
        try {
            LifeRepository(context, clock, database).use { repository ->
                val analysis = ControlledAnalysis(repository, clock)
                val knowledge = KnowledgeLibrary(repository, clock)
                val reflection = ReflectionWorkflow(repository, analysis, AnalysisFeedback(repository, clock), knowledge)
                val conversation = ConversationWorkflow(repository, analysis, reflection, clock)
                SyntheticLifeJourney.stages.forEachIndexed { index, stage ->
                    clock.at(stage.through)
                    SyntheticLifeJourney.seedUntil(repository, stage.through)
                    val turn = conversation.submit(conversation.prepare(stage.question, scopeOverride = stage.scope))
                    val pass = conversation.process(turn, provider, maxRequests = 5)
                    assertEquals("${stage.id}: measured reduction tokens=${reductions.lastOrNull()?.inputTokens}; wire bytes=${reductions.lastOrNull()?.payloadJson?.toByteArray()?.size}; dispatched=${sent.size}",
                        ReflectionStatus.READY, pass.status)
                    if (index < 2) {
                        val choice = fixture.getJSONObject("choices").getJSONObject(stage.id)
                        val finding = pass.run.findings[choice.getInt("findingIndex")]
                        assertEquals(choice.getString("textSha256"), AnalysisRunCodec.sha256(finding.text))
                        reflection.respond(turn.runId, finding.target, pass.run.taskVersion, ClaimFeedbackDecision.CONFIRMED)
                        val saved = reflection.reviewAndSave(turn.runId, finding.target, actual, actual, maxRequests = 8, topics = setOf("学习"))
                            .run.findings.single { it.target == finding.target }.knowledgeId
                        assertNotNull("Earlier real model results must remain saved background", saved)
                        val continuity = KnowledgeContinuity(repository, clock)
                        if (index == 0) continuity.create(requireNotNull(saved), 1, "学习方法如何随任务变化", setOf("学习"), choice.getString("reason"), "synthetic-learning-journey")
                        else continuity.append("synthetic-learning-journey", 1, requireNotNull(saved), 1, ExperienceRelation.SUPPLEMENT, choice.getString("reason"))
                    } else {
                        assertEquals(18, pass.run.coverage!!.records)
                        assertEquals(2, pass.run.synthesizedBatches)
                        assertEquals(2, pass.run.knowledgeContext!!.entries.size)
                        val state = analysis.task(turn.runId).state
                        assertEquals(32_000, state.request.limits.inputTokenLimit)
                        assertEquals(65_536, state.request.limits.maxInputBytes)
                        val reduction = reductions.single()
                        assertTrue(reduction.inputTokens <= 32_000)
                        assertEquals(reduction.payloadJson.toByteArray(Charsets.UTF_8).size + 1_024, reduction.inputTokens)
                        assertEquals(8_000, JSONObject(reduction.payloadJson).getInt("max_tokens"))
                        assertEquals(AnalysisRunCodec.sha256(reduction.payloadJson), state.calls.last().requestSha256)
                        assertSemanticReductionAndLocalProofs(reduction, state)
                        assertEquals(reduction, actual.prepare(reduction.original))
                        assertEquals(state, AnalysisRunCodec.decode(AnalysisRunCodec.encode(state)))
                        assertEquals(10, sent.size)
                    }
                }
            }
        } finally { credentials.clear(); directory.deleteRecursively(); context.deleteDatabase(database) }
    }

    private fun userEnvelope(body: String): JSONObject = JSONObject(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))

    private fun assertSemanticReductionAndLocalProofs(prepared: PreparedModelRequest, state: AnalysisRunState) {
        val original = JSONObject(prepared.original.payloadJson)
        val wire = userEnvelope(prepared.payloadJson)
        original.keys().asSequence().filter { it !in setOf("system", "derivedInputs") }.forEach { key ->
            assertEquals("The question, scope, coverage and earlier understanding must remain intact: $key", jsonValue(original.get(key)), jsonValue(wire.get(key)))
        }
        val inputs = wire.getJSONArray("derivedInputs")
        var facts = 0
        var sources = 0
        repeat(inputs.length()) { index ->
            val input = inputs.getJSONObject(index)
            val constraints = input.getJSONArray("derivedFactConstraints")
            repeat(constraints.length()) { constraintIndex ->
                val inherited = constraints.getJSONObject(constraintIndex)
                assertFalse("Inherited quotes never become newly read originals", inherited.getBoolean("originalEvidence"))
                val pack = inherited.getJSONObject("sourceFacts")
                val leaf = state.calls.single { it.batchKey == inherited.getJSONObject("node").getString("batchKey") }
                val localPack = original.getJSONArray("derivedInputs").getJSONObject(index)
                    .getJSONArray("derivedFactConstraints").getJSONObject(constraintIndex).getJSONObject("sourceFacts")
                val localFacts = requireNotNull(leaf.sourceFacts)
                assertEquals("The original request keeps the complete immutable ledger hash", localFacts.sha256, localPack.getString("sourcePackSha256"))
                assertFalse("Local pack hashes do not consume the model's context", pack.has("sourcePackSha256"))
                val localSources = localPack.getJSONArray("sources")
                repeat(localSources.length()) { sourceIndex ->
                    val source = localSources.getJSONObject(sourceIndex)
                    val ledgerSource = localFacts.sources.single { it.evidenceId == source.getString("id") && it.field == source.getString("field") }
                    assertEquals(ledgerSource.sha256, source.getString("sha256"))
                    assertEquals(ledgerSource.textSha256, source.getString("textSha256"))
                }
                facts += localPack.getJSONArray("facts").length()
                sources += localPack.getJSONArray("sources").length()
            }
            DeepSeekWireProjectionAssertions.assertJsonEquals(
                DeepSeekWireProjectionAssertions.semanticInput(original.getJSONArray("derivedInputs").getJSONObject(index)),
                DeepSeekWireProjectionAssertions.restoreInput(input))
        }
        assertEquals("All 13 sources retain identity, date, field and span", 13, sources)
        assertEquals("All 31 original literal relations remain available across the two leaves", 31, facts)
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
        is org.json.JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }

    private class ReplayClock : Clock() {
        private var now = Instant.parse("2026-07-22T12:00:00Z")
        override fun getZone(): ZoneId = ZoneId.of(SyntheticLifeJourney.ZONE)
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
        fun at(day: LocalDate) { now = day.atTime(20, 0).atZone(zone).toInstant() }
    }
}
