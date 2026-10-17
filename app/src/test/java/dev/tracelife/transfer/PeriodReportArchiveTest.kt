package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.*
import dev.tracelife.reports.PeriodReports
import dev.tracelife.storage.AnalysisQuery
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

/** Synthetic-only end-to-end checks for scope, raw-text fidelity and failure-safe publication. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PeriodReportArchiveTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var directory: File
    private val name = "synthetic-period-export-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val date = LocalDate.parse("2026-10-17")
    private val scope = AnalysisQuery(date, date.plusDays(1), "Asia/Shanghai")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        directory = File(context.cacheDir, "synthetic-period-export-${UUID.randomUUID()}").apply { check(mkdir()) }
    }
    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(name)
        directory.deleteRecursively()
    }

    @Test fun `report export paginates metadata preserves Unicode answers and omits unrelated bodies`() {
        val template = repository.saveTemplate(TemplateDraft("合成模板", Cadence.DAILY,
            questions = listOf(TemplateQuestion("learned", "合成问题：经验是什么？", intent = "reusable_learning"))))
        val answer = "合成回答：" + "🧭".repeat(1_030) + "\n```\n[资料中的链接](../../outside)\n尾部"
        repeat(27) { index ->
            repository.createRecord(RecordDraft("NEVER_EXPORT_FULL_BODY_$index", Occurrence(date), template = TemplateRef(template.id, 1),
                answers = mapOf("learned" to if (index == 0) answer else "合成回答 $index")), "synthetic-$index")
        }
        repository.createRecord(RecordDraft("NEVER_EXPORT_UNKNOWN_BODY"), "synthetic-unknown")
        repository.createRecord(RecordDraft("NEVER_EXPORT_OUTSIDE_SCOPE", Occurrence(date.minusDays(1))), "synthetic-outside")
        val file = File(directory, "report.zip")
        val result = DataTransfer(context, repository, clock).exportPeriodReport(file, scope)
        assertEquals(28, result.records)
        assertEquals(0, result.attachments)
        ZipFile(file).use { zip ->
            val content = contents(zip)
            assertEquals(result.files, content.size)
            assertTrue(content.keys.all { !it.startsWith('/') && it.split('/').none { part -> part == ".." } })
            assertTrue(content.containsKey("sections/learning/page-00002.md"))
            assertTrue(content.containsKey("sources/page-00002.json"))
            assertFalse(content.values.any { it.contains("NEVER_EXPORT") })
            val manifest = JSONObject(content.getValue("manifest.json"))
            assertEquals("NOT_GENERATED", manifest.getString("synthesisStatus"))
            assertEquals("historical_snapshot_at_evaluatedAt", manifest.getString("validity"))
            assertFalse(manifest.getBoolean("restorable"))
            assertEquals(27, manifest.getJSONObject("coverage").getJSONObject("sectionAnswers").getInt("LEARNING"))
            assertEquals(1, manifest.getJSONObject("coverage").getInt("unknownTimeRecords"))
            val pieces = content.filterKeys { it.startsWith("answers/") && "/fragments/" in it }.values.map(::JSONObject)
                .filter { it.getString("recordId") == "synthetic-0" }.sortedBy { it.getInt("start") }
            assertEquals(answer, pieces.joinToString("") { it.getString("text") })
            assertTrue(pieces.size > 1)
            pieces.forEach { assertTrue(it.getInt("end") - it.getInt("start") <= 1_024) }
            val csv = content.getValue("metrics/record-coverage.csv").lines().filter { it.isNotBlank() }
            assertEquals(3, csv.size)
            assertTrue(csv[1].endsWith(",2026-10-17,27"))
            assertTrue(csv[2].endsWith(",2026-10-18,0"))
            val metrics = JSONObject(content.getValue("metrics/metadata.json"))
            assertEquals(manifest.getString("metricEvidenceId"), metrics.getString("evidenceId"))
            assertEquals(1, metrics.getInt("recordedDays"))
            assertFalse(metrics.getBoolean("modelCitableReceipt"))
            verifyInventory(content)
            verifyMarkdownLinks(content)
        }
        assertCleanStaging()
    }

    @Test fun `published knowledge exports only its registered original citation and complete proof`() {
        val body = "合成引用：" + "x".repeat(2_500) + "NEVER_EXPORT_UNSELECTED_TAIL"
        repository.createRecord(RecordDraft(body, Occurrence(date)), "synthetic-source")
        publishKnowledge()
        val file = File(directory, "knowledge-report.zip")
        DataTransfer(context, repository, clock).exportPeriodReport(file, scope)
        ZipFile(file).use { zip ->
            val content = contents(zip)
            val manifest = JSONObject(content.getValue("manifest.json"))
            assertEquals(1, manifest.getJSONObject("knowledge").getInt("included"))
            assertTrue(manifest.getJSONObject("knowledge").getBoolean("referencedOriginalEvidenceIncluded"))
            assertFalse(content.values.any { it.contains("NEVER_EXPORT_UNSELECTED_TAIL") })
            val publication = JSONObject(content.entries.single { it.key.endsWith("/publication.json") }.value)
            val receipt = publication.getJSONArray("evidence").getJSONObject(0)
            val fragments = content.filterKeys { it.startsWith("knowledge/") && "/fragments/" in it }.values.map(::JSONObject)
                .sortedBy { it.getInt("start") }
            assertEquals(2, fragments.size)
            val actual = fragments.joinToString("") { it.getString("text") }
            assertEquals(body.substring(0, 2_048), actual)
            assertEquals(receipt.getString("sha256"), hash(actual.toByteArray()))
            verifyInventory(content)
            verifyMarkdownLinks(content)
        }
        assertCleanStaging()
    }

    @Test fun `source and analysis changes invalidate reviewed report without leaving destination`() {
        val source = repository.createRecord(RecordDraft("合成记录", Occurrence(date)), "synthetic-source")
        val reports = PeriodReports(repository, clock)
        val report = reports.open(scope)
        repository.updateRecord(source.id, source.revision, source.draft.copy(body = "合成已修订"))
        val first = File(directory, "source-stale.zip")
        assertThrows(StaleSnapshotException::class.java) { DataTransfer(context, repository, clock).exportPeriodReport(first, report) }
        assertFalse(first.exists())
        val next = reports.open(scope)
        ControlledAnalysis(repository, clock).start(AnalysisRequest("合成新任务", scope), "synthetic-analysis")
        val second = File(directory, "analysis-stale.zip")
        assertThrows(StaleSnapshotException::class.java) { DataTransfer(context, repository, clock).exportPeriodReport(second, next) }
        assertFalse(second.exists())
        assertCleanStaging()
    }

    @Test fun `review context not selected as supporting evidence is still exported with its original citation`() {
        repository.createRecord(RecordDraft("合成资料：记录了一次练习。", Occurrence(date)), "synthetic-source")
        val contextText = "合成补充上下文：是否复用这个方法仍待观察。"
        repository.createRecord(RecordDraft(contextText), "synthetic-context")
        publishKnowledge(additionalEvidence = listOf(EvidenceSelection("synthetic-context", "body")))
        val file = File(directory, "review-context.zip")
        DataTransfer(context, repository, clock).exportPeriodReport(file, scope)
        ZipFile(file).use { zip ->
            val content = contents(zip)
            val publication = JSONObject(content.entries.single { it.key.endsWith("/publication.json") }.value)
            val evidence = publication.getJSONArray("evidence")
            assertEquals(2, evidence.length())
            val supporting = publication.getJSONObject("review").getJSONArray("supportingEvidenceIds")
            assertEquals(1, supporting.length())
            val contextReceipt = (0 until evidence.length()).map { evidence.getJSONObject(it) }
                .single { it.getString("recordId") == "synthetic-context" }
            assertNotEquals(supporting.getString(0), contextReceipt.getString("id"))
            val exported = content.filterKeys { it.startsWith("knowledge/") && "/fragments/" in it }.values.map(::JSONObject)
            assertEquals(setOf("synthetic-source", "synthetic-context"), exported.map { it.getString("recordId") }.toSet())
            val actual = exported.filter { it.getString("recordId") == "synthetic-context" }.sortedBy { it.getInt("start") }
                .joinToString("") { it.getString("text") }
            assertEquals(contextText, actual)
            assertEquals(contextReceipt.getString("sha256"), hash(actual.toByteArray()))
            verifyInventory(content)
            verifyMarkdownLinks(content)
        }
        assertCleanStaging()
    }

    @Test fun `knowledge expiry invalidates report without a database write`() {
        repository.createRecord(RecordDraft("合成资料：练习", Occurrence(date)), "synthetic-source")
        publishKnowledge(clock.instant().plusSeconds(60))
        val report = PeriodReports(repository, clock).open(scope)
        val sourceGeneration = repository.currentGeneration()
        val analysisGeneration = repository.currentAnalysisGeneration()
        clock.now = clock.now.plusSeconds(60)
        val destination = File(directory, "expired.zip")
        assertThrows(StaleSnapshotException::class.java) { DataTransfer(context, repository, clock).exportPeriodReport(destination, report) }
        assertFalse(destination.exists())
        assertEquals(sourceGeneration, repository.currentGeneration())
        assertEquals(analysisGeneration, repository.currentAnalysisGeneration())
        assertCleanStaging()
    }

    @Test fun `empty scope remains explicit and export never overwrites a destination`() {
        val file = File(directory, "empty.zip")
        val transfer = DataTransfer(context, repository, clock)
        val result = transfer.exportPeriodReport(file, scope)
        assertEquals(0, result.records)
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { transfer.exportPeriodReport(file, scope) }
        assertArrayEquals(before, file.readBytes())
        ZipFile(file).use { zip ->
            val content = contents(zip)
            assertTrue(content.getValue("sections/action/index.md").contains("尚无对应模板回答"))
            verifyInventory(content)
            verifyMarkdownLinks(content)
        }
        assertCleanStaging()
    }

    private fun publishKnowledge(expiresAt: Instant? = null, additionalEvidence: List<EvidenceSelection> = emptyList()) {
        val analysis = ControlledAnalysis(repository, clock)
        analysis.start(AnalysisRequest("整理合成记录", scope, limits = AnalysisLimits(fragmentCodePoints = 2_048), expiresAt = expiresAt), "synthetic-run")
        analysis.runBatch("synthetic-run", "extract", listOf(EvidenceSelection("synthetic-source", "body")), object : AnalysisProvider {
            override val id = "synthetic-extractor"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
            override fun complete(request: ModelRequest): BatchProposal {
                val evidenceId = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim("合成陈述：用户记录了练习。", listOf(evidenceId), ClaimKind.REPORTED_EXPERIENCE)))
            }
        })
        val claim = analysis.claimRef("synthetic-run", "extract", 0)
        analysis.reviewClaim("synthetic-run", "review", claim, additionalEvidence, provider = object : SemanticReviewProvider {
            override val id = "synthetic-reviewer"
            override val contextWindowTokens = 100_000
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, request.payloadJson.toByteArray().size)
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                val evidenceId = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(evidenceId)))
            }
        })
        KnowledgeLibrary(repository, clock).publish("synthetic-run", claim, "review", KnowledgeCategory.OBSERVATION,
            analysis.task("synthetic-run").stored.version)
    }

    private fun contents(zip: ZipFile): Map<String, String> = zip.entries().asSequence().associate { entry ->
        entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() }
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
                val lines = leafText.lineSequence().filter { it.isNotBlank() }.toList()
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
    private fun verifyMarkdownLinks(content: Map<String, String>) {
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
    private class MutableClock(var now: Instant = Instant.parse("2026-10-17T00:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
