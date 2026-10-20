package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceFieldKind
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
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Real SQLite metadata and coverage, using synthetic records and a provider with empty proposals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TextAnalysisInventoryTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var database: String
    private val clock = MutableClock(Instant.parse("2026-10-20T03:00:00Z"))
    private val scope = AnalysisQuery(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-20"), "UTC")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = "synthetic-field-inventory-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(database) }

    @Test fun `metadata pages respect configured size scope unreadable gaps and unknown dates`() {
        repository.createRecord(RecordDraft("合成😀正文", Occurrence(scope.from)), "a-readable")
        repository.createRecord(RecordDraft("合成\u0000不可分片正文", Occurrence(scope.from)), "b-unreadable")
        repository.createRecord(RecordDraft("合成未知日期"), "c-unknown")
        repository.createRecord(RecordDraft("合成范围外正文", Occurrence(scope.from.minusDays(1))), "outside")
        val task = analysis.start(AnalysisRequest("合成元数据分页", scope, AnalysisLimits(pageSize = 2)))
        val first = analysis.fieldMetadataPage(task.stored.id, 0, task.stored.version)
        assertEquals(2, first.page.items.size)
        assertEquals(3, first.page.total)
        assertEquals(1, first.page.unknown)
        assertEquals(setOf("b-unreadable"), first.unreadableRecordIds)
        assertEquals(setOf("a-readable"), first.fields.keys)
        assertEquals(5, first.fields.getValue("a-readable").single().codePoints)
        assertEquals(SourceFieldKind.TEXT, first.fields.getValue("a-readable").single().kind)
        val second = analysis.fieldMetadataPage(task.stored.id, requireNotNull(first.page.nextOffset), task.stored.version)
        assertEquals(listOf("c-unknown"), second.page.items.map { it.id })
        assertNull(second.page.nextOffset)
        val inventory = TextAnalysisInventory(analysis).build(task, CallPurpose.EXTRACTION, 1)
        assertEquals(3, inventory.coverage.records)
        assertEquals(1, inventory.coverage.unreadableRecords)
        assertEquals(1, inventory.coverage.unknownTimeRecords)
        assertEquals(1, inventory.selections.size)
        assertFalse(inventory.selections.any { it.recordId in setOf("outside", "b-unreadable") })
    }

    @Test fun `paged inventory preserves purpose specific Unicode spans and bounded candidates`() {
        repeat(5) { index -> repository.createRecord(RecordDraft("合成😀记录", Occurrence(scope.from)), "record-$index") }
        val task = analysis.start(AnalysisRequest("合成覆盖", scope, AnalysisLimits(pageSize = 2, fragmentCodePoints = 2)))
        analysis.runBatch(task.stored.id, "first-four-codepoints", listOf(EvidenceSelection("record-0", "body", 0), EvidenceSelection("record-0", "body", 2)), EmptyProvider)
        val current = analysis.task(task.stored.id)
        val extraction = TextAnalysisInventory(analysis).build(current, CallPurpose.EXTRACTION, 1)
        assertEquals(25L, extraction.coverage.textCodePoints)
        assertEquals(4L, extraction.coverage.completedTextCodePoints)
        assertEquals(13L, extraction.coverage.remainingTextFragments)
        assertEquals(listOf(EvidenceSelection("record-0", "body", 4)), extraction.selections)
        val synthesis = TextAnalysisInventory(analysis).build(current, CallPurpose.SYNTHESIS, 0)
        assertEquals(0L, synthesis.coverage.completedTextCodePoints)
        assertEquals(15L, synthesis.coverage.remainingTextFragments)
        assertTrue(synthesis.selections.isEmpty())
    }

    @Test fun `new task progress cannot be combined with old coverage receipts`() {
        repository.createRecord(RecordDraft("合成进度", Occurrence(scope.from)), "record")
        val task = analysis.start(AnalysisRequest("合成版本检查", scope))
        analysis.runBatch(task.stored.id, "first", listOf(EvidenceSelection("record", "body")), EmptyProvider)
        assertThrows(RevisionConflictException::class.java) { analysis.fieldMetadataPage(task.stored.id, 0, task.stored.version) }
        assertThrows(RevisionConflictException::class.java) { TextAnalysisInventory(analysis).build(task, CallPurpose.EXTRACTION, 1) }
        assertEquals(4L, AnalysisBatchProcessor(analysis).coverage(task.stored.id).completedTextCodePoints)
    }

    @Test fun `metadata rejects changed sources and expiry even when a page or range is empty`() {
        val record = repository.createRecord(RecordDraft("合成记录", Occurrence(scope.from)), "record")
        val task = analysis.start(AnalysisRequest("合成来源检查", scope))
        repository.updateRecord(record.id, record.revision, record.draft.copy(body = "合成修改"))
        assertThrows(StaleSnapshotException::class.java) { analysis.fieldMetadataPage(task.stored.id, 100, task.stored.version) }
        val empty = analysis.start(AnalysisRequest("合成空范围", scope.copy(recordIds = setOf("missing")), expiresAt = clock.now.plusSeconds(1)))
        assertTrue(analysis.fieldMetadataPage(empty.stored.id, 0, empty.stored.version).fields.isEmpty())
        clock.now = clock.now.plusSeconds(1)
        assertThrows(StaleSnapshotException::class.java) { analysis.fieldMetadataPage(empty.stored.id, 0, empty.stored.version) }
    }

    private object EmptyProvider : AnalysisProvider {
        override val id = "synthetic-empty-inventory"
        override val contextWindowTokens = 128_000
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest) = BatchProposal(emptyList())
    }
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant() = now
    }
}
