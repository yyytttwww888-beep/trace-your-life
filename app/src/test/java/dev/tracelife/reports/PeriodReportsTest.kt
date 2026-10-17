package dev.tracelife.reports

import android.content.Context
import dev.tracelife.analysis.AnalysisFeedback
import dev.tracelife.analysis.AnalysisLimits
import dev.tracelife.analysis.AnalysisProvider
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.BatchProposal
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ClaimReview
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.analysis.EvidenceSelection
import dev.tracelife.analysis.ModelRequest
import dev.tracelife.analysis.PreparedModelRequest
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.analysis.ReviewCompletion
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.analysis.SemanticReviewProvider
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Cadence
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.model.TemplateRef
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceTimeClass
import dev.tracelife.storage.StoredKnowledgeRevision
import dev.tracelife.transfer.StaleSnapshotException
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
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** All user text, model responses and attachment bytes are synthetic fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PeriodReportsTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var reports: PeriodReports
    private lateinit var analysis: ControlledAnalysis
    private lateinit var library: KnowledgeLibrary
    private val name = "synthetic-period-reports-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val first = LocalDate.parse("2026-10-12")
    private val scope = AnalysisQuery(first, first.plusDays(6), "Asia/Shanghai")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        reports = PeriodReports(repository, clock)
        analysis = ControlledAnalysis(repository, clock)
        library = KnowledgeLibrary(repository, clock)
    }

    @After fun tearDown() { repository.close(); context.deleteDatabase(name) }

    @Test fun `question intent organizes materials while preserving original template revisions`() {
        val intents = listOf("expectation", "actual_and_evidence", "contributing_factors", "reusable_learning",
            "next_experiment", "action_follow_up", "personal_state", "my_custom_intent")
        val draft = TemplateDraft("合成每日复盘", Cadence.DAILY, questions = intents.mapIndexed { index, intent ->
            TemplateQuestion("q$index", "合成原问题$index", "合成原提示", intent)
        })
        val template = repository.saveTemplate(draft, "template")
        repository.createRecord(RecordDraft(occurrence = Occurrence(first), template = TemplateRef(template.id, 1),
            answers = draft.questions.associate { it.id to "合成原回答：${it.id}" }), "original")
        repository.saveTemplate(draft.copy(questions = listOf(TemplateQuestion("q0", "合成新版问题", intent = "next_experiment"))),
            template.id, 1)
        repository.createRecord(RecordDraft(occurrence = Occurrence(first.plusDays(1)), template = TemplateRef(template.id, 2),
            answers = mapOf("q0" to "合成新版回答")), "newer")

        val report = reports.open(scope)
        assertEquals(ReportSynthesisStatus.NOT_GENERATED, report.synthesisStatus)
        assertEquals(2, report.sectionCounts[ReportSection.ACTION])
        ReportSection.entries.filter { it != ReportSection.ACTION }.forEach { assertEquals(1, report.sectionCounts[it]) }
        val expectation = reports.answers(report, ReportSection.EXPECTATION).items.single()
        assertEquals(1, expectation.template.version)
        assertEquals("合成原问题0", expectation.question.prompt)
        assertEquals("expectation", expectation.question.intent)
        assertEquals("合成原回答：q0", reports.answer(report, expectation).text)
        val custom = reports.answers(report, ReportSection.OTHER).items.single()
        assertEquals("my_custom_intent", custom.question.intent)
        assertTrue(reports.answers(report, ReportSection.ACTION).items.any { it.template.version == 2 })
    }

    @Test fun `record and answer pages cover the full bounded candidate inventory`() {
        val template = singleQuestion()
        repeat(105) { index -> createAnswer("source-$index", template, first.plusDays((index % 7).toLong())) }
        val report = reports.open(scope)
        val records = reports.records(report, limit = 100)
        val remaining = reports.records(report, records.nextOffset!!, 100)
        assertEquals(105, (records.items + remaining.items).map { it.id }.distinct().size)
        assertNull(remaining.nextOffset)
        val answers = reports.answers(report, limit = 100)
        assertEquals(105, answers.total)
        assertEquals(5, reports.answers(report, offset = answers.nextOffset!!, limit = 100).items.size)
        assertEquals(List(7) { 15 }, report.metrics.daily.map { it.records })
        assertThrows(IllegalArgumentException::class.java) { reports.records(report, limit = 101) }
        assertThrows(IllegalArgumentException::class.java) { reports.answers(report, offset = -1) }
    }

    @Test fun `unknown and overlapping answers remain uncertain and never inflate daily counts`() {
        val template = singleQuestion()
        createAnswer("within", template, first)
        createAnswer("unknown", template, null)
        createAnswer("overlap", template, first.minusDays(1), first.plusDays(1))
        createAnswer("outside", template, first.minusDays(2))
        val report = reports.open(scope)
        val answers = reports.answers(report).items.associateBy { it.recordId }
        assertEquals(setOf("within", "unknown", "overlap"), answers.keys)
        assertEquals(SourceTimeClass.UNKNOWN, answers.getValue("unknown").timeClass)
        assertNull(answers.getValue("unknown").occurrence.startDate)
        assertEquals(SourceTimeClass.OVERLAP, answers.getValue("overlap").timeClass)
        assertEquals(1, report.metrics.exactDateRecords)
        assertEquals(1, report.metrics.unknownTimeRecords)
        assertEquals(1, report.metrics.overlappingRecords)
        assertEquals(1, report.metrics.daily.sumOf { it.records })
    }

    @Test fun `answers read Unicode fragments and reject references outside the report`() {
        val template = singleQuestion()
        repository.createRecord(RecordDraft(occurrence = Occurrence(first), template = template,
            answers = mapOf("question" to "甲😀乙丙丁")), "included")
        createAnswer("excluded", template, first)
        val report = reports.open(scope.copy(recordIds = setOf("included")))
        val ref = reports.answers(report).items.single()
        val fragment = reports.answer(report, ref, start = 1, maxCodePoints = 2)
        assertEquals("😀乙", fragment.text)
        assertEquals(1, fragment.start)
        assertEquals(3, fragment.end)
        assertEquals(5, fragment.totalCodePoints)
        assertTrue(fragment.question!!.contains("合成问题"))
        assertThrows(IllegalArgumentException::class.java) { reports.answer(report, ref.copy(recordId = "excluded")) }
        assertThrows(IllegalArgumentException::class.java) { reports.answer(report, ref, start = 5) }
    }

    @Test fun `knowledge requires all review context within scope and retains uncertain evidence labels`() {
        createBody("inside", first)
        createBody("unknown", null)
        createBody("overlap", first.minusDays(1), first.plusDays(1))
        createBody("outside", first.minusDays(3))
        publish("cross-boundary", "inside", additional = listOf(EvidenceSelection("outside", "body")),
            query = scope.copy(from = first.minusDays(3)))
        val uncertain = publish("unknown-context", "inside", additional = listOf(EvidenceSelection("unknown", "body")))
        val overlap = publish("overlap-context", "overlap")
        val report = reports.open(scope)
        assertEquals(2, report.knowledgeCount)
        assertEquals(1, report.omittedOutOfScopeKnowledge)
        assertEquals(0, report.omittedInvalidKnowledge)
        val page = reports.knowledge(report, limit = 1)
        val rest = reports.knowledge(report, page.nextOffset!!, 1)
        val all = (page.items + rest.items).associateBy { it.view.value.id }
        assertEquals(setOf(SourceTimeClass.WITHIN, SourceTimeClass.UNKNOWN), all.getValue(uncertain.id).evidenceTimeClasses)
        assertEquals(setOf(SourceTimeClass.OVERLAP), all.getValue(overlap.id).evidenceTimeClasses)
        assertNull(rest.nextOffset)
    }

    @Test fun `explicit ID range cannot pull in other same-day knowledge or archived records`() {
        createBody("included", first)
        createBody("other", first)
        createBody("archived", first)
        repository.setArchived("archived", 1, true)
        publish("included-claim", "included")
        publish("other-context", "included", additional = listOf(EvidenceSelection("other", "body")))
        val report = reports.open(scope.copy(recordIds = setOf("included", "archived", "missing")))
        assertEquals(setOf("archived", "missing"), report.metrics.missingExactIds)
        assertEquals(listOf("included"), reports.records(report).items.map { it.id })
        assertEquals(1, report.knowledgeCount)
        assertEquals(1, report.omittedOutOfScopeKnowledge)
    }

    @Test fun `caller-owned ID sets are frozen before report enumeration begins`() {
        val template = singleQuestion()
        createAnswer("included", template, first)
        createAnswer("other", template, first)
        val ids = mutableSetOf("included")
        val changingClock = object : Clock() {
            override fun instant(): Instant {
                ids.clear()
                ids += "other"
                return clock.instant()
            }
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
        }
        val service = PeriodReports(repository, changingClock)
        val report = service.open(scope.copy(recordIds = ids))
        assertEquals(setOf("other"), ids)
        assertEquals(setOf("included"), report.scope.recordIds)
        assertEquals(listOf("included"), service.records(report).items.map { it.id })
        assertEquals(listOf("included"), service.answers(report).items.map { it.recordId })
    }

    @Test fun `user rejection invalidates an existing report and is excluded when rebuilt`() {
        createBody("source", first)
        val saved = publish("run", "source")
        val before = reports.open(scope)
        AnalysisFeedback(repository, clock).respond(saved.runId, saved.claimRef, analysis.task(saved.runId).stored.version,
            ClaimFeedbackDecision.REJECTED, "合成用户反馈：不采用这项判断。")
        assertThrows(StaleSnapshotException::class.java) { reports.knowledge(before) }
        val after = reports.open(scope)
        assertEquals(0, after.knowledgeCount)
        assertEquals(1, after.omittedInvalidKnowledge)
        assertEquals(ReportSynthesisStatus.NOT_GENERATED, after.synthesisStatus)
    }

    @Test fun `earliest knowledge expiry invalidates every page without requiring a write`() {
        createBody("source", first)
        val expiry = clock.instant().plusSeconds(60)
        publish("first-expiry", "source", expiresAt = expiry)
        publish("later-expiry", "source", expiresAt = expiry.plusSeconds(60))
        val report = reports.open(scope)
        assertEquals(expiry, report.validUntil)
        clock.now = expiry
        assertThrows(StaleSnapshotException::class.java) { reports.assertCurrent(report) }
        assertThrows(StaleSnapshotException::class.java) { reports.records(report) }
        assertThrows(StaleSnapshotException::class.java) { reports.answers(report) }
        val rebuilt = reports.open(scope)
        assertEquals(1, rebuilt.knowledgeCount)
        assertEquals(1, rebuilt.omittedInvalidKnowledge)
        assertEquals(expiry.plusSeconds(60), rebuilt.validUntil)
    }

    @Test fun `source edits and analysis writes require a new report instead of mixed pages`() {
        val template = singleQuestion()
        createAnswer("source", template, first)
        val beforeEdit = reports.open(scope)
        val ref = reports.answers(beforeEdit).items.single()
        val original = repository.record("source")!!
        repository.updateRecord("source", original.revision, original.draft.copy(answers = mapOf("question" to "合成改写答案")))
        assertThrows(StaleSnapshotException::class.java) { reports.answer(beforeEdit, ref) }
        val beforeAnalysis = reports.open(scope)
        analysis.start(AnalysisRequest("合成新分析任务", scope), "new-run")
        assertThrows(StaleSnapshotException::class.java) { reports.records(beforeAnalysis) }
        val rebuilt = reports.open(scope)
        assertEquals(1, rebuilt.metrics.totalRecords)
        assertEquals("合成改写答案", reports.answer(rebuilt, reports.answers(rebuilt).items.single()).text)
    }

    @Test fun `knowledge drilldown is bounded to registered Unicode evidence rather than the full source`() {
        val text = "合成资料：前😀甲乙丙丁后"
        repository.createRecord(RecordDraft(text, Occurrence(first)), "source")
        val start = text.codePointCount(0, text.indexOf("😀"))
        val saved = publish("run", "source", fragmentCodePoints = 4, start = start)
        val report = reports.open(scope)
        val receipt = saved.evidence.single()
        assertEquals("😀甲乙丙", reports.evidence(report, saved.id, receipt.id, maxCodePoints = 4_096).text)
        val tail = reports.evidence(report, saved.id, receipt.id, start = 1, maxCodePoints = 4_096)
        assertEquals("甲乙丙", tail.text)
        assertEquals(receipt.start + 1, tail.start)
        assertEquals(receipt.end, tail.end)
        assertThrows(IllegalArgumentException::class.java) { reports.evidence(report, saved.id, receipt.id, start = 4) }
        assertThrows(IllegalArgumentException::class.java) { reports.evidence(report, saved.id, "not-provided") }
        assertThrows(IllegalArgumentException::class.java) { reports.evidence(report, "not-in-report", receipt.id) }
        repository.deleteRecord("source", 1)
        assertThrows(StaleSnapshotException::class.java) { reports.evidence(report, saved.id, receipt.id) }
    }

    @Test fun `unreadable fields and attachments remain gaps instead of completed analysis`() {
        val attachment = repository.importAttachment(ByteArrayInputStream("synthetic attachment".toByteArray()),
            "synthetic.txt", "text/plain")
        repository.createRecord(RecordDraft("合成资料：附件未处理", Occurrence(first), attachmentIds = setOf(attachment.id)), "attachment")
        repository.createRecord(RecordDraft("合成资料\u0000不可安全分片", Occurrence(first)), "unreadable")
        val report = reports.open(scope)
        assertEquals(1, report.unreadAttachments)
        assertEquals(1, report.unreadableRecords)
        assertEquals(2, report.metrics.totalRecords)
        assertEquals(0, reports.answers(report).total)
        assertEquals(ReportSynthesisStatus.NOT_GENERATED, report.synthesisStatus)
    }

    @Test fun `empty reports and invalid ranges never invent completed review or activity`() {
        val empty = reports.open(scope)
        assertEquals(0, empty.metrics.totalRecords)
        assertTrue(empty.metrics.daily.all { it.records == 0 })
        assertTrue(empty.sectionCounts.values.all { it == 0 })
        assertNull(empty.validUntil)
        assertEquals(ReportSynthesisStatus.NOT_GENERATED, empty.synthesisStatus)
        assertThrows(IllegalArgumentException::class.java) { reports.open(scope.copy(through = first.plusDays(366))) }
        assertThrows(IllegalArgumentException::class.java) { reports.open(scope.copy(through = first.minusDays(1))) }
        assertThrows(IllegalArgumentException::class.java) { reports.open(scope.copy(recordIds = emptySet())) }
    }

    private fun singleQuestion(): TemplateRef {
        val saved = repository.saveTemplate(TemplateDraft("合成模板", Cadence.DAILY,
            questions = listOf(TemplateQuestion("question", "合成问题", "合成提示", "actual_and_evidence"))))
        return TemplateRef(saved.id, saved.version)
    }

    private fun createAnswer(id: String, template: TemplateRef, start: LocalDate?, end: LocalDate? = start) =
        repository.createRecord(RecordDraft(occurrence = Occurrence(start, end), template = template,
            answers = mapOf("question" to "合成回答：发生时间与填写时间分别保留。")), id)

    private fun createBody(id: String, start: LocalDate?, end: LocalDate? = start) =
        repository.createRecord(RecordDraft("合成资料：记录一次练习，不能推断整个周期的成长。", Occurrence(start, end)), id)

    private fun publish(id: String, sourceId: String, additional: List<EvidenceSelection> = emptyList(),
        query: AnalysisQuery = scope, expiresAt: Instant? = null, fragmentCodePoints: Int = 1_024, start: Int = 0): StoredKnowledgeRevision {
        analysis.start(AnalysisRequest("合成资料整理", query, limits = AnalysisLimits(fragmentCodePoints = fragmentCodePoints),
            expiresAt = expiresAt), id)
        analysis.runBatch(id, "extract", listOf(EvidenceSelection(sourceId, "body", start)), object : AnalysisProvider {
            override val id = "synthetic-extractor"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
            override fun complete(request: ModelRequest): BatchProposal {
                val receipt = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim("合成陈述：用户记录了一次练习。", listOf(receipt), ClaimKind.REPORTED_EXPERIENCE)))
            }
        })
        val claim = analysis.claimRef(id, "extract", 0)
        analysis.reviewClaim(id, "review", claim, additional, reviewer)
        return library.publish(id, claim, "review", KnowledgeCategory.OBSERVATION, analysis.task(id).stored.version)
    }

    private val reviewer = object : SemanticReviewProvider {
        override val id = "synthetic-reviewer"
        override val contextWindowTokens = 100_000
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, request.payloadJson.toByteArray().size)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val receipt = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(receipt)))
        }
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-17T00:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
