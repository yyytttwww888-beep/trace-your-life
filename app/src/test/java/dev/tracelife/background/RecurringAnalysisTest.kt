package dev.tracelife.background

import android.app.Application
import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.profile.ProfileDimension
import dev.tracelife.profile.ProfileLayer
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
import java.time.*
import java.util.UUID

/** Real local ledgers, synthetic evidence and provider. No cloud credentials or requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecurringAnalysisTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var organizer: IncrementalOrganizer
    private lateinit var directory: File
    private val database = "synthetic-recurring-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val provider = SyntheticProvider()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        directory = File(context.noBackupFilesDir, "synthetic-recurring-${UUID.randomUUID()}")
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        organizer = IncrementalOrganizer(repository, analysis, directory, clock)
    }
    @After fun tearDown() {
        organizer.close(); repository.close(); directory.deleteRecursively(); context.deleteDatabase(database)
    }

    @Test fun `options default off and first enable never backfills closed historical periods`() {
        record("old", "2026-08-10")
        assertFalse(organizer.policy().weeklyReflection)
        assertNull(organizer.policy().monthlyProfileDimension)
        enable()
        assertEquals(0, organizer.synchronizePeriods())
        assertEquals(0, process().modelRequestsThisPass)
        assertTrue(organizer.jobs().isEmpty())
        clock.now = Instant.parse("2026-10-26T00:00:00Z")
        assertEquals(1, organizer.synchronizePeriods())
        val job = organizer.jobs().single()
        assertEquals(BackgroundJobKind.WEEKLY_REFLECTION, job.kind)
        assertEquals(LocalDate.parse("2026-10-19"), job.from)
        assertEquals(LocalDate.parse("2026-10-25"), job.through)
        assertEquals(0, organizer.synchronizePeriods())
        reopen()
        assertEquals(0, organizer.synchronizePeriods())
        assertEquals(job.id, organizer.jobs().single().id)
    }

    @Test fun `weekly reflection reads across records and produces a reviewable draft without publishing`() {
        record("first", "2026-10-19"); record("second", "2026-10-20")
        enable(); dueWeek()
        process()
        val job = organizer.jobs().single()
        assertEquals(BackgroundJobStatus.PROPOSALS_READY, job.status)
        val task = analysis.task(requireNotNull(job.runId))
        assertTrue(task.state.request.actionPeriod)
        assertEquals(setOf("first", "second"), task.state.calls.flatMap { it.evidence }.map { it.recordId }.toSet())
        assertEquals(1, provider.requests.size)
        assertEquals(0, process().modelRequestsThisPass)
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertTrue(repository.claimFeedbacks().isEmpty())
    }

    @Test fun `monthly reflection and profile compare explicit complete months in shared queue`() {
        record("aug", "2026-08-15"); record("sep", "2026-09-15"); record("oct", "2026-10-19")
        enable(BackgroundPolicy(enabled = true, monthlyReflection = true,
            monthlyProfileDimension = ProfileDimension.ABILITIES_AND_STRATEGIES, maxRequestsPerPass = 10))
        clock.now = Instant.parse("2026-11-01T00:00:00Z")
        assertEquals(2, organizer.synchronizePeriods())
        process()
        val jobs = organizer.jobs()
        assertEquals(2, jobs.size)
        assertTrue(jobs.all { it.status in setOf(BackgroundJobStatus.PROPOSALS_READY, BackgroundJobStatus.NEEDS_ATTENTION) })
        val profile = jobs.single { it.kind == BackgroundJobKind.MONTHLY_PROFILE }
        assertEquals(LocalDate.parse("2026-08-01"), profile.from)
        assertEquals(LocalDate.parse("2026-10-31"), profile.through)
        val task = analysis.task(requireNotNull(profile.runId))
        assertEquals(ProfileLayer.LONG_TERM, task.state.request.profileTarget?.layer)
        assertEquals(3, task.state.request.profileTarget?.periods?.size)
        assertEquals(ProfileDimension.ABILITIES_AND_STRATEGIES, task.state.request.profileTarget?.dimension)
        assertEquals(3, provider.requests.size)
        assertEquals(3, organizer.settings().reservedRequestsToday)
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertTrue(repository.claimFeedbacks().isEmpty())
    }

    @Test fun `record and recurring work share one daily quota across restarts`() {
        record("prior", "2026-10-19")
        enable(BackgroundPolicy(enabled = true, weeklyReflection = true, dailyRequestLimit = 1, maxRequestsPerPass = 10))
        record("new", "2026-10-20")
        dueWeek()
        assertEquals(BackgroundPassStatus.DAILY_BUDGET_EXHAUSTED, process().status)
        assertEquals(1, provider.requests.size)
        reopen()
        process()
        assertEquals(1, provider.requests.size)
        assertEquals(1, organizer.settings().reservedRequestsToday)
        assertEquals(1, organizer.jobs().sumOf { it.reservedRequests })
    }

    @Test fun `failed periodic call waits for explicit retry and keeps spent quota`() {
        record("first", "2026-10-19"); enable(); dueWeek()
        provider.fail = true
        process()
        assertEquals(BackgroundJobStatus.FAILED, organizer.jobs().single().status)
        provider.fail = false
        repeat(2) { process() }
        assertEquals(1, provider.requests.size)
        val job = organizer.jobs().single()
        organizer.retry(job.id, job.version)
        process()
        assertEquals(2, provider.requests.size)
        assertEquals(2, organizer.jobs().single().reservedRequests)
        assertEquals(BackgroundJobStatus.PROPOSALS_READY, organizer.jobs().single().status)
    }

    @Test fun `interrupted periodic call is not dispatched again by a later worker`() {
        record("first", "2026-10-19"); enable(); dueWeek()
        provider.crash = true
        assertThrows(SyntheticProcessDeath::class.java) { process() }
        provider.crash = false
        reopen(); process()
        val job = organizer.jobs().single()
        assertEquals(BackgroundJobStatus.NEEDS_RECOVERY, job.status)
        assertEquals(1, provider.requests.size)
        organizer.retry(job.id, job.version)
        process()
        assertEquals(2, provider.requests.size)
        assertEquals(2, organizer.jobs().single().reservedRequests)
    }

    @Test fun `pausing a prepared periodic request prevents dispatch and resumes the same run`() {
        record("first", "2026-10-19"); enable(); dueWeek()
        provider.onPrepare = { organizer.pause() }
        assertEquals(BackgroundPassStatus.PAUSED, process().status)
        assertTrue(provider.requests.isEmpty())
        val runId = organizer.jobs().single().runId
        provider.onPrepare = null
        organizer.resume(); process()
        assertEquals(runId, organizer.jobs().single().runId)
        assertEquals(1, provider.requests.size)
    }

    @Test fun `disabling a periodic option during preparation cancels it without sending`() {
        record("first", "2026-10-19"); enable(); dueWeek()
        provider.onPrepare = { organizer.configure(organizer.policy().copy(weeklyReflection = false)) }
        assertEquals(BackgroundPassStatus.CONSTRAINTS_CHANGED, process().status)
        assertTrue(provider.requests.isEmpty())
        assertEquals(BackgroundJobStatus.SUPERSEDED, organizer.jobs().single().status)
        provider.onPrepare = null
        process()
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun `changed source requires explicit rebuild and preserves prior draft`() {
        val original = record("first", "2026-10-19"); enable(); dueWeek(); process()
        val old = organizer.jobs().single()
        repository.updateRecord(original.id, original.revision, original.draft.copy(body = "合成修正：没有完成，之前记录有误。"))
        val changed = organizer.jobs().single()
        assertEquals(BackgroundJobStatus.REBUILD_REQUIRED, changed.status)
        assertEquals(1, provider.requests.size)
        organizer.retry(changed.id, changed.version)
        process()
        val current = organizer.jobs().single { it.kind == BackgroundJobKind.WEEKLY_REFLECTION }
        assertNotEquals(old.runId, current.runId)
        assertEquals(2, current.runSequence)
        assertEquals(2, current.reservedRequests)
        assertEquals(old.runId, analysis.task(requireNotNull(old.runId)).stored.id)
    }

    @Test fun `deleting a finished analysis cannot revive it from the calendar queue`() {
        record("first", "2026-10-19"); enable(); dueWeek(); process()
        val job = organizer.jobs().single()
        val task = analysis.task(requireNotNull(job.runId))
        repository.deleteAnalysisRun(task.stored.id, task.stored.version)
        val deleted = organizer.jobs().single()
        assertEquals(BackgroundJobStatus.SOURCE_UNAVAILABLE, deleted.status)
        assertThrows(IllegalArgumentException::class.java) { organizer.retry(deleted.id, deleted.version) }
        process()
        assertEquals(1, provider.requests.size)
        assertEquals(0, organizer.synchronizePeriods())
    }

    @Test fun `pause retains bounded backlog while disable resets future authorization`() {
        enable(); organizer.pause()
        clock.now = clock.now.plus(Duration.ofDays(90))
        assertEquals(0, organizer.synchronizePeriods())
        organizer.resume()
        assertEquals(8, organizer.synchronizePeriods())
        assertEquals(8, organizer.jobs().size)
        organizer.configure(organizer.policy().copy(enabled = false, paused = false))
        enable()
        assertTrue(organizer.jobs().isEmpty())
        assertEquals(0, organizer.synchronizePeriods())
    }

    @Test fun `restore cancels recurring authorization and preserves already reserved allowance`() {
        record("first", "2026-10-19"); enable(); dueWeek(); process()
        val files = File(directory, "synthetic-backup").apply { mkdirs() }
        val snapshot = repository.captureSnapshot(files)
        repository.restoreSnapshot(snapshot, files, repository.currentGeneration(), replaceExisting = true)
        assertEquals(BackgroundPassStatus.RESTORE_REQUIRES_ENABLE, process().status)
        assertFalse(organizer.policy().enabled)
        assertEquals(1, organizer.settings().reservedRequestsToday)
        assertTrue(organizer.jobs().isEmpty())
        enable()
        assertEquals(0, organizer.synchronizePeriods())
        assertEquals(1, provider.requests.size)
    }

    @Test fun `insufficient profile periods preserve a visible no request result`() {
        record("only-oct", "2026-10-19")
        enable(BackgroundPolicy(enabled = true, monthlyProfileDimension = ProfileDimension.ABILITIES_AND_STRATEGIES))
        clock.now = Instant.parse("2026-11-01T00:00:00Z")
        process()
        assertEquals(BackgroundJobStatus.NEEDS_ATTENTION, organizer.jobs().single().status)
        assertTrue(provider.requests.isEmpty())
        assertEquals(0, organizer.settings().reservedRequestsToday)
    }

    private fun record(id: String, date: String) = repository.createRecord(
        RecordDraft("合成经历：在有限时间内做了一个小练习，实际作用仍需继续观察。", Occurrence(LocalDate.parse(date))), id)
    private fun enable(policy: BackgroundPolicy = BackgroundPolicy(enabled = true, weeklyReflection = true)) { organizer.configure(policy) }
    private fun process() = organizer.process(provider, provider)
    private fun dueWeek() { clock.now = Instant.parse("2026-10-26T00:00:00Z") }
    private fun reopen() { organizer.close(); organizer = IncrementalOrganizer(repository, analysis, directory, clock) }
    private class MutableClock(var now: Instant = Instant.parse("2026-10-20T12:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
    private class SyntheticProcessDeath : Error("Synthetic process interruption")
    private class SyntheticProvider : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-recurring-provider"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<ModelRequest>()
        var fail = false
        var crash = false
        var onPrepare: (() -> Unit)? = null
        override fun countInputTokens(request: ModelRequest) = 100
        override fun prepare(request: ModelRequest): PreparedModelRequest {
            onPrepare?.invoke()
            return PreparedModelRequest(request, request.payloadJson, 100)
        }
        override fun complete(request: ModelRequest): BatchProposal {
            dispatch(request)
            return BatchProposal(listOf(ProposedClaim("值得留下：合成记录显示曾尝试小练习，作用和适用条件仍待继续观察。",
                ids(JSONObject(request.payloadJson)), ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareReview(request: ModelRequest) = prepare(request)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            dispatch(request.original)
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, supportingEvidenceIds = ids(JSONObject(request.original.payloadJson))))
        }
        private fun dispatch(request: ModelRequest) {
            requests += request
            if (crash) throw SyntheticProcessDeath()
            if (fail) error("Synthetic failure")
        }
        private fun ids(json: JSONObject): List<String> = listOf("evidence", "actionEvidence", "derivedInputs").flatMap { key ->
            json.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("id") } }.orEmpty()
        }
    }
}
