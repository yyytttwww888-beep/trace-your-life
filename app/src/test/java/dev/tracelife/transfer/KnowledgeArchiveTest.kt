package dev.tracelife.transfer

import dev.tracelife.model.Attachment
import dev.tracelife.model.Cadence
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RecordRevision
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.model.TemplateRef
import dev.tracelife.model.TemplateVersion
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipFile

/** Only explicitly synthetic life records and images are used here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KnowledgeArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-10-16T08:00:00Z")

    @Test
    fun `topic time and id routes reach one canonical latest record with its bound old template`() {
        val oldTemplate = template("synthetic-template", 1, "原问题")
        val newerTemplate = template("synthetic-template", 2, "后来修改的问题")
        val previous = record("synthetic-one", body = "合成资料：旧修订仅备份可用", topics = setOf("工作", "学习"))
        val current = previous.copy(revision = 2, draft = previous.draft.copy(
            body = "合成资料：当前唯一正文标记", template = TemplateRef(oldTemplate.id, 1), answers = mapOf("q1" to "合成资料：旧问题回答"),
        ))

        val (entries, result) = export(snapshot(listOf(previous, current), listOf(oldTemplate, newerTemplate)))
        val records = entries.filterKeys { it.startsWith("records/") && it.endsWith(".md") }
        val path = records.keys.single()
        assertEquals(1, result.records)
        assertEquals(1, entries.filterKeys { it.endsWith(".md") }.values.count { it.text().contains(current.draft.body) })
        assertFalse(entries.values.any { it.text().contains(previous.draft.body) })
        assertTrue(entries.keys.any { it.startsWith("reviews/daily/2026/10/2026-10-16/") })
        assertTrue(entries.keys.any { it.startsWith("reviews/weekly/2026/2026-W42/") })
        val topicPages = entries.filterKeys { it.startsWith("topics/") && it.endsWith(".md") }.mapValues { it.value.text() }
        assertEquals(2, topicPages.count { (source, text) -> markdownTargets(source, text).contains(path) })
        val mappings = idMappings(entries)
        assertEquals(path, mappings.single().getString("path"))
        assertEquals(2, mappings.single().getInt("revision"))
        val templateEntries = entries.filterKeys { it.startsWith("templates/") && it.contains("/v") }
        assertEquals(1, templateEntries.size)
        assertTrue(templateEntries.values.single().text().contains("原问题"))
        assertFalse(templateEntries.values.single().text().contains("后来修改的问题"))
        val machine = machineRecords(entries).single()
        assertEquals(current.draft.body, machine.getString("body"))
        assertEquals(1, machine.getJSONObject("template").getInt("version"))
        assertEquals("合成资料：旧问题回答", machine.getJSONObject("answers").getString("q1"))
        assertTrue(reachable(entries).contains(path))
        assertGeneratedNavigation(entries)
    }

    @Test
    fun `scope uses overlapping intervals OR topics and reports excluded unknown dates`() {
        val records = listOf(
            record("interval", start = "2026-09-01", end = "2026-11-02", topics = setOf("睡眠")),
            record("work", topics = setOf("工作")),
            record("outside", start = "2026-08-01", topics = setOf("睡眠")),
            record("unknown", start = null, topics = setOf("睡眠")),
            record("archived", topics = setOf("工作"), archived = true),
            record("other", topics = setOf("运动")),
        )
        val options = ReadingExportOptions(
            scope = ExportScope(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), setOf("睡眠", "工作"), includeUnknown = false, includeArchived = false),
        )
        val (entries, _) = export(snapshot(records), options)

        assertEquals(setOf("interval", "work"), machineRecords(entries).map { it.getString("id") }.toSet())
        val manifest = JSONObject(entries.getValue("manifest.json").text())
        val coverage = manifest.getJSONObject("coverage")
        assertEquals(6, coverage.getInt("sourceCurrentRecords"))
        assertEquals(2, coverage.getInt("includedRecords"))
        assertEquals(4, coverage.getInt("outOfScopeRecords"))
        assertEquals(1, coverage.getInt("unknownTimeEligible"))
        assertEquals(0, coverage.getInt("unknownTimeIncluded"))
        assertEquals(1, coverage.getJSONObject("excludedByReason").getInt("unknownTime"))
        assertEquals("OR", manifest.getJSONObject("scope").getString("topicOperator"))
        assertFalse(manifest.getBoolean("restorable"))
        assertTrue(entries.getValue("timeline/intervals/index.md").text().contains("2026-09-01 至 2026-11-02"))
    }

    @Test
    fun `unknown and unclassified records stay reachable without fabricated dated reviews`() {
        val (entries, _) = export(snapshot(listOf(record("unknown", start = null, body = "合成资料：无法确定发生日期"))))
        val recordPath = entries.keys.single { it.startsWith("records/") }

        assertTrue(recordPath.startsWith("records/unknown/"))
        assertTrue(markdownTargets("timeline/unknown/index.md", entries.getValue("timeline/unknown/index.md").text()).contains(recordPath))
        assertTrue(markdownTargets("topics/unclassified/index.md", entries.getValue("topics/unclassified/index.md").text()).contains(recordPath))
        assertFalse(entries.keys.any { it.startsWith("reviews/") })
        assertEquals(JSONObject.NULL, machineRecords(entries).single().getJSONObject("occurrence").get("startDate"))
        assertEquals(1, JSONObject(entries.getValue("manifest.json").text()).getJSONObject("coverage").getInt("unknownTimeIncluded"))
    }

    @Test
    fun `all navigation families and id mappings paginate within their declared page size`() {
        val shardIds = (0..1000).map { "synthetic-shard-$it" }.groupBy { sha(it.toByteArray()).take(2) }.values.first { it.size >= 3 }.take(3)
        val records = (0..25).map { index ->
            val date = LocalDate.of(2025, 1, 16).plusMonths(index.toLong()).toString()
            record("synthetic-$index", start = date, topics = setOf("合成主题 $index"))
        } + shardIds.map { record(it, topics = setOf("共同主题")) }
        val (entries, _) = export(snapshot(records), ReadingExportOptions(pageSize = 2))

        listOf("topics/page-002.md", "timeline/2025/page-002.md", "indexes/evidence/page-002.md", "indexes/ids/page-002.json", "inventory/page-002.json").forEach { assertTrue(it, it in entries) }
        entries.filterKeys { it.endsWith(".md") && !it.startsWith("records/") && !it.startsWith("templates/") && it != "README.md" }.forEach { (_, bytes) ->
            assertTrue(bytes.text(), bytes.text().lineSequence().count { it.startsWith("- ") } <= 2)
        }
        entries.filterKeys { it.endsWith(".json") && it != "manifest.json" }.forEach { (_, bytes) ->
            assertTrue(JSONObject(bytes.text()).getJSONArray("entries").length() <= 2)
        }
        entries.filterKeys { it.startsWith("indexes/ids/") && it.endsWith(".jsonl") }.forEach { (_, bytes) ->
            assertTrue(bytes.text().lineSequence().filter { it.isNotBlank() }.count() <= 2)
        }
        assertEquals(records.size, idMappings(entries).size)
        assertEquals(records.size, machineRecords(entries).size)
        assertTrue(entries.keys.any { it.startsWith("indexes/ids/${sha(shardIds.first().toByteArray()).take(2)}/records-002") })
        assertGeneratedNavigation(entries)
        assertEquals(entries.keys.filter { it.startsWith("records/") }.toSet(), reachable(entries).filter { it.startsWith("records/") }.toSet())
    }

    @Test
    fun `millennia long interval creates bounded navigation and keeps uncertainty explicit`() {
        val record = record("long-interval", start = "0001-01-01", end = "9999-12-31")
        val (entries, _) = export(snapshot(listOf(record)), ReadingExportOptions(
            kind = ReadingPackageKind.EVIDENCE, scope = ExportScope(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")),
            question = "合成问题：本月需要注意什么？",
        ))

        assertTrue(entries.size < 80)
        assertFalse(entries.keys.any { it.startsWith("timeline/9999/") })
        assertTrue(entries.getValue("timeline/intervals/index.md").text().contains("0001-01-01 至 9999-12-31"))
        val manifest = JSONObject(entries.getValue("manifest.json").text())
        assertEquals("evidence", manifest.getString("kind"))
        assertEquals("合成问题：本月需要注意什么？", manifest.getString("question"))
        assertFalse(manifest.getBoolean("restorable"))
        assertFalse(manifest.getJSONObject("coverage").getBoolean("aiAnalysisPerformed"))
        assertFalse(entries.keys.any { it.startsWith("profile/") || it == "overview.md" || it == "metrics.md" })
    }

    @Test
    fun `unsafe names are never paths or active index markdown and raw body cannot escape its fence`() {
        val unsafeId = "../../outside](../../escape)"
        val unsafeTopic = "[恶意](../../escape) <script>synthetic()</script>"
        val raw = "合成原文\n```\n# 伪造标题\n[外链](../../escape)\n~~~\n<script>synthetic()</script>"
        val (entries, _) = export(snapshot(listOf(record(unsafeId, body = raw, topics = setOf(unsafeTopic)))))

        assertTrue(entries.keys.all { !it.startsWith('/') && it.split('/').none { segment -> segment == ".." } })
        assertFalse(entries.keys.any { "outside" in it || "escape" in it || "<" in it })
        val topic = entries.getValue("topics/index.md").text()
        assertFalse(topic.contains("[恶意](../../escape)"))
        assertFalse(topic.contains("<script>"))
        assertTrue(topic.contains("&#91;"))
        val canonical = entries.filterKeys { it.startsWith("records/") }.values.single().text()
        assertTrue(canonical.contains("````\n$raw\n````"))
        val machine = machineRecords(entries).single()
        assertEquals(unsafeId, machine.getString("id"))
        assertEquals(raw, machine.getString("body"))
        assertEquals(unsafeTopic, machine.getJSONArray("topics").getString(0))
        assertGeneratedNavigation(entries)
    }

    @Test
    fun `attachment omission and inclusion are explicit and included bytes are verified`() {
        val bytes = "synthetic-image-bytes".toByteArray()
        val attachment = Attachment("11111111-1111-4111-8111-111111111111", "合成图片](bad).png", "image/png", bytes.size.toLong(), sha(bytes))
        val draft = record("with-image").let { it.copy(draft = it.draft.copy(attachmentIds = setOf(attachment.id))) }
        val source = snapshot(listOf(draft), attachments = listOf(attachment))
        val directory = temporary.newFolder("images")
        val omittedFile = File(temporary.root, "omitted.zip")
        KnowledgeArchive.write(source, directory, omittedFile, ReadingExportOptions())
        val omitted = readArchive(omittedFile)
        assertFalse(omitted.keys.any { it.startsWith("attachments/") })
        assertEquals(1, JSONObject(omitted.getValue("manifest.json").text()).getJSONObject("completeness").getInt("omittedSelectedAttachments"))
        assertTrue(omitted.filterKeys { it.startsWith("records/") }.values.single().text().contains("文件未包含"))

        File(directory, "${attachment.id}.blob").writeBytes(bytes)
        val includedFile = File(temporary.root, "included.zip")
        val result = KnowledgeArchive.write(source, directory, includedFile, ReadingExportOptions(includeAttachments = true))
        val included = readArchive(includedFile)
        assertArrayEquals(bytes, included.getValue("attachments/${attachment.id}.blob"))
        assertEquals(1, result.attachments)
        assertEquals(0, JSONObject(included.getValue("manifest.json").text()).getJSONObject("completeness").getInt("omittedSelectedAttachments"))
        assertGeneratedNavigation(included)
    }

    @Test
    fun `valid non UUID attachment identity survives backup roundtrip and reading export`() {
        val bytes = "synthetic-restored-image".toByteArray()
        val attachment = Attachment("image-one", "合成恢复图片.png", "image/png", bytes.size.toLong(), sha(bytes))
        val record = record("restored-image-record").let { it.copy(draft = it.draft.copy(attachmentIds = setOf(attachment.id))) }
        val directory = temporary.newFolder("non-uuid-source")
        File(directory, "${attachment.id}.blob").writeBytes(bytes)
        val backup = File(temporary.root, "non-uuid.tlb")
        val restoredDirectory = File(temporary.root, "non-uuid-restored")
        val password = "synthetic-test-password".toCharArray()
        val restored = try {
            BackupArchive.write(snapshot(listOf(record), attachments = listOf(attachment)), directory, backup, password)
            BackupArchive.read(backup, restoredDirectory, password)
        } finally { password.fill('\u0000') }

        val reading = File(temporary.root, "non-uuid-reading.zip")
        KnowledgeArchive.write(restored, restoredDirectory, reading, ReadingExportOptions(includeAttachments = true))
        val entries = readArchive(reading)
        assertArrayEquals(bytes, entries.getValue("attachments/image-one.blob"))
        assertEquals("image-one", machineRecords(entries).single().getJSONArray("attachmentIds").getString(0))
        assertGeneratedNavigation(entries)
    }

    @Test
    fun `checksum failure deletes partial zip and unsafe attachment identity is rejected`() {
        val bytes = "synthetic-image-bytes".toByteArray()
        val attachment = Attachment("22222222-2222-4222-8222-222222222222", "合成图片", "image/png", bytes.size.toLong(), "0".repeat(64))
        val directory = temporary.newFolder("corrupt-images")
        File(directory, "${attachment.id}.blob").writeBytes(bytes)
        val record = record("corrupt").let { it.copy(draft = it.draft.copy(attachmentIds = setOf(attachment.id))) }
        val destination = File(temporary.root, "failed.zip")
        expectFailure("校验失败") { KnowledgeArchive.write(snapshot(listOf(record), attachments = listOf(attachment)), directory, destination, ReadingExportOptions(includeAttachments = true)) }
        assertFalse(destination.exists())

        val unsafe = attachment.copy(id = "../outside")
        val unsafeRecord = record.copy(draft = record.draft.copy(attachmentIds = setOf(unsafe.id)))
        expectFailure("附件文件标识无效") { KnowledgeArchive.write(snapshot(listOf(unsafeRecord), attachments = listOf(unsafe)), directory, destination, ReadingExportOptions()) }
        assertFalse(destination.exists())
    }

    @Test
    fun `manifest inventory hash chain covers each payload and id mapping resolves included revision`() {
        val (entries, _) = export(snapshot((1..8).map { record("synthetic-$it") }), ReadingExportOptions(pageSize = 2))
        val inventory = JSONObject(entries.getValue("manifest.json").text()).getJSONObject("inventory")
        var next: String? = inventory.getString("path")
        var expectedHash = inventory.getString("sha256")
        val covered = mutableSetOf<String>()
        while (next != null) {
            val bytes = entries.getValue(next)
            assertEquals(expectedHash, sha(bytes))
            val index = JSONObject(bytes.text())
            val pages = index.getJSONArray("entries")
            for (i in 0 until pages.length()) {
                val page = pages.getJSONObject(i)
                val pageBytes = entries.getValue(page.getString("path"))
                assertEquals(page.getString("sha256"), sha(pageBytes))
                pageBytes.text().lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val file = JSONObject(line)
                    val content = entries.getValue(file.getString("path"))
                    assertEquals(file.getString("sha256"), sha(content))
                    assertEquals(file.getLong("bytes"), content.size.toLong())
                    assertTrue(covered.add(file.getString("path")))
                }
            }
            next = if (index.isNull("next")) null else index.getString("next")
            if (next != null) expectedHash = index.getString("nextSha256")
        }
        assertEquals(entries.keys.filterNot { it.startsWith("inventory/") || it == "manifest.json" }.toSet(), covered)
        idMappings(entries).forEach { mapping ->
            assertTrue(entries.getValue(mapping.getString("path")).text().contains("修订：${mapping.getInt("revision")}"))
        }
    }

    @Test
    fun `deleted records never appear and missing template rejects export rather than dropping answers`() {
        val deleted = record("deleted", body = "合成资料：已删除原文不能重新导出")
        val source = snapshot(listOf(deleted, record("kept"))).copy(deletedRecords = listOf(Deletion(deleted.id, 1, now)))
        val (entries, _) = export(source)
        assertEquals(listOf("kept"), machineRecords(entries).map { it.getString("id") })
        assertFalse(entries.values.any { it.text().contains(deleted.draft.body) })

        val broken = record("broken").let { it.copy(draft = it.draft.copy(template = TemplateRef("missing", 1), answers = mapOf("q1" to "合成答案"))) }
        val destination = File(temporary.root, "broken.zip")
        expectFailure("模板版本缺失") { KnowledgeArchive.write(snapshot(listOf(broken)), temporary.root, destination, ReadingExportOptions()) }
        assertFalse(destination.exists())
    }

    @Test
    fun `resource limit fails explicitly while an existing destination remains untouched`() {
        val destination = File(temporary.root, "existing.zip").apply { writeText("synthetic-sentinel") }
        expectFailure("不能覆盖") { KnowledgeArchive.write(snapshot(emptyList()), temporary.root, destination, ReadingExportOptions()) }
        assertEquals("synthetic-sentinel", destination.readText())
        val oversized = (0..KnowledgeArchive.MAX_CURRENT_RECORDS).map { record("synthetic-$it") }
        expectFailure("10000") { KnowledgeArchive.write(snapshot(oversized), temporary.root, File(temporary.root, "too-large.zip"), ReadingExportOptions()) }
    }

    @Test
    fun `source revision limit matches storage and remains separate from selected record limit`() {
        val original = record("long-history", topics = setOf("学习"))
        val history = (1..50_001).map { original.copy(revision = it) }
        val (entries, result) = export(snapshot(history), ReadingExportOptions(scope = ExportScope(topics = setOf("学习"))))
        assertEquals(1, result.records)
        assertEquals(50_001, machineRecords(entries).single().getInt("revision"))
        assertEquals(50_000, JSONObject(entries.getValue("manifest.json").text()).getJSONObject("completeness").getInt("omittedHistoricalRevisions"))

        val sourceTooLarge = List(KnowledgeArchive.MAX_SOURCE_REVISIONS + 1) { original }
        expectFailure("范围筛选不能绕过此上限") {
            KnowledgeArchive.write(snapshot(sourceTooLarge), temporary.root, File(temporary.root, "source-too-large.zip"),
                ReadingExportOptions(scope = ExportScope(topics = setOf("不会匹配任何记录"))))
        }
    }

    @Test
    fun `empty package has valid bounded navigation and reports no fabricated content`() {
        val (entries, result) = export(snapshot(emptyList()), ReadingExportOptions(pageSize = 1))
        assertEquals(0, result.records)
        assertTrue(entries.size < 150)
        assertTrue(machineRecords(entries).isEmpty())
        assertTrue(idMappings(entries).isEmpty())
        assertGeneratedNavigation(entries)
        val manifest = JSONObject(entries.getValue("manifest.json").text())
        assertFalse(manifest.getBoolean("restorable"))
        assertFalse(manifest.getJSONObject("completeness").getBoolean("historicalRecordRevisionsIncluded"))
        assertEquals("unknown", manifest.getJSONObject("coverage").getString("lifeCoverage"))
    }

    private fun record(id: String, start: String? = "2026-10-16", end: String? = start, body: String = "合成资料：$id", topics: Set<String> = emptySet(), archived: Boolean = false) =
        RecordRevision(id, 1, RecordDraft(body, Occurrence(start?.let(LocalDate::parse), end?.let(LocalDate::parse), "UTC"), topics), now, now, archived)

    private fun template(id: String, version: Int, prompt: String) = TemplateVersion(
        id, version, TemplateDraft("合成模板", Cadence.DAILY, questions = listOf(TemplateQuestion("q1", prompt))), now,
    )

    private fun snapshot(records: List<RecordRevision>, templates: List<TemplateVersion> = emptyList(), attachments: List<Attachment> = emptyList()) =
        RepositorySnapshot("synthetic-snapshot", "synthetic-store", 7, now, records, templates, attachments, emptyList(), emptyList(), emptyList())

    private fun export(source: RepositorySnapshot, options: ReadingExportOptions = ReadingExportOptions()): Pair<Map<String, ByteArray>, ExportResult> {
        val destination = File(temporary.root, "reading-${UUID.randomUUID()}.zip")
        val result = KnowledgeArchive.write(source, temporary.root, destination, options)
        return readArchive(destination) to result
    }

    private fun readArchive(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
    }

    private fun machineRecords(entries: Map<String, ByteArray>) = entries.filterKeys { it.startsWith("data/records/") && it.endsWith(".jsonl") }
        .values.flatMap { it.text().lineSequence().filter(String::isNotBlank).map(::JSONObject).toList() }

    private fun idMappings(entries: Map<String, ByteArray>) = entries.filterKeys { it.startsWith("indexes/ids/") && it.endsWith(".jsonl") }
        .values.flatMap { it.text().lineSequence().filter(String::isNotBlank).map(::JSONObject).toList() }

    private fun markdownTargets(source: String, text: String): Set<String> = Regex("\\[[^\\]\\n]*]\\(([^)]+)\\)").findAll(text).map { match ->
        (Paths.get(source).parent ?: Paths.get("")).resolve(match.groupValues[1]).normalize().toString().replace('\\', '/')
    }.toSet()

    private fun assertGeneratedNavigation(entries: Map<String, ByteArray>) {
        entries.filterKeys { it.endsWith(".md") && !it.startsWith("records/") && !it.contains("/v") }.forEach { (source, bytes) ->
            markdownTargets(source, bytes.text()).forEach { target -> assertTrue("$source -> $target", target in entries) }
        }
        idMappings(entries).forEach { assertNotNull(entries[it.getString("path")]) }
    }

    private fun reachable(entries: Map<String, ByteArray>): Set<String> {
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque(listOf("README.md"))
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            val bytes = entries.getValue(current)
            val targets = if (current.endsWith(".md") && !current.startsWith("records/") && !current.contains("/v")) markdownTargets(current, bytes.text()) else emptySet()
            targets.forEach { if (it !in visited) pending.add(it) }
        }
        return visited
    }

    private fun expectFailure(message: String, block: () -> Unit) {
        try { block(); fail("应拒绝不完整的合成资料") } catch (failure: IllegalArgumentException) {
            assertTrue("实际错误：${failure.message}", failure.message.orEmpty().contains(message))
        }
    }

    private fun ByteArray.text() = toString(Charsets.UTF_8)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
