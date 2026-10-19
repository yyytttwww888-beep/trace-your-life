package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.actions.*
import dev.tracelife.analysis.*
import dev.tracelife.dashboard.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
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
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile

/** Synthetic-only export checks: source navigation, body omissions and no stale file publication. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DashboardArchiveTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var directory: File
    private lateinit var dashboards: DashboardService
    private lateinit var transfer: DataTransfer
    private val clock = MutableClock()
    private val name = "synthetic-dashboard-export-${UUID.randomUUID()}.db"
    private val today = LocalDate.parse("2026-10-19")
    private val request = DashboardRequest(zoneId = "Asia/Shanghai")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        directory = File(context.cacheDir, "synthetic-dashboard-export-${UUID.randomUUID()}").apply { check(mkdir()) }
        dashboards = DashboardService(repository, clock)
        transfer = DataTransfer(context, repository, clock)
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(name)
        directory.deleteRecursively()
    }

    @Test fun `export paginates two windows and preserves contributors without any source or review body`() {
        repeat(27) { index -> repository.createRecord(RecordDraft("PRIVATE_RECORD_BODY_$index", Occurrence(today)), "synthetic-$index") }
        repository.createRecord(RecordDraft("PRIVATE_UNKNOWN_BODY"), "synthetic-unknown")
        repository.createRecord(RecordDraft("PRIVATE_INTERVAL_BODY", Occurrence(today.minusDays(7), today.minusDays(5))), "synthetic-interval")
        repository.createRecord(RecordDraft("PRIVATE_PREVIOUS_BODY", Occurrence(today.minusDays(7))), "synthetic-previous")
        val actions = ActionExperiments(repository, clock)
        val action = actions.create(ActionDraft("=HYPERLINK [合成](../../outside)", "PRIVATE_TRIGGER", "PRIVATE_BEHAVIOR",
            "PRIVATE_SIGNAL", today, "Asia/Shanghai"), "synthetic-action")
        val reviewed = actions.review(action.value.id, action.value.revision, "synthetic-review",
            ActionReview(ActionExecution.EXECUTED, "PRIVATE_OBSERVATION", ActionDecision.CONTINUE, today), today.plusDays(1))
        actions.update(reviewed.value.id, reviewed.value.revision, reviewed.value.draft.copy(title = "合成 [新标题](../../outside)"))
        val destination = File(directory, "dashboard.zip")
        val result = transfer.exportDashboard(destination, request)
        val content = contents(destination)
        assertEquals(30, result.records)
        assertEquals(0, result.attachments)
        assertEquals(result.files, content.size)
        assertFalse(content.values.any { "PRIVATE_" in it })
        assertTrue(content.keys.contains("sources/current/page-00002.json"))
        val current = sourceEntries(content, "current")
        val previous = sourceEntries(content, "previous")
        assertEquals(29, current.size)
        assertEquals(3, previous.size)
        assertTrue(current.filter { it.getString("recordId").matches(Regex("synthetic-[0-9]+")) }
            .all { it.getBoolean("contributesToPoint") && it.getInt("pointIndex") == 6 })
        listOf("synthetic-unknown", "synthetic-interval").forEach { id ->
            val entry = current.single { it.getString("recordId") == id }
            assertTrue(entry.isNull("pointIndex"))
            assertFalse(entry.getBoolean("contributesToPoint"))
        }
        val currentCsv = content.getValue("metrics/current.csv").lines().filter(String::isNotBlank)
        assertEquals(8, currentCsv.size)
        assertTrue(currentCsv.last().endsWith(",2026-10-19,2026-10-19,1,27,1,true"))
        assertFalse(content.filterKeys { it.endsWith(".csv") }.values.any { "HYPERLINK" in it || "合成" in it })
        val actionEntry = JSONObject(content.getValue("actions/page-00001.json")).getJSONArray("entries").getJSONObject(0)
        assertEquals(3, actionEntry.getInt("revision"))
        assertEquals("EXECUTED", actionEntry.getString("latestExecution"))
        assertEquals(2, actionEntry.getJSONObject("latestReview").getInt("revision"))
        val markerEntries = JSONObject(content.getValue("markers/page-00001.json")).getJSONArray("entries")
        assertEquals(2, markerEntries.length())
        val manifest = JSONObject(content.getValue("manifest.json"))
        assertEquals("trace-life-dashboard", manifest.getString("format"))
        assertEquals("historical_snapshot_at_evaluatedAt", manifest.getString("validity"))
        assertEquals("global_record_coverage_not_health_card_evidence", manifest.getString("sourceMetadataPurpose"))
        assertFalse(manifest.getBoolean("restorable"))
        assertFalse(manifest.getBoolean("modelCitableReceipt"))
        assertEquals(0, manifest.getInt("modelRequests"))
        verifyInventory(content)
        verifyLinks(content)
        assertCleanStaging()
    }

    @Test fun `default health cards remain unavailable while record coverage is separately exported`() {
        val destination = File(directory, "empty.zip")
        transfer.exportDashboard(destination, request)
        val content = contents(destination)
        val metadata = JSONObject(content.getValue("metadata.json"))
        val cards = metadata.getJSONArray("cards")
        assertEquals(3, cards.length())
        assertEquals("SOURCE_NOT_CONNECTED", cards.getJSONObject(0).getString("state"))
        assertEquals("SOURCE_NOT_CONNECTED", cards.getJSONObject(1).getString("state"))
        assertEquals("STRUCTURED_INPUT_UNAVAILABLE", cards.getJSONObject(2).getString("state"))
        repeat(cards.length()) { assertTrue(cards.getJSONObject(it).isNull("currentMetricId")) }
        val values = JSONObject(content.getValue("metrics/current.json")).getJSONObject("values")
        assertEquals(0, values.getInt("totalRecords"))
        assertEquals(7, values.getJSONArray("daily").length())
        assertTrue(metadata.getJSONObject("comparison").isNull("exactDateRecordsDelta"))
        assertTrue(content.getValue("sources/current/index.md").contains("当前无对应资料"))
        assertTrue(content.getValue("README.md").contains("不是睡眠、运动或饮食卡片的健康依据"))
        verifyInventory(content)
        verifyLinks(content)
    }

    @Test fun `ninety day CSV retains partial natural weeks and daily values for recomputation`() {
        val selected = request.copy(window = DashboardWindow.DAYS_90, through = today.minusDays(1),
            cards = listOf(DashboardMetric.RECORD_COVERAGE))
        val snapshot = dashboards.open(selected)
        val destination = File(directory, "ninety.zip")
        transfer.exportDashboard(destination, snapshot)
        val content = contents(destination)
        val metric = JSONObject(content.getValue("metrics/current.json"))
        assertEquals(90, metric.getJSONObject("values").getJSONArray("daily").length())
        val points = metric.getJSONArray("points")
        val csv = content.getValue("metrics/current.csv").lines().filter(String::isNotBlank)
        assertEquals(points.length() + 1, csv.size)
        assertEquals(90, (0 until points.length()).sumOf { points.getJSONObject(it).getInt("days") })
        assertTrue(points.getJSONObject(0).getInt("days") < 7)
        repeat(points.length()) { assertFalse(points.getJSONObject(it).getBoolean("inProgress")) }
        val comparison = JSONObject(content.getValue("metadata.json")).getJSONObject("comparison")
        assertEquals("COMPLETE_RECORDED_WINDOWS", comparison.getString("state"))
        assertEquals(0, comparison.getInt("exactDateRecordsDelta"))
        assertEquals(snapshot.id, JSONObject(content.getValue("manifest.json")).getString("snapshotId"))
    }

    @Test fun `metric insight exports only its identity while feedback and claim text remain absent`() {
        repository.createRecord(RecordDraft("PRIVATE_RECORD_BODY", Occurrence(today)), "synthetic-source")
        val saved = publishMetric()
        val destination = File(directory, "insight.zip")
        transfer.exportDashboard(destination, request)
        val content = contents(destination)
        assertFalse(content.values.any { "PRIVATE_" in it })
        val entries = JSONObject(content.getValue("insights/current/page-00001.json")).getJSONArray("entries")
        assertEquals(1, entries.length())
        assertEquals(saved.id, entries.getJSONObject(0).getString("knowledgeId"))
        assertEquals(saved.revision, entries.getJSONObject(0).getInt("revision"))
        assertFalse(entries.getJSONObject(0).getBoolean("bodyIncluded"))
        assertEquals(1, JSONObject(content.getValue("metadata.json")).getInt("insightCount"))
    }

    @Test fun `source action and analysis changes reject old dashboard without publishing output`() {
        val source = repository.createRecord(RecordDraft("合成原始", Occurrence(today)), "synthetic-source")
        val original = dashboards.open(request)
        repository.updateRecord(source.id, source.revision, source.draft.copy(body = "合成修改"))
        rejectOld("source.zip", original)
        val beforeAction = dashboards.open(request)
        ActionExperiments(repository, clock).create(ActionDraft("合成行动", "合成条件", "合成做法", "合成信号", today, "UTC"), "synthetic-action")
        rejectOld("action.zip", beforeAction)
        val beforeAnalysis = dashboards.open(request)
        ControlledAnalysis(repository, clock).start(AnalysisRequest("合成问题", AnalysisQuery(today, today, "UTC")), "synthetic-run")
        rejectOld("analysis.zip", beforeAnalysis)
        assertCleanStaging()
    }

    @Test fun `crossing midnight and knowledge expiry reject export without a database write`() {
        val first = dashboards.open(request)
        val generation = repository.currentGeneration()
        val analysisGeneration = repository.currentAnalysisGeneration()
        clock.now = first.validUntil
        rejectOld("midnight.zip", first)
        assertEquals(generation, repository.currentGeneration())
        assertEquals(analysisGeneration, repository.currentAnalysisGeneration())
        clock.now = Instant.parse("2026-10-19T00:00:00Z")
        publishMetric(expiresAt = clock.instant().plusSeconds(60))
        val second = dashboards.open(request)
        assertEquals(clock.instant().plusSeconds(60), second.validUntil)
        clock.now = second.validUntil
        rejectOld("expired.zip", second)
        assertCleanStaging()
    }

    @Test fun `destination never overwritten and direct archive failure removes its partial file`() {
        val destination = File(directory, "existing.zip")
        transfer.exportDashboard(destination, request)
        val bytes = destination.readBytes()
        assertThrows(IllegalArgumentException::class.java) { transfer.exportDashboard(destination, request) }
        assertArrayEquals(bytes, destination.readBytes())
        val snapshot = dashboards.open(request)
        clock.now = snapshot.validUntil
        val stale = File(directory, "direct-stale.zip")
        assertThrows(StaleSnapshotException::class.java) { DashboardArchive.write(dashboards, snapshot, stale) }
        assertFalse(stale.exists())
        assertCleanStaging()
    }

    @Test fun `clock rollback only during final publication rejects file and cleans owned staging`() {
        val snapshot = dashboards.open(request)
        clock.duringPublication = snapshot.evaluatedAt.minusSeconds(1)
        rejectOld("clock-rollback.zip", snapshot)
        assertCleanStaging()
    }

    @Test fun `expiry only during final publication rejects file and cleans owned staging`() {
        val snapshot = dashboards.open(request)
        clock.duringPublication = snapshot.validUntil
        rejectOld("clock-expiry.zip", snapshot)
        assertCleanStaging()
    }

    private fun publishMetric(expiresAt: Instant? = null): dev.tracelife.storage.StoredKnowledgeRevision {
        val analysis = ControlledAnalysis(repository, clock)
        val scope = AnalysisQuery(today.minusDays(6), today, request.zoneId)
        analysis.start(AnalysisRequest("合成指标解释", scope, expiresAt = expiresAt), "synthetic-metric-run")
        val provider = MetricProvider()
        analysis.analyzeMetrics("synthetic-metric-run", "metric", provider)
        val claim = analysis.claimRef("synthetic-metric-run", "metric", 0)
        AnalysisFeedback(repository, clock).respond("synthetic-metric-run", claim, analysis.task("synthetic-metric-run").stored.version,
            ClaimFeedbackDecision.CONFIRMED, "PRIVATE_FEEDBACK_BODY")
        analysis.reviewClaim("synthetic-metric-run", "review", claim, provider = provider)
        return KnowledgeLibrary(repository, clock).publish("synthetic-metric-run", claim, "review", KnowledgeCategory.INSIGHT,
            analysis.task("synthetic-metric-run").stored.version)
    }

    private fun rejectOld(filename: String, snapshot: DashboardSnapshot) {
        val file = File(directory, filename)
        assertThrows(StaleSnapshotException::class.java) { transfer.exportDashboard(file, snapshot) }
        assertFalse(file.exists())
    }
    private fun contents(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() } }
    }
    private fun sourceEntries(content: Map<String, String>, period: String): List<JSONObject> = content
        .filterKeys { it.startsWith("sources/$period/") && it.endsWith(".json") }.toSortedMap().values.flatMap { body ->
            val entries = JSONObject(body).getJSONArray("entries")
            (0 until entries.length()).map(entries::getJSONObject)
        }
    private fun verifyInventory(content: Map<String, String>) {
        val manifest = JSONObject(content.getValue("manifest.json"))
        var path: String? = manifest.getJSONObject("inventory").getString("path")
        var expectedHash: String? = manifest.getJSONObject("inventory").getString("sha256")
        val listed = mutableSetOf<String>()
        while (path != null) {
            val pageText = content.getValue(path)
            assertEquals(expectedHash, hash(pageText.toByteArray()))
            val page = JSONObject(pageText)
            assertTrue(page.getJSONArray("entries").length() <= 25)
            repeat(page.getJSONArray("entries").length()) { index ->
                val leaf = page.getJSONArray("entries").getJSONObject(index)
                val leafText = content.getValue(leaf.getString("path"))
                assertEquals(leaf.getString("sha256"), hash(leafText.toByteArray()))
                val lines = leafText.lineSequence().filter(String::isNotBlank).toList()
                assertTrue(lines.size <= 25)
                lines.forEach { line ->
                    val file = JSONObject(line)
                    val name = file.getString("path")
                    assertTrue(listed.add(name))
                    val bytes = content.getValue(name).toByteArray()
                    assertEquals(file.getInt("bytes"), bytes.size)
                    assertEquals(file.getString("sha256"), hash(bytes))
                }
            }
            path = if (page.isNull("next")) null else page.getString("next")
            expectedHash = if (page.isNull("nextSha256")) null else page.getString("nextSha256")
        }
        assertEquals(content.keys.filter { !it.startsWith("inventory/") && it != "manifest.json" }.toSet(), listed)
    }
    private fun verifyLinks(content: Map<String, String>) {
        assertTrue(content.keys.all { !it.startsWith('/') && it.split('/').none { part -> part == ".." } })
        content.filterKeys { it.endsWith(".md") }.forEach { (path, value) ->
            Regex("\\[[^]]*]\\(([^)]+)\\)").findAll(value).forEach { match ->
                val target = (Paths.get(path).parent ?: Paths.get("")).resolve(match.groupValues[1]).normalize().toString().replace('\\', '/')
                assertTrue("Missing $path -> $target", content.containsKey(target))
            }
        }
    }
    private fun assertCleanStaging() {
        assertTrue(File(context.noBackupFilesDir, "transfer-staging").listFiles().orEmpty().isEmpty())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".pending") })
    }
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private class MetricProvider : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-dashboard-metric"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        override fun complete(request: ModelRequest): BatchProposal {
            val metric = JSONObject(request.payloadJson).getJSONArray("metricEvidence").getJSONObject(0)
            return BatchProposal(listOf(ProposedClaim("PRIVATE_METRIC_INSIGHT_BODY", listOf(metric.getString("id")), ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val id = JSONObject(request.payloadJson).getJSONArray("metricEvidence").getJSONObject(0).getString("id")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(id)))
        }
    }
    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T00:00:00Z")) : Clock() {
        var duringPublication: Instant? = null
        override fun instant(): Instant = duringPublication?.takeIf {
            Thread.currentThread().stackTrace.any { frame -> frame.className.contains("LifeRepository") &&
                (frame.methodName == "publishDerivedFile" || frame.className.contains("publishDerivedFile")) }
        } ?: now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
