package dev.tracelife.metrics

import android.content.Context
import dev.tracelife.dashboard.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.TemplateRef
import dev.tracelife.storage.LifeRepository
import dev.tracelife.templates.ActivityLogTemplate
import dev.tracelife.transfer.DataTransfer
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile

/** Explicit synthetic inputs, real SQLite and actual encrypted backup/export paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActivityMetricsTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var dashboard: DashboardService
    private lateinit var template: TemplateRef
    private lateinit var directory: File
    private val name = "synthetic-activity-${UUID.randomUUID()}.db"
    private val day = LocalDate.parse("2026-10-20")
    private val clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC)
    private val request = DashboardRequest(zoneId = "UTC")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        dashboard = DashboardService(repository, clock)
        template = ActivityLogTemplate.obtain(repository)
        directory = File(context.cacheDir, "synthetic-activity-${UUID.randomUUID()}").apply { check(mkdir()) }
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(name); directory.deleteRecursively() }

    @Test fun `explicit zeros differ from missing and prose mentions never become activity numbers`() {
        repository.createRecord(RecordDraft("合成：看了三小时课程，计划练习五次", Occurrence(day), setOf("学习")), "prose")
        save("learning", minutes = "45", practices = "2", outcome = "合成：完成一个可运行的小例子")
        save("zero", occurrence = Occurrence(day.minusDays(1)), minutes = "0")
        save("outcome-only", occurrence = Occurrence(day.minusDays(2)), outcome = "合成：整理出一页笔记")
        save("work", domain = "工作", minutes = "90", practices = "1")
        save("undated", occurrence = Occurrence(), minutes = "100", practices = "9")
        save("interval", occurrence = Occurrence(day.minusDays(2), day), minutes = "200", practices = "8")
        val snapshot = dashboard.open(request)
        val learning = snapshot.activityCurrent.series.single { it.domain == ActivityDomain.LEARNING }
        assertEquals(45, learning.minutes)
        assertEquals(2, learning.practices)
        assertEquals(2, learning.minutesRecordedDays)
        assertEquals(0, learning.points[5].minutes)
        assertNull(learning.points[4].minutes)
        assertNull(learning.points[5].practices)
        assertEquals(90, snapshot.activityCurrent.series.single { it.domain == ActivityDomain.WORK }.minutes)
        assertEquals(1, snapshot.activityCurrent.undatedEntries)
        assertEquals(1, snapshot.activityCurrent.intervalEntries)
        assertEquals(listOf("learning"), dashboard.activitySources(snapshot, domain = ActivityDomain.LEARNING,
            measure = ActivityMeasure.MINUTES, pointIndex = 6).items.map { it.recordId })
        assertEquals(setOf("learning", "outcome-only"), dashboard.activitySources(snapshot, measure = ActivityMeasure.OUTCOMES).items.map { it.recordId }.toSet())
        assertEquals("合成：完成一个可运行的小例子", dashboard.sourceText(snapshot, "learning", "answer:${ActivityLogTemplate.OUTCOME}", 0, 100).text)
    }

    @Test fun `revisions archive and deletion invalidate old board and preserve original answers`() {
        val first = save("activity", minutes = "30", practices = "1")
        val old = dashboard.open(request)
        val revised = repository.updateRecord(first.id, first.revision, first.draft.copy(answers = first.draft.answers + (ActivityLogTemplate.MINUTES to "55")))
        assertThrows(StaleSnapshotException::class.java) { dashboard.activitySources(old) }
        assertEquals(55, dashboard.open(request).activityCurrent.series.single { it.domain == ActivityDomain.LEARNING }.minutes)
        assertEquals("30", repository.record(first.id, 1)!!.draft.answers[ActivityLogTemplate.MINUTES])
        val archived = repository.setArchived(revised.id, revised.revision, true)
        assertNull(dashboard.open(request).activityCurrent.series.single { it.domain == ActivityDomain.LEARNING }.minutes)
        val withArchived = dashboard.open(request.copy(includeArchived = true))
        assertEquals(55, withArchived.activityCurrent.series.single { it.domain == ActivityDomain.LEARNING }.minutes)
        repository.deleteRecord(archived.id, archived.revision)
        assertThrows(StaleSnapshotException::class.java) { dashboard.activitySources(withArchived) }
        assertTrue(dashboard.activitySources(dashboard.open(request)).items.isEmpty())
    }

    @Test fun `changed template meaning and invalid scalars are never guessed while old bound version survives`() {
        save("original", minutes = "25", practices = "1")
        val original = repository.template(template.id, template.version)!!
        val changed = repository.saveTemplate(original.draft.copy(questions = original.draft.questions.map {
            if (it.id == ActivityLogTemplate.MINUTES) it.copy(prompt = "计划小时") else it
        }), original.id, original.version)
        save("changed", minutes = "200", templateRef = TemplateRef(changed.id, changed.version))
        save("malformed", minutes = "1小时", practices = "2")
        save("truncated", minutes = "30" + " ".repeat(70) + "小时", practices = "-2")
        val snapshot = dashboard.open(request)
        val learning = snapshot.activityCurrent.series.single { it.domain == ActivityDomain.LEARNING }
        assertEquals(25, learning.minutes)
        assertEquals(3, learning.practices)
        assertEquals(3, snapshot.activityCurrent.entriesNeedingCorrection)
        assertEquals(3, dashboard.activitySources(snapshot, correctionsOnly = true).total)
        val compatible = ActivityLogTemplate.obtain(repository)
        assertNotEquals(changed.id, compatible.id)
        assertEquals(changed.version, repository.template(changed.id)!!.version)
    }

    @Test fun `ninety day totals keep actual partial week coverage and previous window separate`() {
        save("first", occurrence = Occurrence(day.minusDays(89)), minutes = "15", practices = "1")
        save("last", minutes = "25", practices = "2")
        save("previous", occurrence = Occurrence(day.minusDays(90)), minutes = "300", practices = "8")
        val snapshot = dashboard.open(request.copy(window = DashboardWindow.DAYS_90))
        val current = snapshot.activityCurrent.series.single { it.domain == ActivityDomain.LEARNING }
        val previous = snapshot.activityPrevious.series.single { it.domain == ActivityDomain.LEARNING }
        assertEquals(90, current.points.sumOf { it.days })
        assertEquals(day.minusDays(89), current.points.first().from)
        assertEquals(day, current.points.last().through)
        assertEquals(40, current.minutes)
        assertEquals(3, current.practices)
        assertEquals(2, current.minutesRecordedDays)
        assertEquals(300, previous.minutes)
        assertTrue(current.points.any { it.minutes == null })
        assertTrue(current.points.last().inProgress)
    }

    @Test fun `encrypted backup restores bound templates histories and exact numeric meaning`() {
        val first = save("backup", minutes = "12", practices = "0", outcome = "合成备份成果")
        repository.updateRecord(first.id, first.revision, first.draft.copy(answers = first.draft.answers + (ActivityLogTemplate.MINUTES to "17")))
        val sourceTransfer = DataTransfer(context, repository, clock)
        val archive = File(directory, "activity.tlb")
        val password = "synthetic-recovery-passphrase".toCharArray()
        sourceTransfer.exportBackup(archive, password)
        val targetName = "synthetic-activity-target-${UUID.randomUUID()}.db"
        LifeRepository(context, clock, targetName).use { target ->
            val transfer = DataTransfer(context, target, clock)
            transfer.restoreBackup(archive, password, transfer.previewBackup(archive, password))
            val snapshot = DashboardService(target, clock).open(request)
            val value = snapshot.activityCurrent.series.single { it.domain == ActivityDomain.LEARNING }
            assertEquals(17, value.minutes)
            assertEquals(0, value.practices)
            assertEquals(2, target.history(first.id).size)
            assertEquals("12", target.record(first.id, 1)!!.draft.answers[ActivityLogTemplate.MINUTES])
        }
        context.deleteDatabase(targetName)
    }

    @Test fun `dashboard export distinguishes null from zero and points to outcomes without copying private prose`() {
        save("export", minutes = "0", outcome = "PRIVATE_SYNTHETIC_OUTCOME")
        val destination = File(directory, "dashboard.zip")
        DataTransfer(context, repository, clock).exportDashboard(destination, request)
        ZipFile(destination).use { zip ->
            fun text(path: String) = zip.getInputStream(zip.getEntry(path)).bufferedReader().use { it.readText() }
            assertEquals(2, JSONObject(text("manifest.json")).getInt("formatVersion"))
            val series = JSONObject(text("activities/current.json")).getJSONArray("series").getJSONObject(1)
            assertEquals(0, series.getInt("minutes"))
            assertTrue(series.isNull("practices"))
            val row = JSONObject(text("activities/current/page-00001.json")).getJSONArray("entries").getJSONObject(0)
            assertEquals("answer:${ActivityLogTemplate.OUTCOME}", row.getString("outcomeField"))
            assertFalse(row.getBoolean("bodyIncluded"))
            assertTrue(text("activities/current.csv").contains(",0,,1,0"))
            zip.entries().asSequence().forEach { entry -> assertFalse(text(entry.name).contains("PRIVATE_SYNTHETIC_OUTCOME")) }
        }
    }

    private fun save(id: String, occurrence: Occurrence = Occurrence(day), domain: String = "学习", minutes: String = "",
        practices: String = "", outcome: String = "", templateRef: TemplateRef = template) = repository.createRecord(
        RecordDraft("合成活动：$id", occurrence, setOf(domain), templateRef,
            mapOf(ActivityLogTemplate.DOMAIN to domain, ActivityLogTemplate.MINUTES to minutes,
                ActivityLogTemplate.PRACTICES to practices, ActivityLogTemplate.OUTCOME to outcome).filterValues { it.isNotBlank() }), id)
}
