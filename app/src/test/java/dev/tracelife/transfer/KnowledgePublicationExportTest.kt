package dev.tracelife.transfer

import dev.tracelife.analysis.AnalysisCall
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRunState
import dev.tracelife.analysis.BatchProposal
import dev.tracelife.analysis.CallPurpose
import dev.tracelife.analysis.CallStatus
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ClaimRef
import dev.tracelife.analysis.ClaimReview
import dev.tracelife.analysis.EvidenceReceipt
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RecordRevision
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.KnowledgeHead
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.SourceStamp
import dev.tracelife.storage.StoredAnalysisRun
import dev.tracelife.storage.StoredClaimFeedback
import dev.tracelife.storage.StoredKnowledgeRevision
import dev.tracelife.knowledge.StoredExperienceThread
import dev.tracelife.knowledge.StoredExperienceChange
import dev.tracelife.knowledge.ExperienceRelation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipFile

/** All source records, model claims and user feedback are explicitly synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KnowledgePublicationExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-10-17T08:00:00Z")
    private val stamp = SourceStamp("synthetic-store", 7)
    private val scope = AnalysisQuery(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), "UTC")

    @Test fun `published knowledge has one canonical body separate from evidence and no copied user text`() {
        val record = record("synthetic-record")
        val note = note("synthetic-run", listOf(record), feedbackVersion = 1)
        val marker = "合成独立用户文字：不应被知识包复制"
        val source = snapshot(listOf(record), listOf(note)).copy(claimFeedback = listOf(feedback(note, userText = marker)))
        val entries = export(source)
        val path = entries.keys.single { it.startsWith("notes/entries/") }
        val original = entries.keys.single { it.startsWith("records/") }
        val body = entries.getValue(path)
        assertTrue(body.contains("非独立原始证据"))
        assertTrue(body.contains("CONFIRMED"))
        assertTrue(body.contains("REPORTED_EXPERIENCE"))
        assertTrue(body.contains("[0, ${record.draft.body.codePointCount(0, record.draft.body.length)})"))
        assertTrue(markdownTargets(path, body).contains(original))
        val related = markdownTargets(original, entries.getValue(original)).single { it.startsWith("notes/sources/") }
        assertTrue(markdownTargets(related, entries.getValue(related)).contains(path))
        assertTrue(entries.values.none { marker in it })
        assertTrue(entries.filterKeys { it.startsWith("indexes/evidence/") }.values.none { note.id in it })
        assertEquals(1, entries.filterKeys { it.endsWith(".md") }.values.count { note.claim.text in it })
        val machine = machineNotes(entries).single()
        assertEquals("saved-analysis", machine.getString("nodeType"))
        assertFalse(machine.getBoolean("originalEvidence"))
        assertFalse(machine.has("userText"))
        assertEquals("CONFIRMED", machine.getString("feedbackDecision"))
        val manifest = JSONObject(entries.getValue("manifest.json"))
        assertEquals(11, manifest.getLong("analysisGeneration"))
        assertEquals("historical_snapshot_at_capturedAt", manifest.getString("validity"))
        assertEquals(1, manifest.getJSONObject("knowledge").getInt("included"))
        assertFalse(manifest.getJSONObject("completeness").getBoolean("analysisRunsIncluded"))
        assertLinks(entries)
    }

    @Test fun `stale expired feedback changed rejected withdrawn and unconfirmed insight heads stay out`() {
        val record = record("synthetic-record")
        val stale = note("stale", listOf(record)).copy(source = stamp.copy(generation = 6))
        val expired = note("expired", listOf(record)).copy(expiresAt = now)
        val changed = note("changed", listOf(record), feedbackVersion = 1)
        val rejected = note("rejected", listOf(record), feedbackVersion = 1)
        val withdrawn = note("withdrawn", listOf(record), feedbackVersion = 1)
        val corrected = note("corrected", listOf(record), feedbackVersion = 1)
        val insight = note("insight", listOf(record), feedbackVersion = 1, category = KnowledgeCategory.INSIGHT)
        val deletedFeedback = note("deleted-feedback", listOf(record), feedbackVersion = 1)
        val notes = listOf(stale, expired, changed, rejected, withdrawn, corrected, insight, deletedFeedback)
        val responses = listOf(feedback(changed).copy(version = 2), feedback(rejected, ClaimFeedbackDecision.REJECTED),
            feedback(withdrawn, ClaimFeedbackDecision.WITHDRAWN), feedback(corrected, ClaimFeedbackDecision.CORRECTED, "合成修正"))
        val source = snapshot(listOf(record), notes).copy(claimFeedback = responses,
            deletedClaimFeedback = listOf(Deletion(AnalysisRunCodec.feedbackId(deletedFeedback.runId, deletedFeedback.claimRef), 1, now)))
        val entries = export(source)
        assertFalse(entries.keys.any { it.startsWith("notes/") })
        assertTrue(notes.all { note -> entries.values.none { note.claim.text in it } })
        val status = JSONObject(entries.getValue("manifest.json")).getJSONObject("knowledge")
        assertEquals(8, status.getInt("omitted"))
        val reasons = status.getJSONObject("omittedByReason")
        assertEquals(1, reasons.getInt("stale_source"))
        assertEquals(1, reasons.getInt("expired"))
        assertEquals(2, reasons.getInt("feedback_changed"))
        assertEquals(3, reasons.getInt("feedback_not_accepted"))
        assertEquals(1, reasons.getInt("feedback_deleted"))
    }

    @Test fun `all reviewed context must be in export scope even if the claim cites only one record`() {
        val learning = record("synthetic-learning", topics = setOf("学习"))
        val other = record("synthetic-other", topics = setOf("工作"))
        val note = note("synthetic-scope", listOf(learning, other))
        val entries = export(snapshot(listOf(learning, other), listOf(note)),
            ReadingExportOptions(scope = ExportScope(topics = setOf("学习"))))
        assertFalse(entries.keys.any { it.startsWith("notes/") })
        assertTrue(entries.values.none { note.claim.text in it || other.draft.body in it })
        assertEquals(1, JSONObject(entries.getValue("manifest.json")).getJSONObject("knowledge")
            .getJSONObject("omittedByReason").getInt("evidence_out_of_scope"))
        assertEquals(1, entries.keys.count { it.startsWith("records/") })
    }

    @Test fun `evidence package omits valid published analyses while preserving source navigation`() {
        val record = record("synthetic-original")
        val note = note("synthetic-evidence", listOf(record))
        val entries = export(snapshot(listOf(record), listOf(note)), ReadingExportOptions(
            kind = ReadingPackageKind.EVIDENCE, question = "合成问题：原始记录有哪些？"))
        assertFalse(entries.keys.any { it.startsWith("notes/") || it.startsWith("indexes/note-ids/") || it.startsWith("data/knowledge/") })
        assertTrue(entries.values.none { note.claim.text in it })
        assertTrue(entries.keys.any { it.startsWith("records/") })
        assertTrue(entries.getValue("README.md").contains("所有已发布知识均省略"))
        assertEquals(1, JSONObject(entries.getValue("manifest.json")).getJSONObject("knowledge")
            .getJSONObject("omittedByReason").getInt("evidence_package"))
        assertLinks(entries)
    }

    @Test fun `later review invalidates older publication without a source or feedback change`() {
        val record = record("synthetic-reviewed-again")
        val note = note("synthetic-later-review", listOf(record))
        val extraction = AnalysisCall("extract", 1, "synthetic", "c".repeat(64), 100, 20, note.evidence,
            now.minusSeconds(90), CallStatus.SUCCEEDED, finishedAt = now.minusSeconds(90), proposal = BatchProposal(listOf(note.claim)))
        val originalReview = AnalysisCall("review", 1, "synthetic", note.reviewRequestSha256, 100, 20, note.evidence,
            now.minusSeconds(70), CallStatus.SUCCEEDED, finishedAt = now.minusSeconds(70), purpose = CallPurpose.REVIEW,
            reviewTarget = note.claimRef, review = note.review)
        val laterReview = originalReview.copy(batchKey = "review-later", requestSha256 = "b".repeat(64),
            startedAt = now.minusSeconds(30), finishedAt = now.minusSeconds(30),
            review = ClaimReview(ReviewVerdict.CONTRADICTED, contradictingEvidenceIds = listOf(note.evidence.first().id)))
        val run = StoredAnalysisRun(note.runId, 1, stamp, now.minusSeconds(90), now.minusSeconds(30), null,
            AnalysisRunCodec.encode(AnalysisRunState(AnalysisRequest("合成二次核对", scope), listOf(extraction, originalReview, laterReview))))
        val entries = export(snapshot(listOf(record), listOf(note)).copy(analysisRuns = listOf(run)))
        assertTrue(entries.values.none { note.claim.text in it })
        assertFalse(entries.keys.any { it.startsWith("notes/") })
        assertEquals(1, JSONObject(entries.getValue("manifest.json")).getJSONObject("knowledge")
            .getJSONObject("omittedByReason").getInt("review_changed"))
    }

    @Test fun `changed or deleted original revisions cannot leak derived wording and corrupt proof fails closed`() {
        val record = record("synthetic-original")
        val note = note("synthetic-history", listOf(record))
        val changed = record.copy(revision = 2, draft = record.draft.copy(body = "合成修订：已更正"))
        val revisedEntries = export(snapshot(listOf(record, changed), listOf(note)))
        assertTrue(revisedEntries.values.none { note.claim.text in it })
        assertEquals(1, JSONObject(revisedEntries.getValue("manifest.json")).getJSONObject("knowledge")
            .getJSONObject("omittedByReason").getInt("evidence_not_current"))
        val deletedEntries = export(snapshot(listOf(record), listOf(note)).copy(deletedRecords = listOf(Deletion(record.id, 1, now))))
        assertTrue(deletedEntries.values.none { note.claim.text in it || record.draft.body in it })

        val corrupt = record.copy(draft = record.draft.copy(body = "同一修订被非法改写的合成资料"))
        val destination = File(temporary.root, "corrupt.zip")
        try {
            KnowledgeArchive.write(snapshot(listOf(corrupt), listOf(note)), temporary.root, destination, ReadingExportOptions())
            fail("corrupt original proof must not be exported")
        } catch (_: IllegalArgumentException) { assertFalse(destination.exists()) }
    }

    @Test fun `knowledge routes paginate while one immutable current revision is shared by all entrances`() {
        val records = (0..13).map { index -> record("synthetic-$index", LocalDate.of(2025, 1, 16).plusMonths(index.toLong()), topics = setOf("合成主题 $index")) }
        val notes = records.mapIndexed { index, record ->
            note("synthetic-page-$index", listOf(record), topics = record.draft.topics,
                query = scope.copy(from = record.draft.occurrence.startDate!!, through = record.draft.occurrence.endDate!!.plusDays(1)))
        }
        val first = notes.first()
        val latest = first.copy(revision = 2, publishedAt = now.minusSeconds(10))
        val source = snapshot(records, notes + latest)
        val entries = export(source, ReadingExportOptions(pageSize = 2))
        assertEquals(14, machineNotes(entries).size)
        assertEquals(14, entries.keys.count { it.startsWith("notes/entries/") })
        assertTrue(entries.keys.any { it == "notes/all/page-002.md" })
        assertTrue(entries.keys.any { it == "notes/topics/page-002.md" })
        assertTrue(entries.keys.any { it == "notes/timeline/2025/page-002.md" })
        assertTrue(entries.keys.any { it == "indexes/note-ids/page-002.json" })
        assertFalse(entries.keys.any { it.endsWith("${AnalysisRunCodec.sha256(first.id)}-r1.md") })
        assertEquals(1, JSONObject(entries.getValue("manifest.json")).getJSONObject("completeness").getInt("omittedHistoricalKnowledgeRevisions"))
        entries.filterKeys { it.startsWith("notes/") && !it.startsWith("notes/entries/") }.forEach { (_, text) ->
            assertTrue(text.lineSequence().count { it.startsWith("- ") } <= 2)
        }
        entries.filterKeys { it.startsWith("indexes/note-ids/") && it.endsWith(".json") }.forEach { (_, text) ->
            assertTrue(JSONObject(text).getJSONArray("entries").length() <= 2)
        }
        val mappings = entries.filterKeys { it.startsWith("indexes/note-ids/") && it.endsWith(".jsonl") }.values
            .flatMap { it.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList() }
        assertEquals(notes.map { it.id }.toSet(), mappings.map { it.getString("id") }.toSet())
        assertEquals(2, mappings.single { it.getString("id") == first.id }.getInt("revision"))
        assertLinks(entries)
    }

    @Test fun `unknown source dates and long analysis scopes remain explicit without exploding time indices`() {
        val record = record("synthetic-unknown", date = null)
        val note = note("synthetic-long-range", listOf(record), query = scope.copy(from = LocalDate.of(1, 1, 1), through = LocalDate.of(9999, 12, 31)))
        val entries = export(snapshot(listOf(record), listOf(note)))
        assertTrue(entries.size < 100)
        assertTrue(entries.keys.any { it.startsWith("records/unknown/") })
        assertTrue(entries.keys.any { it.startsWith("notes/timeline/0001/01/") })
        assertFalse(entries.keys.any { it.startsWith("notes/timeline/9999/") })
        val body = entries.filterKeys { it.startsWith("notes/entries/") }.values.single()
        assertTrue(body.contains("0001-01-01 至 9999-12-31"))
        assertTrue(entries.getValue("notes/timeline/index.md").contains("不以发布时间代替"))
        assertLinks(entries)
    }

    @Test fun `unknowns from other records in the extraction batch never enter the publication or reading package`() {
        val record = record("synthetic-selected")
        val other = record("synthetic-unrelated", topics = setOf("工作"))
        val original = note("synthetic-batch", listOf(record))
        val marker = "合成同批其他记录未知项：不得通过条目跨范围泄漏"
        val extraction = AnalysisCall("extract", 1, "synthetic", "a".repeat(64), 100, 20,
            listOf(receipt(record), receipt(other)), now.minusSeconds(60), CallStatus.SUCCEEDED,
            finishedAt = now.minusSeconds(60), proposal = BatchProposal(listOf(original.claim), listOf(marker)))
        val review = AnalysisCall("review", 1, "synthetic", "b".repeat(64), 100, 20,
            original.evidence, now.minusSeconds(50), CallStatus.SUCCEEDED, finishedAt = now.minusSeconds(50),
            purpose = CallPurpose.REVIEW, reviewTarget = original.claimRef, review = original.review)
        val run = StoredAnalysisRun(original.runId, 1, stamp, now.minusSeconds(60), now.minusSeconds(50), null,
            AnalysisRunCodec.encode(AnalysisRunState(AnalysisRequest("合成整理", scope), listOf(extraction, review))))
        val published = KnowledgeValidation.fromRun(run, original.claimRef, "review", KnowledgeCategory.KNOWLEDGE_NOTE,
            setOf("学习"), 1, now.minusSeconds(40), now.minusSeconds(40))
        val source = snapshot(listOf(record, other), listOf(published)).copy(analysisRuns = listOf(run))
        val entries = export(source, ReadingExportOptions(scope = ExportScope(topics = setOf("学习"))))
        assertEquals(1, machineNotes(entries).size)
        assertTrue(entries.values.none { marker in it })
        assertFalse(machineNotes(entries).single().has("extractionUnknowns"))
    }

    @Test fun `experience exports page changes link canonical knowledge and preserve scope gaps without fallback`() {
        val records = (1..4).map { record("synthetic-experience-$it") }
        val notes = records.mapIndexed { i, record -> note("synthetic-experience-run-$i", listOf(record)) }
        val thread = StoredExperienceThread("synthetic-thread", 4, "合成经验：理解逐步变化", setOf("学习"), now.minusSeconds(40), now.minusSeconds(10))
        val relations = listOf(ExperienceRelation.INITIAL, ExperienceRelation.SUPPLEMENT, ExperienceRelation.EXCEPTION, ExperienceRelation.NARROW)
        val changes = notes.mapIndexed { i, note -> StoredExperienceChange(thread.id, i + 1, note.id, note.revision,
            relations[i], "合成用户说明-$i", now.minusSeconds(40 - i * 10L)) }
        val source = snapshot(records, notes).copy(experienceThreads = listOf(thread), experienceChanges = changes)
        val entries = export(source, ReadingExportOptions(pageSize = 2))
        assertTrue(entries.keys.any { it.startsWith("experiences/entries/") && it.endsWith("changes/page-002.md") })
        assertEquals(4, entries.keys.count { it.startsWith("experiences/entries/") && Regex("changes/[1-4]\\.md$").containsMatchIn(it) })
        notes.forEach { note -> assertEquals(1, entries.filterKeys { it.endsWith(".md") }.values.count { note.claim.text in it }) }
        assertTrue(entries.filterKeys { it.matches(Regex("indexes/(index|page-\\d+)\\.md")) }.values.any { it.contains("经验线索") })
        assertTrue(entries.keys.any { it.startsWith("indexes/experience-ids/") && it.endsWith(".jsonl") })
        assertLinks(entries)

        val limited = export(source.copy(knowledgeRevisions = notes.dropLast(1) + notes.last().copy(source = stamp.copy(generation = 6))), ReadingExportOptions(pageSize = 2))
        val head = limited.filterKeys { it.endsWith("thread.json") }.values.single().let(::JSONObject)
        assertEquals(4, head.getInt("adoptedSequence"))
        assertFalse(head.getBoolean("adoptedIncluded"))
        assertTrue(limited.values.none { changes.last().userNote!! in it || notes.last().claim.text in it })
        assertTrue(limited.values.any { "不能以更早知识替代" in it })
        assertEquals(1, JSONObject(limited.getValue("manifest.json")).getJSONObject("experiences").getInt("omittedChanges"))
        assertLinks(limited)
    }

    @Test fun `evidence packages and excluded knowledge cannot leak experience names or independent explanations`() {
        val record = record("synthetic-experience-private", topics = setOf("工作"))
        val note = note("synthetic-experience-private-run", listOf(record), topics = setOf("工作"))
        val thread = StoredExperienceThread("synthetic-private-thread", 1, "合成独立名称不可越界", setOf("工作"), now.minusSeconds(20), now.minusSeconds(20))
        val change = StoredExperienceChange(thread.id, 1, note.id, 1, ExperienceRelation.INITIAL, "合成独立说明不可越界", thread.createdAt)
        val source = snapshot(listOf(record), listOf(note)).copy(experienceThreads = listOf(thread), experienceChanges = listOf(change))
        listOf(ReadingExportOptions(kind = ReadingPackageKind.EVIDENCE, question = "合成问题：有哪些原始记录？"),
            ReadingExportOptions(scope = ExportScope(topics = setOf("学习")))).forEach { options ->
            val entries = export(source, options)
            assertFalse(entries.keys.any { it.startsWith("experiences/") })
            assertTrue(entries.values.none { thread.title in it || change.userNote!! in it })
            assertEquals(1, JSONObject(entries.getValue("manifest.json")).getJSONObject("experiences").getInt("omittedChanges"))
            assertLinks(entries)
        }
    }

    private fun record(id: String, date: LocalDate? = LocalDate.parse("2026-10-16"), topics: Set<String> = setOf("学习")) =
        RecordRevision(id, 1, RecordDraft("合成原始记录：$id 学习了一种方法。", Occurrence(date, date, "UTC"), topics), now.minusSeconds(120), now.minusSeconds(120))

    private fun receipt(record: RecordRevision): EvidenceReceipt {
        val text = record.draft.body
        val length = text.codePointCount(0, text.length)
        val hash = AnalysisRunCodec.sha256(text)
        return EvidenceReceipt(AnalysisRunCodec.evidenceId(record.id, record.revision, "body", 0, length, hash),
            record.id, record.revision, "body", 0, length, length, hash)
    }

    private fun note(
        runId: String,
        records: List<RecordRevision>,
        feedbackVersion: Int = 0,
        category: KnowledgeCategory = KnowledgeCategory.KNOWLEDGE_NOTE,
        topics: Set<String> = setOf("学习"),
        query: AnalysisQuery = scope,
    ): StoredKnowledgeRevision {
        val evidence = records.map(::receipt)
        val kind = if (category == KnowledgeCategory.INSIGHT) ClaimKind.POSSIBLE_INTERPRETATION else ClaimKind.REPORTED_EXPERIENCE
        val claim = ProposedClaim("合成已发布陈述：$runId 中记录了一种方法。", listOf(evidence.first().id), kind)
        val ref = ClaimRef("extract", 0, AnalysisRunCodec.claimSha256(claim))
        return StoredKnowledgeRevision(KnowledgeValidation.idFor(runId, ref), 1, runId, ref, stamp, query, null, category, claim,
            "review", 1, "synthetic", "a".repeat(64), ClaimReview(ReviewVerdict.SUPPORTED, listOf(evidence.first().id)),
            evidence, feedbackVersion, topics, now.minusSeconds(60), now.minusSeconds(60))
    }

    private fun feedback(note: StoredKnowledgeRevision, decision: ClaimFeedbackDecision = ClaimFeedbackDecision.CONFIRMED, userText: String? = null) =
        StoredClaimFeedback(AnalysisRunCodec.feedbackId(note.runId, note.claimRef), 1, note.runId, note.claimRef.batchKey, note.claimRef.claimIndex,
            note.claimRef.claimSha256, note.source, decision, userText, now.minusSeconds(70), now.minusSeconds(70))

    private fun snapshot(records: List<RecordRevision>, notes: List<StoredKnowledgeRevision>) = RepositorySnapshot(
        "synthetic-snapshot", stamp.storeId, stamp.generation, now, records, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        analysisGeneration = 11, knowledgeRevisions = notes,
        knowledgeHeads = notes.groupBy { it.id }.map { (id, versions) -> KnowledgeHead(id, versions.maxOf { it.revision }) },
    )

    private fun export(snapshot: RepositorySnapshot, options: ReadingExportOptions = ReadingExportOptions()): Map<String, String> {
        val destination = File(temporary.root, "synthetic-reading-${UUID.randomUUID()}.zip")
        KnowledgeArchive.write(snapshot, temporary.root, destination, options)
        return ZipFile(destination).use { zip -> zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() }
        } }
    }

    private fun machineNotes(entries: Map<String, String>) = entries.filterKeys { it.startsWith("data/knowledge/") && it.endsWith(".jsonl") }
        .values.flatMap { it.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList() }

    private fun markdownTargets(source: String, text: String): Set<String> = Regex("\\[[^\\]\\n]*]\\(([^)]+)\\)").findAll(text).map { match ->
        (Paths.get(source).parent ?: Paths.get("")).resolve(match.groupValues[1]).normalize().toString().replace('\\', '/')
    }.toSet()

    private fun assertLinks(entries: Map<String, String>) {
        entries.filterKeys { it.endsWith(".md") }.forEach { (source, text) ->
            markdownTargets(source, text).forEach { assertTrue("$source -> $it", it in entries) }
        }
    }
}
