package dev.tracelife.background

import android.app.Application
import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.ClaimFeedbackDecision
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic records/providers only. Tests real source/operational SQLite and durable request ledgers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class IncrementalOrganizerTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var organizer: IncrementalOrganizer
    private lateinit var directory: File
    private val database = "synthetic-incremental-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val provider = SyntheticProvider()
    private val date = LocalDate.parse("2026-10-19")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        directory = File(context.noBackupFilesDir, "synthetic-incremental-${UUID.randomUUID()}")
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        organizer = IncrementalOrganizer(repository, analysis, directory, clock)
    }

    @After fun tearDown() {
        organizer.close()
        repository.close()
        directory.deleteRecursively()
        context.deleteDatabase(database)
    }

    @Test fun `default disabled and enabling authorizes only later events`() {
        record("old")
        assertEquals(BackgroundPassStatus.DISABLED, process().status)
        enable()
        assertTrue(organizer.jobs().isEmpty())
        record("new")
        val result = process()
        assertEquals(2, result.modelRequestsThisPass)
        assertEquals(listOf("new"), organizer.jobs().map { it.recordId })
        assertEquals(BackgroundJobStatus.PROPOSALS_READY, organizer.jobs().single().status)
        assertTrue(provider.requests.all { request ->
            val evidence = JSONObject(request.payloadJson).getJSONArray("evidence")
            (0 until evidence.length()).all { evidence.getJSONObject(it).getString("recordId") == "new" }
        })
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertTrue(repository.claimFeedbacks().isEmpty())
    }

    @Test fun `event replay coalesces revisions and checkpoint survives reopen`() {
        enable()
        val original = record("changed")
        repository.updateRecord(original.id, original.revision, original.draft.copy(body = "合成修订：减少了练习步骤。"))
        assertEquals(2, organizer.synchronizeChanges())
        assertEquals(1, organizer.jobs().size)
        assertEquals(2, organizer.jobs().single().recordRevision)
        assertEquals(0, organizer.synchronizeChanges())
        reopen()
        assertEquals(0, organizer.synchronizeChanges())
        assertEquals(2, process().modelRequestsThisPass)
        assertEquals(0, process().modelRequestsThisPass)
    }

    @Test fun `one request per pass resumes without rereading successful batches`() {
        enable(BackgroundPolicy(enabled = true, maxRequestsPerPass = 1))
        record("one")
        assertEquals(1, process().modelRequestsThisPass)
        val run = organizer.jobs().single().runId
        reopen()
        assertEquals(1, process().modelRequestsThisPass)
        assertEquals(run, organizer.jobs().single().runId)
        assertEquals(0, process().modelRequestsThisPass)
        assertEquals(BackgroundJobStatus.PROPOSALS_READY, organizer.jobs().single().status)
        assertEquals(2, organizer.jobs().single().reservedRequests)
        assertEquals(2, provider.requests.size)
    }

    @Test fun `daily UTC quota survives process restart and changing presentation timezone`() {
        enable(BackgroundPolicy(enabled = true, dailyRequestLimit = 1))
        record("daily")
        assertEquals(BackgroundPassStatus.DAILY_BUDGET_EXHAUSTED, process().status)
        assertEquals(1, provider.requests.size)
        reopen()
        organizer.configure(organizer.policy().copy(zoneId = "Pacific/Auckland"))
        assertEquals(BackgroundPassStatus.DAILY_BUDGET_EXHAUSTED, process().status)
        assertEquals(1, organizer.settings().reservedRequestsToday)
        clock.now = Instant.parse("2026-10-20T00:01:00Z")
        assertEquals(1, process().modelRequestsThisPass)
        assertEquals(BackgroundJobStatus.PROPOSALS_READY, organizer.jobs().single().status)
        assertEquals(2, organizer.jobs().single().reservedRequests)
    }

    @Test fun `removed analysis replanning does not reset cumulative job allowance`() {
        enable(BackgroundPolicy(enabled = true, maxRequestsPerPass = 1, maxRequestsPerJob = 2))
        record("first")
        process()
        val originalRun = requireNotNull(organizer.jobs().single().runId)
        analysis.delete(originalRun, analysis.task(originalRun).stored.version)
        process()
        val first = organizer.jobs().first { it.recordId == "first" }
        assertNotEquals(originalRun, first.runId)
        assertEquals(2, first.reservedRequests)
        assertEquals(BackgroundPassStatus.JOB_BUDGET_EXHAUSTED, process().status)
        assertEquals(BackgroundJobStatus.BUDGET_EXHAUSTED, organizer.jobs().first { it.recordId == "first" }.status)
        assertEquals(2, provider.requests.size)
    }

    @Test fun `record changed while creating the analysis run is not sent under the older job revision`() {
        enable()
        val first = record("start-race")
        organizer.close()
        val changingClock = object : Clock() {
            var changed = false
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant {
                if (!changed) {
                    changed = true
                    // ControlledAnalysis reads its clock immediately before capturing its source.
                    repository.updateRecord(first.id, first.revision, first.draft.copy(body = "合成新修订：练习方案已修改。"))
                }
                return clock.instant()
            }
        }
        analysis = ControlledAnalysis(repository, changingClock)
        organizer = IncrementalOrganizer(repository, analysis, directory, clock)
        assertEquals(0, process().modelRequestsThisPass)
        assertEquals(BackgroundJobStatus.SUPERSEDED, organizer.jobs().single().status)
        assertEquals(0, organizer.jobs().single().reservedRequests)
        assertEquals(2, process().modelRequestsThisPass)
        assertEquals(2, organizer.jobs().last().recordRevision)
        assertEquals(2, organizer.jobs().last().reservedRequests)
    }

    @Test fun `failure consumes allowance and periodic passes do not retry without explicit action`() {
        enable()
        record("failed")
        provider.failure = true
        assertEquals(BackgroundPassStatus.NEEDS_ATTENTION, process().status)
        assertEquals(1, organizer.settings().reservedRequestsToday)
        assertEquals(BackgroundJobStatus.FAILED, organizer.jobs().single().status)
        provider.failure = false
        reopen()
        repeat(2) { process() }
        assertEquals(1, provider.requests.size)
        val job = organizer.jobs().single()
        organizer.retry(job.id, job.version)
        assertEquals(2, process().modelRequestsThisPass)
        assertEquals(3, organizer.jobs().single().reservedRequests)
        assertEquals(BackgroundJobStatus.PROPOSALS_READY, organizer.jobs().single().status)
    }

    @Test fun `process death holds an in flight call for explicit recovery and keeps its quota`() {
        enable()
        record("crash")
        provider.crash = true
        assertThrows(SyntheticProcessDeath::class.java) { process() }
        assertEquals(BackgroundJobStatus.RUNNING, organizer.jobs().single().status)
        provider.crash = false
        reopen()
        process()
        assertEquals(1, provider.requests.size)
        val orphan = organizer.jobs().single()
        assertEquals(BackgroundJobStatus.NEEDS_RECOVERY, orphan.status)
        assertEquals(1, orphan.reservedRequests)
        organizer.retry(orphan.id, orphan.version)
        assertEquals(2, process().modelRequestsThisPass)
        assertEquals(3, organizer.settings().reservedRequestsToday)
    }

    @Test fun `pause during a dispatched call prevents the next request and resumes the same task`() {
        enable()
        record("pause")
        provider.onDispatch = { organizer.pause() }
        assertEquals(BackgroundPassStatus.PAUSED, process().status)
        assertEquals(1, provider.requests.size)
        val run = organizer.jobs().single().runId
        provider.onDispatch = null
        record("during-pause")
        assertEquals(BackgroundPassStatus.PAUSED, process().status)
        assertEquals(1, provider.requests.size)
        organizer.resume()
        // This job is bound to an exact record; an independent input does not restart paid work.
        process()
        assertEquals(run, organizer.jobs().first { it.recordId == "pause" }.runId)
        assertTrue(organizer.jobs().any { it.recordId == "during-pause" })
    }

    @Test fun `pausing before provider dispatch cannot leak a prepared request`() {
        enable()
        record("preparation")
        provider.onPrepare = { organizer.pause() }
        assertEquals(BackgroundPassStatus.PAUSED, process().status)
        assertEquals(0, provider.requests.size)
        assertEquals(0, organizer.settings().reservedRequestsToday)
        provider.onPrepare = null
        organizer.resume()
        assertEquals(2, process().modelRequestsThisPass)
    }

    @Test fun `tightening the network requirement during a call stops the current pass`() {
        enable(BackgroundPolicy(enabled = true, unmeteredOnly = false))
        record("network-change")
        provider.onDispatch = { organizer.configure(organizer.policy().copy(unmeteredOnly = true)) }
        assertEquals(BackgroundPassStatus.CONSTRAINTS_CHANGED,
            organizer.process(provider, provider, scheduledUnmeteredOnly = false).status)
        assertEquals(1, provider.requests.size)
        provider.onDispatch = null
        // A previously enqueued request with the old constraint must also refuse to dispatch.
        assertEquals(BackgroundPassStatus.CONSTRAINTS_CHANGED,
            organizer.process(provider, provider, scheduledUnmeteredOnly = false).status)
        assertEquals(1, provider.requests.size)
        assertEquals(1, organizer.process(provider, provider, scheduledUnmeteredOnly = true).modelRequestsThisPass)
        assertEquals(BackgroundJobStatus.PROPOSALS_READY, organizer.jobs().single().status)
    }

    @Test fun `network policy change in provider preparation prevents the first network dispatch`() {
        enable(BackgroundPolicy(enabled = true, unmeteredOnly = false))
        record("network-before-dispatch")
        provider.onPrepare = { organizer.configure(organizer.policy().copy(unmeteredOnly = true)) }
        assertEquals(BackgroundPassStatus.CONSTRAINTS_CHANGED,
            organizer.process(provider, provider, scheduledUnmeteredOnly = false).status)
        assertTrue(provider.requests.isEmpty())
        assertEquals(0, organizer.settings().reservedRequestsToday)
    }

    @Test fun `disable and reenable drop prior scheduling intent without deleting analysis history`() {
        enable(BackgroundPolicy(enabled = true, maxRequestsPerPass = 1))
        record("prior")
        process()
        val run = organizer.jobs().single().runId!!
        organizer.configure(BackgroundPolicy())
        record("while-disabled")
        enable()
        assertTrue(organizer.jobs().isEmpty())
        assertEquals(0, process().modelRequestsThisPass)
        assertEquals(run, analysis.task(run).stored.id)
        assertEquals(1, organizer.settings().reservedRequestsToday)
    }

    @Test fun `restore revokes scheduling consent and never refunds same day requests`() {
        enable(BackgroundPolicy(enabled = true, maxRequestsPerPass = 1))
        record("restore")
        process()
        val archive = File(directory, "synthetic-snapshot").apply { check(mkdirs()) }
        val snapshot = repository.captureSnapshot(archive)
        repository.restoreSnapshot(snapshot, archive, repository.currentGeneration(), replaceExisting = true)
        assertEquals(BackgroundPassStatus.RESTORE_REQUIRES_ENABLE, process().status)
        assertFalse(organizer.policy().enabled)
        assertTrue(organizer.jobs().isEmpty())
        assertEquals(1, organizer.settings().reservedRequestsToday)
        assertEquals(1, provider.requests.size)
        enable()
        assertEquals(0, process().modelRequestsThisPass)
    }

    @Test fun `restore during request preparation blocks network dispatch`() {
        enable()
        record("restore-before-dispatch")
        provider.onPrepare = {
            val archive = File(directory, "synthetic-snapshot").apply { check(mkdirs()) }
            val snapshot = repository.captureSnapshot(archive)
            repository.restoreSnapshot(snapshot, archive, repository.currentGeneration(), replaceExisting = true)
        }
        process()
        assertEquals(0, provider.requests.size)
        assertFalse(organizer.policy().enabled)
        assertTrue(organizer.jobs().isEmpty())
    }

    @Test fun `operational queue cannot carry consent across source store identities`() {
        enable(BackgroundPolicy(enabled = true, maxRequestsPerPass = 1))
        record("identity")
        process()
        val otherName = "synthetic-background-other-${UUID.randomUUID()}.db"
        LifeRepository(context, clock, otherName).use { otherRepository ->
            IncrementalOrganizer(otherRepository, ControlledAnalysis(otherRepository, clock), directory, clock).use { other ->
                assertEquals(BackgroundPassStatus.RESTORE_REQUIRES_ENABLE, other.process(provider, provider).status)
                assertFalse(other.policy().enabled)
                assertTrue(other.jobs().isEmpty())
                assertEquals(1, other.settings().reservedRequestsToday)
                assertEquals(1, provider.requests.size)
            }
        }
        context.deleteDatabase(otherName)
    }

    @Test fun `clock rollback blocks work without resetting the daily ledger`() {
        enable(BackgroundPolicy(enabled = true, maxRequestsPerPass = 1))
        record("clock")
        process()
        clock.now = clock.now.minusSeconds(60)
        assertEquals(BackgroundPassStatus.CLOCK_ROLLBACK, process().status)
        assertEquals(1, organizer.settings().reservedRequestsToday)
        assertEquals(1, provider.requests.size)
        assertThrows(IllegalStateException::class.java) { organizer.configure(organizer.policy()) }
    }

    @Test fun `clock rollback inside a request does not move job metadata backwards`() {
        enable()
        record("during-request-clock")
        provider.onDispatch = { clock.now = clock.now.minusSeconds(60) }
        process()
        val job = organizer.jobs().single()
        assertTrue(job.updatedAt >= job.createdAt)
        assertEquals(1, job.reservedRequests)
        assertEquals(BackgroundPassStatus.CLOCK_ROLLBACK, process().status)
        assertEquals(1, provider.requests.size)
    }

    @Test fun `missing credentials and caller cancellation never consume a request`() {
        enable()
        record("credential")
        assertEquals(BackgroundPassStatus.CREDENTIALS_REQUIRED,
            organizer.process(provider, provider, credentialsAvailable = false).status)
        assertEquals(BackgroundPassStatus.CANCELLED, organizer.process(provider, provider, cancelled = { true }).status)
        assertEquals(0, organizer.settings().reservedRequestsToday)
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun `unknown dates remain an explicit coverage gap and negative opinions are not retried`() {
        enable()
        repository.createRecord(RecordDraft("合成未知日期：练习后的效果尚不确定。", Occurrence()), "unknown")
        provider.verdict = ReviewVerdict.INSUFFICIENT_CONTEXT
        process()
        assertEquals(BackgroundJobStatus.NEEDS_ATTENTION, organizer.jobs().single().status)
        assertEquals(2, provider.requests.size)
        repeat(2) { process() }
        assertEquals(2, provider.requests.size)
        assertEquals(1, JSONObject(provider.requests.first().payloadJson).getJSONObject("range").getInt("unknownTimeRecords"))
    }

    @Test fun `user rejection blocks review without automatic confirmation or publication`() {
        enable(BackgroundPolicy(enabled = true, maxRequestsPerPass = 1))
        record("rejected")
        process()
        val task = analysis.task(organizer.jobs().single().runId!!)
        val call = task.state.calls.single()
        val claim = call.proposal!!.claims.single()
        AnalysisFeedback(repository, clock).respond(task.stored.id,
            ClaimRef(call.batchKey, 0, AnalysisRunCodec.claimSha256(claim)), task.stored.version, ClaimFeedbackDecision.REJECTED)
        assertEquals(0, process().modelRequestsThisPass)
        assertEquals(BackgroundJobStatus.NEEDS_ATTENTION, organizer.jobs().single().status)
        assertEquals(1, provider.requests.size)
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertEquals(ClaimFeedbackDecision.REJECTED, repository.claimFeedbacks().single().decision)
    }

    @Test fun `another dispatcher cannot acquire the same durable job`() {
        enable()
        record("busy")
        val second = IncrementalOrganizer(repository, analysis, directory, clock)
        try {
            provider.onDispatch = { assertEquals(BackgroundPassStatus.BUSY, second.process(provider, provider).status) }
            assertEquals(2, process().modelRequestsThisPass)
            assertEquals(2, provider.requests.size)
        } finally { second.close() }
    }

    @Test fun `deleted and archived records are never sent from pending events`() {
        enable()
        val deleted = record("deleted")
        val archived = record("archived")
        organizer.synchronizeChanges()
        repository.deleteRecord(deleted.id, deleted.revision)
        repository.setArchived(archived.id, archived.revision, true)
        assertEquals(0, process().modelRequestsThisPass)
        assertTrue(organizer.jobs().all { it.status == BackgroundJobStatus.SOURCE_UNAVAILABLE })
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun `settings updates reject stale versions and invalid allowances`() {
        val first = organizer.settings()
        organizer.configure(BackgroundPolicy(enabled = true), first.version)
        assertThrows(BackgroundConflictException::class.java) { organizer.configure(BackgroundPolicy(), first.version) }
        assertThrows(IllegalArgumentException::class.java) { BackgroundPolicy(dailyRequestLimit = 0) }
        assertThrows(IllegalArgumentException::class.java) { BackgroundPolicy(maxRequestsPerPass = 11) }
        assertThrows(IllegalArgumentException::class.java) { BackgroundPolicy(enabled = false, paused = true) }
        assertThrows(IllegalArgumentException::class.java) { organizer.jobs(limit = 101) }
    }

    private fun enable(policy: BackgroundPolicy = BackgroundPolicy(enabled = true)) { organizer.configure(policy) }
    private fun process() = organizer.process(provider, provider)
    private fun record(id: String) = repository.createRecord(RecordDraft("合成记录：今天列出小目标后完成一次练习，结果需核对。", Occurrence(date)), id)
    private fun reopen() { organizer.close(); organizer = IncrementalOrganizer(repository, analysis, directory, clock) }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T12:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private class SyntheticProcessDeath : Error("Synthetic process interruption")
    private class SyntheticProvider : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-incremental"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<ModelRequest>()
        var failure = false
        var crash = false
        var verdict = ReviewVerdict.SUPPORTED
        var onPrepare: (() -> Unit)? = null
        var onDispatch: (() -> Unit)? = null
        override fun countInputTokens(request: ModelRequest) = 1_000
        override fun prepare(request: ModelRequest): PreparedModelRequest {
            onPrepare?.invoke()
            return PreparedModelRequest(request, request.payloadJson, 1_000)
        }
        override fun complete(request: ModelRequest): BatchProposal {
            dispatch(request)
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return BatchProposal(listOf(ProposedClaim("合成提案：记录中提到一次练习，适用范围待核对。", listOf(id), ClaimKind.REPORTED_EXPERIENCE)))
        }
        override fun prepareReview(request: ModelRequest) = prepare(request)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            dispatch(request.original)
            val id = JSONObject(request.original.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return ReviewCompletion(ClaimReview(verdict, supportingEvidenceIds = if (verdict == ReviewVerdict.SUPPORTED) listOf(id) else emptyList(),
                issues = if (verdict == ReviewVerdict.INSUFFICIENT_CONTEXT) listOf(ReviewIssue.INSUFFICIENT_CONTEXT) else emptyList()))
        }
        private fun dispatch(request: ModelRequest) {
            requests += request
            onDispatch?.invoke()
            if (crash) throw SyntheticProcessDeath()
            if (failure) throw IllegalStateException("synthetic provider failure")
        }
    }
}
