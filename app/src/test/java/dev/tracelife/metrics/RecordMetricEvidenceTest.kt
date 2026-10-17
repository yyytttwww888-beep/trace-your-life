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
class RecordMetricEvidenceTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private val name = "synthetic-metric-evidence-${UUID.randomUUID()}.db"
    private val day = LocalDate.parse("2026-10-12")
    private val scope = AnalysisQuery(day, day.plusDays(6), "Asia/Shanghai")
    private val firstClock = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, databaseName = name)
        repository.createRecord(RecordDraft("合成资料：一次记录。", Occurrence(day)), "sample")
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name) }

    @Test fun `identity excludes calculation time but binds scope and exact ID selection`() {
        val first = RecordMetricEvidenceService(repository, firstClock).calculate(scope)
        val later = RecordMetricEvidenceService(repository, Clock.offset(firstClock, java.time.Duration.ofDays(1))).calculate(scope)
        assertEquals(first.id, later.id)
        assertNotEquals(first.report.calculatedAt, later.report.calculatedAt)
        val scoped = RecordMetricEvidenceService(repository).calculate(scope.copy(recordIds = setOf("sample")))
        assertEquals(first.report.totalRecords, scoped.report.totalRecords)
        assertNotEquals(first.id, scoped.id)
        assertNotEquals(first.id, RecordMetricEvidenceService(repository).calculate(scope.copy(zoneId = "UTC")).id)
    }

    @Test fun `describe rejects altered totals daily values and definition instead of certifying caller data`() {
        val service = RecordMetricEvidenceService(repository)
        val report = service.calculate(scope).report
        assertThrows(IllegalArgumentException::class.java) { service.describe(report.copy(totalRecords = 99)) }
        assertThrows(IllegalArgumentException::class.java) {
            service.describe(report.copy(daily = report.daily.map { it.copy(records = 99) }))
        }
        assertThrows(IllegalArgumentException::class.java) { service.describe(report.copy(definitionVersion = 2)) }
        assertEquals(service.calculate(scope).id, service.describe(report).id)
    }

    @Test fun `deletion prevents old source drilldown and old totals from being issued`() {
        val service = RecordMetricEvidenceService(repository)
        val evidence = service.calculate(scope)
        assertEquals(listOf("sample"), service.sources(evidence, limit = 1).items.map { it.id })
        repository.deleteRecord("sample", 1)
        assertThrows(StaleSnapshotException::class.java) { service.sources(evidence) }
        assertThrows(StaleSnapshotException::class.java) { service.describe(evidence.report) }
        assertNotEquals(evidence.id, service.calculate(scope).id)
    }

    @Test fun `caller set mutations do not change issued scope and input set order does not change identity`() {
        val ids = linkedSetOf("sample", "missing")
        val service = RecordMetricEvidenceService(repository)
        val first = service.calculate(scope.copy(recordIds = ids))
        val second = service.calculate(scope.copy(recordIds = linkedSetOf("missing", "sample")))
        ids.clear()
        assertEquals(first.id, second.id)
        assertEquals(setOf("sample", "missing"), first.report.scope.recordIds)
        assertEquals(setOf("missing"), first.report.missingExactIds)
        assertEquals(1, service.sources(first).total)
    }
}
