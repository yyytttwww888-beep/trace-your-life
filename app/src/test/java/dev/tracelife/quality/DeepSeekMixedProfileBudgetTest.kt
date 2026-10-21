package dev.tracelife.quality

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.providers.*
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate
import java.util.UUID
import javax.crypto.KeyGenerator

/** Real request preparation and parser, synthetic transport only. This tests the request's
 * budget and lossless representation, not whether a fabricated review judges the target correctly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekMixedProfileBudgetTest {
    private lateinit var context: Context
    private lateinit var credentials: DeepSeekCredentials
    private lateinit var directory: File

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        directory = File(context.noBackupFilesDir, "synthetic-mixed-profile-budget-${UUID.randomUUID()}")
        val wrapping = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        credentials = DeepSeekCredentials(directory, context.packageName) { wrapping }
        credentials.configure("synthetic-mixed-profile-budget-key".toCharArray())
    }

    @After fun cleanup() { credentials.clear(); directory.deleteRecursively() }

    @Test fun `archived attribution error fits unchanged review budget with every source relation`() =
        exercise("user-attribution-with-conservative-tail")

    @Test fun `bounded attribution control fits unchanged review budget with every source relation`() =
        exercise("user-attribution-bounded-control")

    private fun exercise(id: String) {
        val sample = frozenSample(id)
        val database = "synthetic-mixed-profile-budget-${UUID.randomUUID()}.db"
        val sent = mutableListOf<String>()
        var prepared: PreparedModelRequest? = null
        val actual = DeepSeekProvider(credentials, DeepSeekModel.FLASH, DeepSeekTransport { body, _ ->
            sent += body
            val input = userEnvelope(body)
            val parts = input.getJSONObject("target").getJSONArray("parts")
            val checks = (0 until parts.length()).map { index -> mapOf(
                "partId" to parts.getJSONObject(index).getString("partId"), "statementType" to "MIXED",
                "anchors" to emptyList<Any>(), "reason" to "合成预算占位，不作语义验收。", "status" to "UNSUPPORTED") }
            val content = AnalysisRunCodec.json(mapOf("checks" to checks, "issues" to listOf("OTHER"),
                "unknowns" to emptyList<Any>(), "verdict" to "UNSUPPORTED"))
            DeepSeekHttpResponse(200, AnalysisRunCodec.json(mapOf(
                "object" to "chat.completion", "model" to DeepSeekModel.FLASH.apiId,
                "choices" to listOf(mapOf("index" to 0, "message" to mapOf("role" to "assistant", "content" to content), "finish_reason" to "stop")),
                "usage" to mapOf("prompt_tokens" to 100, "completion_tokens" to 100, "total_tokens" to 200),
            )).toByteArray(Charsets.UTF_8))
        })
        val reviewer = object : SemanticReviewProvider {
            override val id = actual.id
            override val contextWindowTokens = actual.contextWindowTokens
            override fun prepareReview(request: ModelRequest) = actual.prepareReview(request).also { prepared = it }
            override fun reviewPrepared(request: PreparedModelRequest) = actual.reviewPrepared(request)
        }
        try {
            LifeRepository(context, SyntheticQualityCases.clock, database).use { repository ->
                val analysis = ControlledAnalysis(repository, SyntheticQualityCases.clock)
                sample.sources.forEach { source -> repository.createRecord(RecordDraft(source.body,
                    Occurrence(LocalDate.parse(source.date), zoneId = "Asia/Shanghai"), setOf("学习")), source.id) }
                val task = analysis.start(AnalysisRequest("核对这条限定范围的描述；用户确认不表示描述正确。",
                    AnalysisQuery(LocalDate.parse(sample.from), LocalDate.parse(sample.through), "Asia/Shanghai"),
                    limits = AnalysisLimits(inputTokenLimit = 12_000, outputTokenReserve = 8_000, maxCalls = 2),
                    focusTopics = setOf("学习")))
                analysis.synthesize(task.stored.id, "authored-target", sample.sources.map { EvidenceSelection(it.id, "body") },
                    object : AnalysisProvider {
                        override val id = "synthetic-mixed-clause-authored-target-v1"
                        override val contextWindowTokens = 100_000
                        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray(Charsets.UTF_8).size
                        override fun complete(request: ModelRequest): BatchProposal {
                            val evidence = JSONObject(request.payloadJson).getJSONArray("evidence")
                            return BatchProposal(listOf(ProposedClaim(sample.target,
                                (0 until evidence.length()).map { evidence.getJSONObject(it).getString("id") }, ClaimKind.POSSIBLE_INTERPRETATION)))
                        }
                    })
                val target = analysis.claimRef(task.stored.id, "authored-target", 0)
                AnalysisFeedback(repository, SyntheticQualityCases.clock).respond(task.stored.id, target,
                    analysis.task(task.stored.id).stored.version, ClaimFeedbackDecision.CONFIRMED)
                try { analysis.reviewClaim(task.stored.id, "real-wire-review", target, provider = reviewer) }
                catch (failure: Exception) {
                    throw AssertionError("$id review failed: measured inputTokens=${prepared?.inputTokens}, limit=12000, output=8000, dispatches=${sent.size}", failure)
                }
                assertEquals("$id measured inputTokens=${prepared?.inputTokens}", 1, sent.size)
                val state = analysis.task(task.stored.id).state
                assertEquals(2, state.calls.size)
                assertTrue(state.calls.all { it.status == CallStatus.SUCCEEDED })
                val review = state.calls.last()
                val body = sent.single()
                assertEquals(body, requireNotNull(prepared).payloadJson)
                assertEquals(body.toByteArray(Charsets.UTF_8).size, review.inputBytes)
                assertEquals(review.inputBytes + 1_024, review.inputTokens)
                assertTrue("$id measured inputTokens=${review.inputTokens}, limit=12000", review.inputTokens <= 12_000)
                assertEquals(TokenCountKind.CONSERVATIVE_UTF8_BOUND, review.tokenCountKind)
                assertEquals(AnalysisRunCodec.sha256(body), review.requestSha256)
                assertEquals(8_000, JSONObject(body).getInt("max_tokens"))
                assertEquals(2, state.request.limits.maxCalls)
                assertLossless(sample, userEnvelope(body), requireNotNull(review.sourceFacts))
            }
        } finally { context.deleteDatabase(database) }
    }

    private fun assertLossless(sample: Sample, input: JSONObject, pack: EvidenceFactPack) {
        val parts = input.getJSONObject("target").getJSONArray("parts")
        assertEquals(sample.target, (0 until parts.length()).joinToString("") { parts.getJSONObject(it).getString("text") })
        val evidence = input.getJSONArray("evidence")
        val rows = (0 until evidence.length()).map { evidence.getJSONObject(it) }
        assertEquals(sample.sources.map { it.id }.toSet(), rows.map { it.getString("recordId") }.toSet())
        sample.sources.forEach { source ->
            val row = rows.single { it.getString("recordId") == source.id }
            assertEquals(source.body, row.getString("text"))
            assertEquals(source.date, row.getString("startDate"))
            assertEquals(source.date, row.getString("endDate"))
            assertEquals(0, row.getInt("start"))
            assertEquals(source.body.codePointCount(0, source.body.length), row.getInt("end"))
        }
        assertTrue("The fixed samples carry order and condition relations", pack.facts.isNotEmpty())
        val compact = input.getJSONObject("sourceFacts")
        val columns = compact.getJSONArray("factColumns")
        assertEquals(listOf("source", "kind", "start", "end", "value", "related", "mode"),
            (0 until columns.length()).map(columns::getString))
        val sources = compact.getJSONArray("sources")
        assertEquals(pack.sources.size, sources.length())
        pack.sources.forEachIndexed { index, source ->
            val original = evidence.getJSONObject(sources.getJSONObject(index).getInt("evidence"))
            assertEquals(source.evidenceId, original.getString("id"))
            assertEquals(source.field, original.getString("field"))
        }
        val tuples = compact.getJSONArray("facts")
        assertEquals(pack.facts.size, tuples.length())
        pack.facts.forEachIndexed { index, fact ->
            val expected = JSONArray(listOf(fact.source, fact.kind.name, fact.start, fact.end, fact.value, fact.related, fact.mode.name))
            assertEquals("Every extracted relation and modality survives wire compaction", expected.toString(), tuples.getJSONArray(index).toString())
            val source = pack.sources[fact.source]
            val row = rows.single { it.getString("id") == source.evidenceId }
            assertEquals("Omitted duplicate quotes remain exactly reconstructable", fact.quote,
                AnalysisRunCodec.slice(row.getString("text"), fact.start - source.start, fact.end - source.start))
        }
    }

    private fun userEnvelope(body: String): JSONObject {
        val messages = JSONObject(body).getJSONArray("messages")
        return JSONObject((0 until messages.length()).map { messages.getJSONObject(it) }
            .single { it.getString("role") == "user" }.getString("content"))
    }

    private data class Source(val id: String, val date: String, val body: String)
    private data class Sample(val from: String, val through: String, val target: String, val sources: List<Source>)

    /** Read the existing frozen private fixture without copying or editing its sources/targets.
     * Constructing the test object does not call its opt-in live test or access credentials. */
    private fun frozenSample(id: String): Sample {
        fun field(value: Any, name: String): Any = requireNotNull(value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value))
        val fixtures = field(DeepSeekSemanticMixedClauseLiveTest(), "cases") as List<*>
        val value = requireNotNull(fixtures.single { it != null && field(it, "id") == id })
        val sources = (field(value, "records") as List<*>).map { item ->
            val source = requireNotNull(item)
            Source(field(source, "id") as String, field(source, "date") as String, field(source, "body") as String)
        }
        return Sample(field(value, "from") as String, field(value, "through") as String, field(value, "target") as String, sources)
    }
}
