package dev.tracelife.metrics

import android.content.Context
import dev.tracelife.analysis.AnalysisRunCodec
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
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecordMetricProtocolTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private val name = "synthetic-metric-protocol-${UUID.randomUUID()}.db"
    private val start = LocalDate.parse("2026-10-12")
    private val scope = AnalysisQuery(start, start.plusDays(6), "Asia/Shanghai")
    @Before fun setup() { context = RuntimeEnvironment.getApplication(); repository = LifeRepository(context, databaseName = name) }
    @After fun cleanup() { repository.close(); context.deleteDatabase(name) }

    @Test fun `all pages latest revisions and date classes match dashboard arithmetic`() {
        repeat(105) { create("synthetic-$it", start.plusDays((it % 7).toLong())) }
        val old = repository.record("synthetic-0")!!
        repository.updateRecord(old.id, old.revision, old.draft.copy(occurrence = Occurrence(start.plusDays(2))))
        create("interval", start.plusDays(1), start.plusDays(3))
        create("overlap", start.minusDays(1), start.plusDays(1))
        create("unknown", null)
        create("outside", start.minusDays(5))
        val metric = calculate()
        val chart = RecordMetrics(repository).calculate(scope)
        assertEquals(108, metric.sources.size)
        assertEquals(2, metric.sources.single { it.recordId == old.id }.revision)
        assertEquals(chart.totalRecords, metric.values.totalRecords)
        assertEquals(chart.daily, metric.values.daily)
        assertEquals(2, metric.values.intervalRecords)
        assertEquals(1, metric.values.overlappingRecords)
        assertEquals(1, metric.values.unknownTimeRecords)
        RecordMetricProtocol.validate(metric, records = originals())
        assertEquals(metric, RecordMetricProtocol.parse(RecordMetricProtocol.map(metric)))
    }

    @Test fun `model data carries definition and digest without source manifest or original text`() {
        create("synthetic-secret-record-id", start)
        val receipt = calculate()
        val payload = AnalysisRunCodec.json(RecordMetricProtocol.modelMap(receipt))
        assertFalse(payload.contains("synthetic-secret-record-id"))
        assertFalse(payload.contains("合成原文"))
        assertTrue(payload.contains("PROGRAM_CALCULATED_METRIC"))
        assertTrue(payload.contains("sourceManifestSha256"))
        assertTrue(payload.contains("zero_means_no_exact_date_record_not_zero_activity"))
        assertTrue(payload.contains("record-coverage"))
    }

    @Test fun `zero records and explicit absent or archived IDs remain scoped gaps`() {
        val empty = calculate()
        assertTrue(empty.sources.isEmpty())
        assertEquals(List(7) { 0 }, empty.values.daily.map { it.records })
        create("archived", start)
        repository.setArchived("archived", 1, true)
        val narrowed = scope.copy(recordIds = setOf("archived", "absent"))
        val metric = calculate(narrowed)
        assertEquals(setOf("archived", "absent"), metric.values.missingExactIds)
        assertEquals(1, calculate(narrowed.copy(includeArchived = true)).values.totalRecords)
        assertNotEquals(empty.id, metric.id)
        assertThrows(IllegalArgumentException::class.java) { RecordMetricProtocol.validate(metric, scope = scope) }
    }

    @Test fun `self consistent forged date allocation is rejected against actual revisions`() {
        create("source", start)
        val metric = calculate()
        val changed = resign(metric.copy(values = metric.values.copy(daily = metric.values.daily.mapIndexed { index, day ->
            day.copy(records = if (index == 1) 1 else 0)
        })))
        RecordMetricProtocol.validate(changed)
        assertThrows(IllegalArgumentException::class.java) { RecordMetricProtocol.validate(changed, records = originals()) }
        assertThrows(IllegalArgumentException::class.java) { RecordMetricProtocol.validateCurrent(repository, changed) }
    }

    @Test fun `missing members can be arithmetic valid history but never accepted as current full scope`() {
        val before = calculate()
        create("new", start)
        val forged = resign(before.copy(source = repository.analysisSourceStamp()))
        RecordMetricProtocol.validate(forged, records = originals())
        assertThrows(IllegalArgumentException::class.java) { RecordMetricProtocol.validateSnapshot(forged, repository.analysisSourceStamp(), originals()) }
        assertThrows(IllegalArgumentException::class.java) { RecordMetricProtocol.validateCurrent(repository, forged) }
    }

    @Test fun `duplicate sources wrong revision and old source cannot receive provenance`() {
        create("source", start)
        val metric = calculate()
        assertThrows(IllegalArgumentException::class.java) { RecordMetricProtocol.validate(resign(metric.copy(sources = metric.sources + metric.sources))) }
        val wrong = resign(metric.copy(sources = listOf(RecordMetricSource("source", 9))))
        assertThrows(IllegalArgumentException::class.java) { RecordMetricProtocol.validate(wrong, records = originals()) }
        repository.deleteRecord("source", 1)
        assertThrows(StaleSnapshotException::class.java) { RecordMetricProtocol.validateCurrent(repository, metric) }
        assertThrows(StaleSnapshotException::class.java) { RecordMetricEvidenceService(repository).sources(metric) }
    }

    @Test fun `parser rejects changed definition unknown fields fractional numbers and reordered dates`() {
        val metric = calculate()
        val map = RecordMetricProtocol.map(metric)
        assertThrows(IllegalArgumentException::class.java) { RecordMetricProtocol.parse(map + ("definitionVersion" to 2)) }
        assertThrows(IllegalArgumentException::class.java) { RecordMetricProtocol.parse(map + ("unknown" to 1)) }
        val values = map["values"] as Map<*, *>
        assertThrows(IllegalStateException::class.java) { RecordMetricProtocol.parse(map + ("values" to (values + ("totalRecords" to 0.5)))) }
        assertThrows(IllegalArgumentException::class.java) { RecordMetricProtocol.validate(resign(metric.copy(values = metric.values.copy(daily = metric.values.daily.reversed())))) }
    }

    @Test fun `scope mutation cannot change numeric report or typed receipt source drilldown`() {
        create("first", start); create("second", start.plusDays(1))
        val ids = linkedSetOf("first")
        val query = scope.copy(recordIds = ids)
        val report = RecordMetrics(repository).calculate(query)
        val receipt = calculate(query)
        ids.clear(); ids.add("second")
        assertEquals(setOf("first"), report.scope.recordIds)
        assertEquals(setOf("first"), receipt.scope.recordIds)
        assertEquals("first", RecordMetrics(repository).sources(report).items.single().id)
        assertEquals("first", RecordMetricEvidenceService(repository).sources(receipt).items.single().id)
        assertThrows(UnsupportedOperationException::class.java) { (receipt.sources as MutableList).clear() }
    }

    @Test fun `receipt identity is stable across calls and binds timezone archive filter and source revisions`() {
        create("source", start)
        assertEquals(calculate(), calculate())
        assertNotEquals(calculate().id, calculate(scope.copy(zoneId = "UTC")).id)
        assertNotEquals(calculate().id, calculate(scope.copy(includeArchived = true)).id)
        val first = calculate()
        val old = repository.record("source")!!
        repository.updateRecord(old.id, old.revision, old.draft.copy(body = "合成资料：不同修订"))
        assertEquals(first.values, calculate().values)
        assertNotEquals(first.id, calculate().id)
    }

    private fun originals() = repository.listRecords(dev.tracelife.model.RecordFilter(includeArchived = true, limit = 200)).items.flatMap { repository.history(it.id) }
    private fun calculate(query: AnalysisQuery = scope) = RecordMetricProtocol.calculate(repository, repository.analysisSourceStamp(), query)
    private fun create(id: String, first: LocalDate?, last: LocalDate? = first) {
        repository.createRecord(RecordDraft("合成原文：不发送至指标模型。", Occurrence(first, last)), id)
    }
    private fun resign(value: RecordMetricReceipt): RecordMetricReceipt = value.copy(id = "metric-v1-" + AnalysisRunCodec.sha256(
        AnalysisRunCodec.json(mapOf("protocol" to "trace-life-metric-receipt-v1", "content" to (RecordMetricProtocol.map(value) - "id")))))
}
