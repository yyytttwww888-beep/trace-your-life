package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.metrics.DailyRecordCount
import dev.tracelife.metrics.RecordMetricProtocol
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.reports.PeriodReports
import dev.tracelife.storage.*
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
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile

/** Synthetic sources, model replies and password only. No network or personal data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MetricKnowledgeTransferTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var workspace: File
    private val name = "synthetic-metric-transfer-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC)
    private val first = LocalDate.parse("2026-10-12")
    private val scope = AnalysisQuery(first, first.plusDays(6), "UTC")
    private val password = "synthetic-metric-transfer-password".toCharArray()
    private var sequence = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        analysis = ControlledAnalysis(repository, clock)
        workspace = File(context.cacheDir, "synthetic-metric-transfer-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() { repository.close(); context.deleteDatabase(name); workspace.deleteRecursively() }

    @Test fun `reading export keeps typed metric result complete source links and reverse navigation`() {
        record("one", first)
        record("unknown", null)
        val note = publish()
        val entries = reading()
        val result = JSONObject(entries.filterKeys { it.startsWith("notes/metrics/") && it.endsWith("result.json") }.values.single())
        assertEquals(note.metricEvidence.single().id, result.getString("id"))
        assertEquals(2, result.getJSONArray("sources").length())
        assertEquals(1, result.getJSONObject("values").getInt("unknownTimeRecords"))
        val statementPath = entries.keys.single { it.startsWith("notes/entries/") }
        val statement = entries.getValue(statementPath)
        assertTrue(statement.contains("没有向模型提供记录正文"))
        assertTrue(statement.contains("程序计算依据"))
        assertTrue(entries.keys.any { it.startsWith("notes/sources/") })
        val data = entries.filterKeys { it.startsWith("data/knowledge/") && it.endsWith("jsonl") }.values.single()
        assertEquals(2, JSONObject(data.trim()).getJSONArray("recordPaths").length())
        assertLinks(entries)
    }

    @Test fun `period report exports metric metadata without inventing raw text citations`() {
        val marker = "合成正文：指标模型并未读取此内容。"
        record("one", first, body = marker)
        val note = publish()
        val reports = PeriodReports(repository, clock)
        val report = reports.open(scope)
        assertEquals(1, report.knowledgeCount)
        assertThrows(IllegalArgumentException::class.java) {
            reports.evidence(report, note.id, note.metricEvidence.single().id)
        }
        val destination = file("period", "zip")
        DataTransfer(context, repository, clock).exportPeriodReport(destination, scope)
        val entries = unzip(destination)
        assertTrue(entries.keys.any { it.startsWith("knowledge/entries/") && "/metrics/" in it && it.endsWith("result.json") })
        assertTrue(entries.values.none { marker in it })
        assertFalse(entries.keys.any { "/evidence/" in it })
        assertTrue(entries.filterKeys { it.endsWith("statement.md") }.values.single().contains("模型未读取记录正文"))
        assertLinks(entries)
    }

    @Test fun `complete metric interval is required even if every member lies in a smaller export`() {
        record("one", first)
        publish()
        val entries = reading(ReadingExportOptions(scope = ExportScope(from = first, through = first)))
        assertFalse(entries.keys.any { it.startsWith("notes/") })
        assertEquals(1, JSONObject(entries.getValue("manifest.json")).getJSONObject("knowledge")
            .getJSONObject("omittedByReason").getInt("metric_scope_not_contained"))
        val report = PeriodReports(repository, clock).open(scope.copy(through = first))
        assertEquals(0, report.knowledgeCount)
        assertEquals(1, report.omittedOutOfScopeKnowledge)
    }

    @Test fun `empty metrics retain date archive and exact ID query restrictions`() {
        val emptyScope = scope.copy(includeArchived = true, recordIds = setOf("missing"))
        publish(emptyScope)
        val reports = PeriodReports(repository, clock)
        assertEquals(1, reports.open(emptyScope).knowledgeCount)
        listOf(emptyScope.copy(through = first), emptyScope.copy(includeArchived = false),
            emptyScope.copy(recordIds = setOf("other-missing")), emptyScope.copy(zoneId = "Asia/Shanghai")).forEach { smaller ->
            assertEquals(0, reports.open(smaller).knowledgeCount)
        }
        val entries = reading(ReadingExportOptions(scope = ExportScope(includeArchived = false)))
        assertFalse(entries.keys.any { it.startsWith("notes/") })
        assertEquals(1, JSONObject(entries.getValue("manifest.json")).getJSONObject("knowledge")
            .getJSONObject("omittedByReason").getInt("metric_scope_not_contained"))
    }

    @Test fun `topic selection cannot export knowledge with only some metric members`() {
        record("learning", first, setOf("learning"))
        record("work", first, setOf("work"))
        publish()
        val entries = reading(ReadingExportOptions(scope = ExportScope(topics = setOf("learning"))))
        assertFalse(entries.keys.any { it.startsWith("notes/") })
        assertEquals(1, JSONObject(entries.getValue("manifest.json")).getJSONObject("knowledge")
            .getJSONObject("omittedByReason").getInt("evidence_out_of_scope"))
        val subset = PeriodReports(repository, clock).open(scope.copy(recordIds = setOf("learning", "work")))
        // An explicit selection cannot stand in for an unrestricted full-scope query.
        assertEquals(0, subset.knowledgeCount)
    }

    @Test fun `encrypted restore preserves metric proof and independent feedback but remains stale`() {
        record("one", first)
        val note = publish()
        val transfer = DataTransfer(context, repository, clock)
        val archive = file("backup", "tlb")
        transfer.exportBackup(archive, password)
        val preview = transfer.previewBackup(archive, password)
        transfer.restoreBackup(archive, password, preview, replaceExisting = true)
        val restored = KnowledgeLibrary(repository, clock).get(note.id)!!
        assertEquals(note.metricEvidence, restored.value.metricEvidence)
        assertEquals(KnowledgeEvidenceState.STALE, restored.evidenceState)
        assertEquals("合成独立确认文字", repository.claimFeedback(AnalysisRunCodec.feedbackId(note.runId, note.claimRef))!!.userText)
        assertFalse(reading().keys.any { it.startsWith("notes/") })
    }

    @Test fun `backup rejects missing metric source and altered original date before writing archive`() {
        record("one", first)
        publish()
        val snapshot = snapshot()
        reject(snapshot.copy(records = emptyList(), changes = emptyList()))
        reject(snapshot.copy(records = snapshot.records.map { it.copy(draft = it.draft.copy(occurrence = Occurrence(first.plusDays(1)))) }))
    }

    @Test fun `current snapshot rejects a self consistent metric that silently omits a matching record`() {
        record("one", first)
        val note = publish()
        val original = note.metricEvidence.single()
        val empty = original.copy(sources = emptyList(), values = original.values.copy(totalRecords = 0,
            exactDateRecords = 0, recordedDays = 0, daily = original.values.daily.map { DailyRecordCount(it.date, 0) }))
        val forged = empty.copy(id = "metric-v1-" + AnalysisRunCodec.sha256(AnalysisRunCodec.json(mapOf(
            "protocol" to "trace-life-metric-receipt-v1", "content" to (RecordMetricProtocol.map(empty) - "id")))))
        // Identity and arithmetic alone cannot attest that a complete current query was executed.
        RecordMetricProtocol.validate(forged, records = repository.history("one"))
        val claim = note.claim.copy(evidenceIds = listOf(forged.id))
        val ref = note.claimRef.copy(claimSha256 = AnalysisRunCodec.claimSha256(claim))
        val forgedNote = note.copy(id = KnowledgeValidation.idFor(note.runId, ref), claimRef = ref, claim = claim,
            review = note.review.copy(supportingEvidenceIds = listOf(forged.id)), metricEvidence = listOf(forged))
        reject(snapshot().copy(analysisRuns = emptyList(), claimFeedback = emptyList(),
            knowledgeRevisions = listOf(forgedNote), knowledgeHeads = listOf(KnowledgeHead(forgedNote.id, 1))))
    }

    @Test fun `record deletion prevents old backup from restoring metric knowledge and preserves user text`() {
        record("one", first)
        val note = publish()
        val transfer = DataTransfer(context, repository, clock)
        val archive = file("deleted", "tlb")
        transfer.exportBackup(archive, password)
        repository.deleteRecord("one", 1)
        transfer.restoreBackup(archive, password, transfer.previewBackup(archive, password), replaceExisting = true)
        assertNull(repository.knowledgeRevision(note.id))
        assertEquals("合成独立确认文字", repository.claimFeedback(AnalysisRunCodec.feedbackId(note.runId, note.claimRef))!!.userText)
        assertNull(repository.record("one"))
    }

    private fun publish(query: AnalysisQuery = scope): StoredKnowledgeRevision {
        val run = analysis.start(AnalysisRequest("合成问题：记录覆盖有什么限制？", query), "synthetic-run-${sequence++}")
        val id = run.stored.id
        analysis.analyzeMetrics(id, "metric", object : AnalysisProvider {
            override val id = "synthetic-metric-provider"
            override val contextWindowTokens = 32_000
            override fun countInputTokens(request: ModelRequest) = 100
            override fun complete(request: ModelRequest): BatchProposal {
                val metricId = JSONObject(request.payloadJson).getJSONArray("metricEvidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim("合成解释：记录覆盖只描述已保存资料，不能证明活动或成长。",
                    listOf(metricId), ClaimKind.POSSIBLE_INTERPRETATION)))
            }
        })
        val claim = analysis.claimRef(id, "metric", 0)
        AnalysisFeedback(repository, clock).respond(id, claim, analysis.task(id).stored.version,
            ClaimFeedbackDecision.CONFIRMED, "合成独立确认文字")
        analysis.reviewClaim(id, "review", claim, provider = object : SemanticReviewProvider {
            override val id = "synthetic-metric-review"
            override val contextWindowTokens = 32_000
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                val metricId = JSONObject(request.original.payloadJson).getJSONArray("metricEvidence").getJSONObject(0).getString("id")
                return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(metricId)))
            }
        })
        return KnowledgeLibrary(repository, clock).publish(id, claim, "review", KnowledgeCategory.INSIGHT,
            analysis.task(id).stored.version, topics = setOf("learning"))
    }

    private fun record(id: String, date: LocalDate?, topics: Set<String> = setOf("learning"), body: String = "合成记录：$id") =
        repository.createRecord(RecordDraft(body, Occurrence(date, date, "UTC"), topics), id)

    private fun snapshot() = repository.captureSnapshot(File(workspace, "snapshot-${sequence++}").apply { mkdirs() })
    private fun file(prefix: String, suffix: String) = File(workspace, "$prefix-${sequence++}.$suffix")
    private fun reading(options: ReadingExportOptions = ReadingExportOptions()): Map<String, String> {
        val destination = file("reading", "zip")
        DataTransfer(context, repository, clock).exportReadingPackage(destination, options)
        return unzip(destination)
    }
    private fun reject(snapshot: RepositorySnapshot) {
        val destination = file("invalid", "tlb")
        assertThrows(InvalidBackupException::class.java) { BackupArchive.write(snapshot, workspace, destination, password) }
        assertFalse(destination.exists())
    }
    private fun unzip(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() } }
    }
    private fun assertLinks(entries: Map<String, String>) {
        val link = Regex("\\[[^]]*]\\(([^)]+)\\)")
        entries.filterKeys { it.endsWith(".md") }.forEach { (path, body) ->
            link.findAll(body).forEach { match ->
                val target = (Paths.get(path).parent ?: Paths.get("")).resolve(match.groupValues[1]).normalize().toString()
                assertTrue("missing $path -> $target", target in entries)
            }
        }
    }
}
