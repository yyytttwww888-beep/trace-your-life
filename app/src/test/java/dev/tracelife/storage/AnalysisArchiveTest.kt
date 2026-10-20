package dev.tracelife.storage

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.transfer.*
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile

/** Synthetic year: 365 daily analyses plus 52 weekly analyses; no network or real user data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AnalysisArchiveTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var library: KnowledgeLibrary
    private lateinit var workspace: File
    private val name = "synthetic-archive-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC)
    private val start = LocalDate.parse("2025-01-01")
    private val scope = AnalysisQuery(start, start, "Asia/Shanghai")
    private var requests = 0

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        workspace = File(context.cacheDir, "synthetic-archives-${UUID.randomUUID()}").apply { mkdirs() }
        open()
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name); workspace.deleteRecursively() }

    @Test fun `417 daily and weekly tasks release active budget keep proofs pageable and survive backup`() {
        repeat(365) { day -> repository.createRecord(RecordDraft("合成年记录第 $day 天：完成一次小步骤练习。", Occurrence(start.plusDays(day.toLong())), setOf("合成学习")), "day-$day") }
        var saved: StoredKnowledgeRevision? = null
        repeat(417) { index ->
            val weekly = index >= 365
            val first = if (weekly) (index - 365) * 7 else index
            val last = if (weekly) first + 6 else first
            val id = "reflection-v1-year-${index.toString().padStart(3, '0')}"
            analysis.start(AnalysisRequest("合成${if (weekly) "每周" else "每日"}分析", scope.copy(from = start.plusDays(first.toLong()), through = start.plusDays(last.toLong()))), id)
            analysis.runBatch(id, "complete", (first..last).map { EvidenceSelection("day-$it", "body") }, provider)
            assertTrue(analysis.coveragePage(id).records.all { it.complete })
            if (index == 0) saved = publish(id, "complete")
            val before = analysis.task(id)
            val archived = repository.archiveAnalysisRun(id, before.stored.version)
            assertEquals(before.stored.payload, archived.payload)
            assertEquals(before.stored.source, archived.source)
            assertNotNull(archived.archivedAt)
        }
        assertEquals(417, requests)
        val usage = repository.analysisStorageUsage()
        assertEquals(0, usage.activeTasks); assertEquals(0L, usage.activeBytes); assertEquals(417, usage.archivedTasks)
        assertTrue(usage.archivedBytes > 0 && usage.archivedBytes < AnalysisStorageLimits.ARCHIVED_BYTES)
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(saved!!.id)!!.evidenceState)
        val allIds = buildList {
            var offset = 0
            do {
                val page = repository.analysisRunIndex("reflection-v1-", archived = true, offset = offset, limit = 25)
                addAll(page.map { it.id }); offset += page.size
            } while (page.size == 25)
        }
        assertEquals(417, allIds.distinct().size)
        assertEquals("reflection-v1-year-416", allIds.first())
        assertEquals("reflection-v1-year-000", allIds.last())
        assertTrue(repository.analysisRunIndex("does-not-exist").isEmpty())
        analysis.start(AnalysisRequest("合成第二年的新任务", scope), "next-year")
        assertEquals(1, repository.analysisStorageUsage().activeTasks)
        val attachments = directory()
        val snapshot = repository.captureSnapshot(attachments)
        val json = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toByteArray()
        val roundtrip = SnapshotJsonCodec.read(json.inputStream())
        assertEquals(snapshot, roundtrip)
        assertEquals(417, roundtrip.analysisRuns.count { it.archivedAt != null })
        val backup = File(workspace, "synthetic-year.tlb")
        val passphrase = "synthetic-archive-year-passphrase".toCharArray()
        BackupArchive.write(snapshot, attachments, backup, passphrase)
        val restoredArchive = BackupArchive.read(backup, File(workspace, "unpacked-year"), passphrase)
        assertEquals(snapshot, restoredArchive)
        val reading = File(workspace, "synthetic-year.zip")
        KnowledgeArchive.write(snapshot, attachments, reading, ReadingExportOptions())
        ZipFile(reading).use { zip ->
            assertTrue(zip.entries().asSequence().any { entry -> entry.name.startsWith("notes/") && entry.name.endsWith(".md") &&
                zip.getInputStream(entry).bufferedReader().use { it.readText() }.contains(saved!!.claim.text) })
        }
        val targetName = "synthetic-archive-restored-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, targetName).use { target ->
                target.restoreSnapshot(roundtrip, attachments, target.currentGeneration())
                assertEquals(417, target.analysisStorageUsage().archivedTasks)
                assertEquals(1, target.analysisStorageUsage().activeTasks)
                assertNotNull(target.analysisRun(allIds.last()))
                assertEquals(KnowledgeEvidenceState.STALE, KnowledgeLibrary(target, clock).get(saved!!.id)!!.evidenceState)
                assertEquals(saved!!.claim.text, KnowledgeLibrary(target, clock).get(saved!!.id)!!.value.claim.text)
            }
        } finally { context.deleteDatabase(targetName) }
    }

    @Test fun `archived dispatch is blocked before provider and explicit reopening preserves publication`() {
        record("source")
        val saved = prepare("run", "source")
        val before = analysis.task("run").stored
        val archived = repository.archiveAnalysisRun("run", before.version)
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(saved.id)!!.evidenceState)
        assertEquals(before.payload, archived.payload)
        val calls = requests
        assertThrows(IllegalArgumentException::class.java) {
            analysis.runBatch("run", "unapproved-new-call", listOf(EvidenceSelection("source", "body")), provider)
        }
        assertEquals(calls, requests)
        assertThrows(RevisionConflictException::class.java) { repository.unarchiveAnalysisRun("run", before.version) }
        repository.close(); open()
        assertEquals(archived, repository.analysisRun("run"))
        val reopened = repository.unarchiveAnalysisRun("run", archived.version)
        assertNull(reopened.archivedAt)
        assertEquals(before.payload, reopened.payload)
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(saved.id)!!.evidenceState)
        analysis.runBatch("run", "explicit-new-call", listOf(EvidenceSelection("source", "body")), provider)
        assertEquals(calls + 1, requests)
    }

    @Test fun `active capacity remains one hundred while archives never count against it`() {
        record("source")
        analysis.start(AnalysisRequest("合成归档任务", scope), "archived")
        repository.archiveAnalysisRun("archived", analysis.task("archived").stored.version)
        repeat(100) { index -> analysis.start(AnalysisRequest("合成活动任务", scope), "active-$index") }
        assertThrows(IllegalArgumentException::class.java) { analysis.start(AnalysisRequest("超出活动预算", scope), "blocked") }
        assertThrows(IllegalArgumentException::class.java) { repository.unarchiveAnalysisRun("archived", repository.analysisRun("archived")!!.version) }
        assertEquals(100, repository.analysisStorageUsage().activeTasks)
        assertEquals(1, repository.analysisStorageUsage().archivedTasks)
        repository.archiveAnalysisRun("active-0", analysis.task("active-0").stored.version)
        repository.unarchiveAnalysisRun("archived", repository.analysisRun("archived")!!.version)
        assertEquals(100, repository.analysisStorageUsage().activeTasks)
        assertEquals(1, repository.analysisStorageUsage().archivedTasks)
    }

    @Test fun `in flight task cannot be archived and old completion cannot bypass archive fence`() {
        record("source")
        analysis.start(AnalysisRequest("合成在途任务", scope), "run")
        val crash = object : Error() {}
        val interrupt = object : AnalysisProvider {
            override val id = "synthetic-interrupted-archive-provider"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = request.payloadJson.length
            override fun complete(request: ModelRequest): BatchProposal {
                assertThrows(IllegalArgumentException::class.java) { repository.archiveAnalysisRun("run", analysis.task("run").stored.version) }
                throw crash
            }
        }
        assertThrows(Error::class.java) { analysis.runBatch("run", "interrupted", listOf(EvidenceSelection("source", "body")), interrupt) }
        assertNull(repository.analysisRun("run")!!.archivedAt)
        analysis.recoverInterrupted("run")
        val before = repository.analysisRun("run")!!
        repository.archiveAnalysisRun("run", before.version)
        assertThrows(IllegalArgumentException::class.java) { repository.saveAnalysisRun(before.copy(version = before.version + 2), before.version + 1) }
    }

    @Test fun `feedback can correct archived knowledge and permanent deletion traverses archived proofs`() {
        record("source"); record("unrelated")
        val first = prepare("run", "source")
        val other = prepare("other", "unrelated")
        repository.archiveAnalysisRun("run", analysis.task("run").stored.version)
        repository.archiveAnalysisRun("other", analysis.task("other").stored.version)
        val feedback = AnalysisFeedback(repository, clock)
        val response = feedback.respond("run", first.claimRef, analysis.task("run").stored.version,
            ClaimFeedbackDecision.CORRECTED, "合成独立意见：这次执行不代表长期习惯。")
        assertEquals(KnowledgeEvidenceState.FEEDBACK_CHANGED, library.get(first.id)!!.evidenceState)
        assertNotNull(repository.analysisRun("run")!!.archivedAt)
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(other.id)!!.evidenceState)
        feedback.delete(response.feedback.id, response.feedback.version)
        assertNull(library.get(first.id))
        assertNotNull(library.get(other.id))
        val snapshot = repository.captureSnapshot(directory())
        repository.deleteRecord("unrelated", 1)
        assertNull(repository.analysisRun("other")); assertNull(library.get(other.id))
        repository.restoreSnapshot(snapshot, directory(), repository.currentGeneration(), true)
        assertNull(repository.analysisRun("other")); assertNull(library.get(other.id))
        assertTrue(repository.analysisRun("run")?.archivedAt != null)
    }

    @Test fun `restore removes archived context descendants when newer local feedback excludes their old source task`() {
        record("source")
        val prior = prepare("prior", "source")
        val feedback = AnalysisFeedback(repository, clock)
        feedback.respond("prior", prior.claimRef, analysis.task("prior").stored.version,
            ClaimFeedbackDecision.CORRECTED, "合成旧纠正：一次练习不足以判断能力。")
        analysis.start(AnalysisRequest("合成新期间对照", scope, inheritHistoricalFeedback = true), "dependent")
        analysis.synthesize("dependent", "generation", listOf(EvidenceSelection("source", "body")), provider)
        repository.archiveAnalysisRun("dependent", analysis.task("dependent").stored.version)
        val before = repository.captureSnapshot(directory())
        analysis.runBatch("prior", "later-claim", listOf(EvidenceSelection("source", "body")), provider)
        val later = analysis.claimRef("prior", "later-claim", 0)
        val latest = feedback.respond("prior", later, analysis.task("prior").stored.version,
            ClaimFeedbackDecision.CORRECTED, "合成后来意见：仍需保留当前用户说明。")
        repository.restoreSnapshot(before, directory(), repository.currentGeneration(), true)
        assertNull(repository.analysisRun("prior"))
        assertNull(repository.analysisRun("dependent"))
        assertEquals(latest.feedback, repository.claimFeedback(latest.feedback.id))
        val after = repository.captureSnapshot(directory())
        assertTrue(after.deletedAnalysisRuns.any { it.id == "dependent" })
        assertEquals(2, after.claimFeedback.size)
    }

    @Test fun `released version eight backup reads tasks as active and rejects a smuggled archive state`() {
        record("source")
        prepare("run", "source")
        val snapshot = repository.captureSnapshot(directory())
        val encoded = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toString(Charsets.UTF_8)
        val legacy = JSONObject(encoded).apply {
            put("formatVersion", 8); put("sourceSchemaVersion", 9)
            remove("historicalComparisons"); remove("historicalComparisonDeletions"); remove("experienceThreads"); remove("experienceChanges"); remove("deletedExperienceThreads"); remove("conversations"); remove("conversationTurns"); remove("deletedConversations")
            getJSONArray("analysisRuns").getJSONObject(0).remove("archivedAt")
        }
        assertEquals(snapshot.copy(sourceSchemaVersion = 9), SnapshotJsonCodec.read(legacy.toString().byteInputStream()))
        legacy.getJSONArray("analysisRuns").getJSONObject(0).put("archivedAt", clock.instant().toString())
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(legacy.toString().byteInputStream()) }
    }

    @Test fun `version nine upgrade keeps existing proof active without source reset`() {
        record("source")
        val saved = prepare("run", "source")
        val before = analysis.task("run").stored
        repository.close()
        LifeDatabase(context, name).use { helper ->
            removeVersionTenForMigration(helper.writableDatabase)
            helper.writableDatabase.version = 9
        }
        open()
        assertEquals(before, analysis.task("run").stored)
        assertNull(repository.analysisRun("run")!!.archivedAt)
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(saved.id)!!.evidenceState)
        assertEquals(11, repository.captureSnapshot(directory()).sourceSchemaVersion)
        assertTrue(repository.experienceThreads().isEmpty())
    }

    private fun open() {
        repository = LifeRepository(context, clock, name)
        analysis = ControlledAnalysis(repository, clock)
        library = KnowledgeLibrary(repository, clock)
    }
    private fun directory() = File(workspace, UUID.randomUUID().toString()).apply { mkdirs() }
    private fun record(id: String) = repository.createRecord(RecordDraft("合成资料：完成一次小步骤练习。", Occurrence(start)), id)
    private fun prepare(id: String, source: String): StoredKnowledgeRevision {
        analysis.start(AnalysisRequest("合成观察", scope.copy(recordIds = setOf(source))), id)
        analysis.runBatch(id, "extract", listOf(EvidenceSelection(source, "body")), provider)
        return publish(id, "extract")
    }
    private fun publish(id: String, batch: String): StoredKnowledgeRevision {
        val claim = analysis.claimRef(id, batch, 0)
        analysis.reviewClaim(id, "review", claim, provider = reviewer)
        return library.publish(id, claim, "review", KnowledgeCategory.OBSERVATION, analysis.task(id).stored.version)
    }
    private val provider = object : AnalysisProvider {
        override val id = "synthetic-archive-provider"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.length
        override fun complete(request: ModelRequest): BatchProposal {
            requests++
            val kind = if (JSONObject(request.payloadJson).optString("purpose", "EXTRACTION") == "SYNTHESIS")
                ClaimKind.POSSIBLE_INTERPRETATION else ClaimKind.REPORTED_EXPERIENCE
            return BatchProposal(listOf(ProposedClaim("合成观察：本期间记录了小步骤练习。", ids(request), kind)))
        }
    }
    private val reviewer = object : SemanticReviewProvider {
        override val id = "synthetic-archive-reviewer"
        override val contextWindowTokens = 100_000
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, request.payloadJson.length)
        override fun reviewPrepared(request: PreparedModelRequest) = ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, ids(request.original)))
    }
    private fun ids(request: ModelRequest): List<String> = JSONObject(request.payloadJson).getJSONArray("evidence").let { list ->
        (0 until list.length()).map { list.getJSONObject(it).getString("id") }
    }
}
