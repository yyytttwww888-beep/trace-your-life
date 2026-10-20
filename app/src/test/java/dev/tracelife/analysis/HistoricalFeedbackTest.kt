package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.LifeRepository
import dev.tracelife.transfer.BackupArchive
import dev.tracelife.transfer.PublishedKnowledgeExport
import dev.tracelife.transfer.ReadingExportOptions
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
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Synthetic cross-week user corrections. No credentials, network or real personal material. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HistoricalFeedbackTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var workflow: ReflectionWorkflow
    private val database = "synthetic-historical-feedback-${UUID.randomUUID()}.db"
    private val day = LocalDate.parse("2026-10-20")
    private val scope = AnalysisQuery(day, day, "Asia/Shanghai")
    private val provider = SyntheticProvider()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, databaseName = database)
        analysis = ControlledAnalysis(repository)
        workflow = ReflectionWorkflow(repository, analysis)
        repository.createRecord(RecordDraft("合成本周：任务较容易，只做了一次练习。", Occurrence(day), setOf("学习")), "new-source")
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(database) }

    @Test fun `a paraphrase cannot bypass prior rejection and explicit unrelated choice keeps a version trail`() {
        val previous = rejectEarlier()
        val current = generated()
        val target = current.findings.single().target
        val control = JSONObject(provider.requests.last().payloadJson).getJSONObject("historicalFeedback")
        assertFalse(control.getBoolean("originalEvidence"))
        assertEquals(1, control.getInt("totalRelevantOpinions"))
        val controlEntry = control.getJSONArray("entries").getJSONObject(0)
        assertEquals("historical-feedback-v1:$previous", controlEntry.getString("controlKey"))
        assertFalse(controlEntry.has("id"))
        assertFalse(controlEntry.has("sha256"))
        assertEquals("合成用户纠正：仅任务更简单，不能认定方法有效。", control.getJSONArray("entries").getJSONObject(0).getString("userText"))
        assertEquals(1, current.findings.single().historicalFeedback.usedInGeneration)
        workflow.respond(current.id, target, current.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        assertThrows(AnalysisDependencyBlockedException::class.java) { workflow.reviewAndSave(current.id, target, provider, provider) }
        assertEquals(2, provider.requests.size)
        var compared = workflow.resolveHistoricalFeedback(current.id, target, previous, workflow.inspect(current.id).taskVersion,
            HistoricalFeedbackChoice.CONFLICT, "合成：仍把容易任务的结果归因于方法。")
        assertThrows(AnalysisDependencyBlockedException::class.java) { workflow.reviewAndSave(current.id, target, provider, provider) }
        compared = workflow.resolveHistoricalFeedback(current.id, target, previous, compared.taskVersion,
            HistoricalFeedbackChoice.UNRELATED, "合成：这次描述只说尝试方法，未宣称方法有效。", 1)
        val saved = workflow.reviewAndSave(current.id, target, provider, provider).run
        val knowledgeId = saved.findings.single().knowledgeId!!
        assertEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository).get(knowledgeId)!!.evidenceState)
        val state = analysis.task(current.id).state
        assertEquals(listOf(1, 2), state.historicalFeedbackResolutions.map { it.version })
        assertEquals(state, AnalysisRunCodec.decode(AnalysisRunCodec.encode(state)))
        // Changing the comparison invalidates even a previously supported original review.
        workflow.resolveHistoricalFeedback(current.id, target, previous, saved.taskVersion,
            HistoricalFeedbackChoice.CONFLICT, "合成：重新检查发现确实还有冲突。", 2)
        assertEquals(KnowledgeEvidenceState.REVIEW_CHANGED, KnowledgeLibrary(repository).get(knowledgeId)!!.evidenceState)
        val review = state.calls.last()
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeLibrary(repository).publish(current.id, (target as ReflectionTarget.Claim).ref, review.batchKey,
                KnowledgeCategory.INSIGHT, workflow.inspect(current.id).taskVersion, expectedRevision = 1)
        }
    }

    @Test fun `a historical feedback identifier mixed into original citations rejects the whole proposal`() {
        val previous = rejectEarlier()
        provider.additionalCitations = listOf(previous)
        val run = workflow.start(scope)
        val result = workflow.process(run.id, provider, maxRequests = 1)
        assertEquals(ReflectionStatus.RETRY_NEEDED, result.status)
        assertTrue(result.run.findings.isEmpty())
        val call = analysis.task(run.id).state.calls.single()
        assertEquals("INVALID_RESPONSE", call.failureCode)
        assertEquals(listOf(previous), call.historicalFeedbackReadIds)
        assertNull(call.proposal)
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    @Test fun `more than one model page remains fully reachable and all comparisons are required`() {
        rejectEarlier(count = 17)
        val current = generated()
        val target = current.findings.single().target
        assertEquals(17, current.findings.single().historicalFeedback.total)
        assertTrue(current.findings.single().historicalFeedback.usedInGeneration in 1..8)
        val first = workflow.historicalFeedbackPage(current.id, target)
        val second = workflow.historicalFeedbackPage(current.id, target, first.nextOffset!!)
        assertEquals(16, first.items.size)
        assertEquals(1, second.items.size)
        assertNull(second.nextOffset)
        workflow.respond(current.id, target, current.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        first.items.forEach {
            workflow.resolveHistoricalFeedback(current.id, target, it.content.reference.id, workflow.inspect(current.id).taskVersion,
                HistoricalFeedbackChoice.RESPECTED, "合成：已对照，表述保留了限制条件。")
        }
        assertThrows(AnalysisDependencyBlockedException::class.java) { workflow.reviewAndSave(current.id, target, provider, provider) }
        workflow.resolveHistoricalFeedback(current.id, target, second.items.single().content.reference.id, workflow.inspect(current.id).taskVersion,
            HistoricalFeedbackChoice.RESPECTED, "合成：最后一条也已对照。")
        assertNotNull(workflow.reviewAndSave(current.id, target, provider, provider).run.findings.single().knowledgeId)
    }

    @Test fun `feedback changed while the provider is running cannot save its answer`() {
        val previous = rejectEarlier()
        val run = workflow.start(scope)
        provider.before = {
            val value = repository.claimFeedback(previous)!!
            AnalysisFeedback(repository).update(previous, value.version, repository.analysisRun(value.runId)!!.version,
                ClaimFeedbackDecision.CORRECTED, "合成：新纠正内容，已变化。")
        }
        val result = workflow.process(run.id, provider)
        assertEquals(ReflectionStatus.STALE, result.status)
        assertTrue(result.run.findings.isEmpty())
        assertEquals(CallStatus.IN_FLIGHT, analysis.task(run.id).state.calls.single().status)
    }

    @Test fun `an unread directory entry deletion invalidates but does not erase an unrelated generated answer`() {
        rejectEarlier(count = 17)
        val current = generated()
        val state = analysis.task(current.id).state
        val used = state.calls.flatMap { it.historicalFeedbackReadIds }.toSet()
        val unread = state.feedbackContext!!.references.first { it.id !in used }
        AnalysisFeedback(repository).delete(unread.id, unread.version)
        assertNotNull(repository.analysisRun(current.id))
        val historical = workflow.inspect(current.id)
        assertEquals(ReflectionStatus.STALE, historical.status)
        assertEquals(current.findings.single().text, historical.findings.single().text)
        val work = File(context.cacheDir, "synthetic-unread-feedback-backup-${UUID.randomUUID()}").apply { mkdirs() }
        try { repository.captureSnapshot(File(work, "attachments").apply { mkdirs() }) }
        finally { work.deleteRecursively() }
    }

    @Test fun `context cannot be removed from an enabled task or attached to a legacy task`() {
        val enabled = AnalysisRunState(AnalysisRequest("合成任务", scope, focusTopics = setOf("学习"), inheritHistoricalFeedback = true))
        assertThrows(IllegalArgumentException::class.java) { FeedbackContextProtocol.validate(enabled) }
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.encode(enabled) }
        val disabled = AnalysisRunState(AnalysisRequest("合成任务", scope), feedbackContext = FeedbackContextPlan(emptySet(), emptyList()))
        assertThrows(IllegalArgumentException::class.java) { FeedbackContextProtocol.validate(disabled) }
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.encode(disabled) }
    }

    @Test fun `archived findings permit an independent correction without reopening an active slot`() {
        val run = generated()
        val archived = workflow.archive(run.id, run.taskVersion)
        assertNotNull(archived.archivedAt)
        val corrected = workflow.respond(run.id, run.findings.single().target, archived.taskVersion,
            ClaimFeedbackDecision.CORRECTED, userText = "合成：只是一种尝试，还不能推广到所有任务。")
        assertNotNull(corrected.archivedAt)
        assertEquals("合成：只是一种尝试，还不能推广到所有任务。", corrected.findings.single().feedback!!.feedback.userText)
        assertThrows(IllegalArgumentException::class.java) {
            workflow.respond(run.id, run.findings.single().target, corrected.taskVersion, ClaimFeedbackDecision.CONFIRMED,
                expectedFeedbackVersion = 1)
        }
    }

    @Test fun `deleting historical original purges derived wording but retains independent user correction`() {
        val previous = rejectEarlier()
        val current = generated()
        val saved = saveCompared(current)
        repository.deleteRecord("old-source", 1)
        assertNull(repository.analysisRun(current.id))
        assertNull(repository.knowledgeRevision(saved))
        assertEquals("合成：保留任务不同与证据不足的限制。", repository.historicalComparisons(current.id).single().resolution.reason)
        assertEquals("合成用户纠正：仅任务更简单，不能认定方法有效。", repository.claimFeedback(previous)!!.userText)
        val next = generated()
        val entry = workflow.historicalFeedbackPage(next.id, next.findings.single().target).items.single()
        assertNull(entry.content.previousClaimText)
        assertNotNull(entry.content.userText)
    }

    @Test fun `deleting prior feedback removes dependent analysis and cannot return from an older snapshot`() {
        val previous = rejectEarlier()
        val current = generated()
        val saved = saveCompared(current)
        val work = File(context.cacheDir, "synthetic-history-backup-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val snapshot = repository.captureSnapshot(File(work, "attachments").apply { mkdirs() })
            AnalysisFeedback(repository).delete(previous, 1)
            assertNull(repository.analysisRun(current.id))
            assertNull(repository.knowledgeRevision(saved))
            assertEquals(1, repository.historicalComparisons(current.id).size)
            repository.restoreSnapshot(snapshot, File(work, "attachments"), repository.currentGeneration(), replaceExisting = true)
            assertNull(repository.analysisRun(current.id))
            assertNull(repository.knowledgeRevision(saved))
            assertNull(repository.claimFeedback(previous))
            assertEquals(1, repository.historicalComparisons(current.id).size)
        } finally { work.deleteRecursively() }
    }

    @Test fun `restoring an old accepted comparison preserves a newer independent conflict and clears old machine proof`() {
        val previous = rejectEarlier()
        val current = generated()
        val saved = saveCompared(current)
        val work = File(context.cacheDir, "synthetic-comparison-restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val snapshot = repository.captureSnapshot(File(work, "attachments").apply { mkdirs() })
            workflow.resolveHistoricalFeedback(current.id, current.findings.single().target, previous, workflow.inspect(current.id).taskVersion,
                HistoricalFeedbackChoice.CONFLICT, "合成：后续重新核对仍有问题。", 1)
            repository.restoreSnapshot(snapshot, File(work, "attachments"), repository.currentGeneration(), replaceExisting = true)
            assertNull(repository.analysisRun(current.id))
            assertNull(repository.knowledgeRevision(saved))
            val notes = repository.historicalComparisons(current.id).sortedBy { it.resolution.version }
            assertEquals(listOf(HistoricalFeedbackChoice.RESPECTED, HistoricalFeedbackChoice.CONFLICT), notes.map { it.resolution.choice })
            assertEquals("合成：后续重新核对仍有问题。", notes.last().resolution.reason)
        } finally { work.deleteRecursively() }
    }

    @Test fun `explicit note deletion after task removal fences an older backup independently`() {
        rejectEarlier()
        val current = generated()
        val saved = saveCompared(current)
        val work = File(context.cacheDir, "synthetic-comparison-delete-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val snapshot = repository.captureSnapshot(File(work, "attachments").apply { mkdirs() })
            analysis.delete(current.id, analysis.task(current.id).stored.version)
            assertEquals(1, repository.historicalComparisons(current.id).size)
            repository.deleteHistoricalComparisons(current.id, repository.currentAnalysisGeneration())
            assertTrue(repository.historicalComparisons(current.id).isEmpty())
            repository.restoreSnapshot(snapshot, File(work, "attachments"), repository.currentGeneration(), replaceExisting = true)
            assertTrue(repository.historicalComparisons(current.id).isEmpty())
            assertNull(repository.analysisRun(current.id))
            assertNull(repository.knowledgeRevision(saved))
        } finally { work.deleteRecursively() }
    }

    @Test fun `same second comparison notes keep chronological order and latest time after restore`() {
        val previous = rejectEarlier()
        val current = generated()
        val target = (current.findings.single().target as ReflectionTarget.Claim).ref
        val whole = analysis.task(current.id).stored.updatedAt.plusSeconds(1).truncatedTo(ChronoUnit.SECONDS)
        val fractional = whole.plusMillis(100)
        val first = ControlledAnalysis(repository, Clock.fixed(whole, ZoneOffset.UTC)).resolveHistoricalFeedback(
            current.id, target, previous, current.taskVersion, HistoricalFeedbackChoice.RESPECTED, "合成：第一次对照。")
        ControlledAnalysis(repository, Clock.fixed(fractional, ZoneOffset.UTC)).resolveHistoricalFeedback(
            current.id, target, previous, first.stored.version, HistoricalFeedbackChoice.CONFLICT, "合成：同一秒重新核对发现冲突。", 1)
        fun assertOrdered(targetRepository: LifeRepository) {
            assertEquals(listOf(2, 1), targetRepository.historicalComparisons(current.id).map { it.resolution.version })
            assertEquals(fractional, targetRepository.historicalComparisonRuns().single().latestAt)
        }
        assertOrdered(repository)
        val work = File(context.cacheDir, "synthetic-comparison-order-${UUID.randomUUID()}").apply { mkdirs() }
        val restoredDatabase = "synthetic-comparison-restored-${UUID.randomUUID()}.db"
        try {
            val attachments = File(work, "attachments").apply { mkdirs() }
            val snapshot = repository.captureSnapshot(attachments)
            val backup = File(work, "synthetic-comparisons.tlb")
            val passphrase = "synthetic-comparison-passphrase".toCharArray()
            BackupArchive.write(snapshot, attachments, backup, passphrase)
            val unpacked = File(work, "unpacked")
            val restored = BackupArchive.read(backup, unpacked, passphrase)
            LifeRepository(context, databaseName = restoredDatabase).use { targetRepository ->
                targetRepository.restoreSnapshot(restored, unpacked, targetRepository.currentGeneration())
                assertOrdered(targetRepository)
            }
        } finally { context.deleteDatabase(restoredDatabase); work.deleteRecursively() }
    }

    @Test fun `deleting orphan comparison notes prevents reusing the task identity without an analysis tombstone`() {
        rejectEarlier()
        val current = generated()
        saveCompared(current)
        val work = File(context.cacheDir, "synthetic-orphan-comparison-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val snapshot = repository.captureSnapshot(File(work, "attachments").apply { mkdirs() })
            val orphaned = snapshot.copy(
                analysisRuns = snapshot.analysisRuns.filterNot { it.id == current.id },
                knowledgeRevisions = emptyList(), knowledgeHeads = emptyList(),
            )
            repository.restoreSnapshot(orphaned, File(work, "attachments"), repository.currentGeneration(), replaceExisting = true)
            assertNull(repository.analysisRun(current.id))
            assertEquals(1, repository.historicalComparisons(current.id).size)
            repository.deleteHistoricalComparisons(current.id, repository.currentAnalysisGeneration())
            val deleted = repository.captureSnapshot(File(work, "after-delete").apply { mkdirs() })
            assertTrue(deleted.deletedAnalysisRuns.none { it.id == current.id })
            assertTrue(deleted.historicalComparisonDeletions.any { it.id == current.id })
            assertThrows(IllegalArgumentException::class.java) {
                analysis.start(AnalysisRequest("合成：尝试重新使用已擦除说明的身份", scope), current.id)
            }
            assertNull(repository.analysisRun(current.id))
        } finally { work.deleteRecursively() }
    }

    @Test fun `new relevant correction invalidates saved knowledge and reading export while other topics do not`() {
        rejectEarlier()
        val current = generated()
        val saved = saveCompared(current)
        rejectEarlier(topic = "运动", id = "sport")
        assertEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository).get(saved)!!.evidenceState)
        rejectEarlier(topic = "学习", id = "additional")
        assertEquals(KnowledgeEvidenceState.FEEDBACK_CHANGED, KnowledgeLibrary(repository).get(saved)!!.evidenceState)
        assertEquals(ReflectionStatus.STALE, workflow.inspect(current.id).status)
        val work = File(context.cacheDir, "synthetic-history-export-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val snapshot = repository.captureSnapshot(File(work, "attachments").apply { mkdirs() })
            val heads = snapshot.records.groupBy { it.id }.values.map { versions -> versions.maxBy { it.revision } }
            assertTrue(PublishedKnowledgeExport.select(snapshot, heads, heads, ReadingExportOptions()).entries.isEmpty())
        } finally { work.deleteRecursively() }
    }

    private fun generated(): ReflectionRun {
        provider.claimCount = 1
        return workflow.process(workflow.start(scope).id, provider).run
    }
    private fun saveCompared(run: ReflectionRun): String {
        val target = run.findings.single().target
        workflow.respond(run.id, target, run.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        workflow.historicalFeedbackPage(run.id, target).items.forEach {
            workflow.resolveHistoricalFeedback(run.id, target, it.content.reference.id, workflow.inspect(run.id).taskVersion,
                HistoricalFeedbackChoice.RESPECTED, "合成：保留任务不同与证据不足的限制。")
        }
        return workflow.reviewAndSave(run.id, target, provider, provider).run.findings.single().knowledgeId!!
    }
    private fun rejectEarlier(count: Int = 1, topic: String = "学习", id: String = "old"): String {
        val earlier = day.minusDays(14)
        repository.createRecord(RecordDraft("合成上期：练习完成但任务更简单。", Occurrence(earlier), setOf(topic)), "$id-source")
        analysis.start(AnalysisRequest("合成上期判断", AnalysisQuery(earlier, earlier, "Asia/Shanghai", recordIds = setOf("$id-source")),
            focusTopics = setOf(topic)), "$id-run")
        provider.claimCount = count
        analysis.synthesize("$id-run", "generated", listOf(EvidenceSelection("$id-source", "body")), provider)
        repeat(count) { index ->
            val claim = analysis.claimRef("$id-run", "generated", index)
            AnalysisFeedback(repository).respond("$id-run", claim, analysis.task("$id-run").stored.version,
                ClaimFeedbackDecision.CORRECTED, "合成用户纠正：仅任务更简单，不能认定方法有效。")
        }
        return AnalysisRunCodec.feedbackId("$id-run", analysis.claimRef("$id-run", "generated", 0))
    }

    private class SyntheticProvider : AnalysisProvider, SemanticReviewProvider, SemanticReductionScanProvider {
        override val id = "synthetic-historical-feedback-provider"
        override val contextWindowTokens = 128_000
        var claimCount = 1
        var additionalCitations = emptyList<String>()
        var before: () -> Unit = {}
        val requests = mutableListOf<ModelRequest>()
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request; before()
            val root = JSONObject(request.payloadJson)
            val refs = root.getJSONArray(if (root.getString("purpose") == "REDUCTION") "derivedInputs" else "evidence")
            return BatchProposal(List(claimCount) { i -> ProposedClaim("值得留下：合成表述$i，先做一次练习，效果尚需验证。",
                (0 until refs.length()).map { refs.getJSONObject(it).getString("id") } + additionalCitations, ClaimKind.POSSIBLE_INTERPRETATION) })
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            requests += request.original
            val refs = JSONObject(request.original.payloadJson).getJSONArray("evidence")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, (0 until refs.length()).map { refs.getJSONObject(it).getString("id") }))
        }
        override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun scanPrepared(request: PreparedModelRequest) = error("Synthetic single-batch fixture has no reduction scans")
    }
}
