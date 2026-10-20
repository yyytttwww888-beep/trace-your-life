package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.*
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Original synthetic text is supplied only through the public executor and registered fragments. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AnalysisInputCoverageTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private val database = "synthetic-input-coverage-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-20")
    private val scope = AnalysisQuery(day, day, "UTC")
    private val provider = Provider()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `single batch and its review declare complete text across all metadata pages without claiming semantic proof`() {
        note("one", "合成第一条原文"); note("two", "合成第二条原文")
        start()
        synthesize(listOf(EvidenceSelection("one", "body"), EvidenceSelection("two", "body")))
        assertCoverage(true, true, true)
        assertEquals(0, coverage().getInt("scopeAttachments"))
        assertEquals(0, coverage().getInt("attachmentsRead"))
        assertFalse(coverage().getBoolean("semanticallyVerified"))
        assertFalse(coverage().has("wholeScopeReviewed")); assertFalse(coverage().has("crossBatchIntegrated"))
        analysis.reviewClaim("run", "review", analysis.claimRef("run", "synthesis", 0), provider = provider)
        assertCoverage(true, true, true)
        assertEquals(2, coverage().getInt("reviewedFragments"))
        assertFalse(coverage().getBoolean("semanticallyVerified"))
    }

    @Test fun `all Unicode fragments and overlapping ranges form one complete field without gaps`() {
        val text = "合成😀长文ABC学习验证"
        note("long", text)
        start(fragmentSize = 4)
        val selections = (0 until text.codePointCount(0, text.length) step 4).map { EvidenceSelection("long", "body", it) } +
            EvidenceSelection("long", "body", 2)
        synthesize(selections)
        assertCoverage(true, true, true)
        assertEquals(selections.size, coverage().getInt("providedFragments"))
    }

    @Test fun `a later call cannot count an earlier batch as text supplied to this request`() {
        note("long", "合成文字完整末尾")
        start(fragmentSize = 4)
        synthesize(listOf(EvidenceSelection("long", "body", 0)))
        assertCoverage(false, false, false)
        analysis.synthesize("run", "tail", listOf(EvidenceSelection("long", "body", 4)), provider)
        assertCoverage(false, false, false)
    }

    @Test fun `complete body does not imply every answer in the provided record was supplied`() {
        val template = repository.saveTemplate(TemplateDraft("合成模板", Cadence.DAILY,
            questions = listOf(TemplateQuestion("answer", "合成问题"))))
        repository.createRecord(RecordDraft("合成正文", Occurrence(day), template = TemplateRef(template.id, template.version),
            answers = mapOf("answer" to "合成尚未提供的回答")), "record")
        start()
        synthesize(listOf(EvidenceSelection("record", "body")))
        assertCoverage(true, false, false)
    }

    @Test fun `complete selected record does not claim an unselected scope record was read`() {
        note("provided", "合成已提供原文"); note("other", "合成没有提供原文")
        start()
        synthesize(listOf(EvidenceSelection("provided", "body")))
        assertCoverage(true, true, false)
        assertFalse(provider.requests.last().payloadJson.contains("合成没有提供原文"))
    }

    @Test fun `all available text can be complete while attachment bytes remain unread`() {
        val attachment = repository.importAttachment("synthetic-image-bytes".byteInputStream(), "synthetic.jpg", "image/jpeg")
        repository.createRecord(RecordDraft("合成全部文字", Occurrence(day), attachmentIds = setOf(attachment.id)), "record")
        start()
        synthesize(listOf(EvidenceSelection("record", "body")))
        assertCoverage(true, true, true)
        assertEquals(1, coverage().getInt("scopeAttachments"))
        assertEquals(0, coverage().getInt("attachmentsRead"))
        assertFalse(provider.requests.last().payloadJson.contains("synthetic-image-bytes"))
    }

    @Test fun `unreadable source does not masquerade as an empty text scope or zero attachments`() {
        note("provided", "合成可读正文"); note("unreadable", "合成前缀\u0000末尾")
        start()
        synthesize(listOf(EvidenceSelection("provided", "body")))
        assertCoverage(true, true, false)
        assertEquals(1, coverage().getInt("unreadableRecords"))
        assertTrue(coverage().isNull("scopeAttachments"))
    }

    private fun note(id: String, text: String) = repository.createRecord(RecordDraft(text, Occurrence(day)), id)
    private fun start(fragmentSize: Int = 1024) = analysis.start(AnalysisRequest("合成精确覆盖核验", scope,
        AnalysisLimits(pageSize = 1, fragmentCodePoints = fragmentSize)), "run")
    private fun synthesize(selections: List<EvidenceSelection>) = analysis.synthesize("run", "synthesis", selections, provider)
    private fun coverage() = JSONObject(provider.requests.last().payloadJson).getJSONObject("coverage")
    private fun assertCoverage(fields: Boolean, records: Boolean, scope: Boolean) {
        assertEquals(fields, coverage().getBoolean("providedFieldsComplete"))
        assertEquals(records, coverage().getBoolean("providedRecordsTextComplete"))
        assertEquals(scope, coverage().getBoolean("allScopeTextProvided"))
    }
    private class Provider : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-input-coverage"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<ModelRequest>()
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray(Charsets.UTF_8).size
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            return BatchProposal(listOf(ProposedClaim("合成候选，仅用于覆盖测试", ids(request), ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareReview(request: ModelRequest) = prepare(request)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            requests += request.original
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, ids(request.original)))
        }
        private fun ids(request: ModelRequest): List<String> = JSONObject(request.payloadJson).getJSONArray("evidence").let { values ->
            (0 until values.length()).map { values.getJSONObject(it).getString("id") }
        }
    }
}
