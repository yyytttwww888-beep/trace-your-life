package dev.tracelife.metrics

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.transfer.StaleSnapshotException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecordMetricsTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var metrics: RecordMetrics
    private val name = "synthetic-metrics-${UUID.randomUUID()}.db"
    private val start = LocalDate.parse("2026-10-12")
    private val scope = AnalysisQuery(start, start.plusDays(6), "Asia/Shanghai")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, databaseName = name)
        metrics = RecordMetrics(repository, Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC))
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name) }

    @Test fun `daily counts distinguish exact dates intervals and unknown time without inventing activity`() {
        create("a", start); create("b", start); create("c", start.plusDays(2))
        create("range", start.plusDays(1), start.plusDays(3))
        create("overlap", start.minusDays(1), start.plusDays(1))
        create("unknown", null)
        create("outside", start.minusDays(4))
        val report = metrics.calculate(scope)
        assertEquals(6, report.totalRecords)
        assertEquals(3, report.exactDateRecords)
        assertEquals(2, report.intervalRecords)
        assertEquals(1, report.overlappingRecords)
        assertEquals(1, report.unknownTimeRecords)
        assertEquals(2, report.recordedDays)
        assertEquals(7, report.daysInScope)
        assertEquals(listOf(2, 0, 1, 0, 0, 0, 0), report.daily.map { it.records })
    }

    @Test fun `all source pages participate and record revisions are counted once`() {
        repeat(105) { create("note-$it", start.plusDays((it % 7).toLong())) }
        val original = repository.record("note-0")!!
        repository.updateRecord(original.id, original.revision, original.draft.copy(body = "合成修订：内容变化不增加事件数"))
        val report = metrics.calculate(scope)
        assertEquals(105, report.totalRecords)
        assertEquals(List(7) { 15 }, report.daily.map { it.records })
        val first = metrics.sources(report, limit = 100)
        val last = metrics.sources(report, first.nextOffset!!, 100)
        assertEquals(105, (first.items + last.items).map { it.id }.distinct().size)
        assertNull(last.nextOffset)
    }

    @Test fun `archive exclusion exact IDs and later source changes stay explicit`() {
        create("active", start); create("archived", start)
        repository.setArchived("archived", 1, true)
        val query = scope.copy(recordIds = setOf("active", "archived", "missing"))
        val report = metrics.calculate(query)
        assertEquals(1, report.totalRecords)
        assertEquals(setOf("archived", "missing"), report.missingExactIds)
        assertEquals(2, metrics.calculate(query.copy(includeArchived = true)).totalRecords)
        repository.deleteRecord("active", 1)
        assertThrows(StaleSnapshotException::class.java) { metrics.sources(report) }
        assertEquals(0, metrics.calculate(scope).totalRecords)
    }

    @Test fun `empty range is empty record coverage with bounded chart points`() {
        val report = metrics.calculate(scope)
        assertEquals(0, report.recordedDays)
        assertEquals(7, report.daily.size)
        assertTrue(report.daily.all { it.records == 0 })
        assertThrows(IllegalArgumentException::class.java) { metrics.calculate(scope.copy(through = start.plusDays(366))) }
    }

    private fun create(id: String, first: LocalDate?, last: LocalDate? = first) {
        repository.createRecord(RecordDraft("合成资料：指标仅统计记录，不推断活动或健康。", Occurrence(first, last)), id)
    }
}
