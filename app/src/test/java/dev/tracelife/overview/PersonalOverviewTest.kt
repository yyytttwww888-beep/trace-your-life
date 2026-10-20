package dev.tracelife.overview

import android.content.Context
import dev.tracelife.actions.ActionDraft
import dev.tracelife.actions.ActionExperiments
import dev.tracelife.model.*
import dev.tracelife.storage.LifeRepository
import dev.tracelife.transfer.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
import java.util.zip.ZipFile

/** Synthetic original statements exercise the same record, backup and export interfaces as the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PersonalOverviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var overview: PersonalOverview
    private val database = "synthetic-overview-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val today = LocalDate.parse("2026-10-20")
    private val direction = PersonalDirection("合成阶段：新项目，每天只有半小时。", "合成目标：做一个能运行的练习。",
        "合成关注：给休息留出时间。", "合成信号：能否讲清并运行一个例子。", today, today.plusDays(7), "Asia/Shanghai")

    @Before fun setup() { context = RuntimeEnvironment.getApplication(); repository = LifeRepository(context, clock, database); overview = PersonalOverview(repository, clock) }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `direction is an ordinary versioned source and retries do not duplicate it`() {
        val first = overview.save(direction, creationId = "direction")
        assertEquals(first, overview.save(direction, creationId = "direction"))
        assertEquals(1, overview.open().directionRecords)
        assertEquals(direction, overview.open().direction!!.direction)
        clock.now = clock.now.plusSeconds(1)
        val revised = overview.save(direction.copy(goals = "合成修订目标：先休息，再做练习。"), first.id, first.revision)
        assertEquals(2, revised.revision)
        assertEquals(direction.goals, repository.history(first.id).last().draft.answers[OverviewNotes.GOALS])
        assertEquals(revised, overview.open().direction!!.record)
        assertThrows(RevisionConflictException::class.java) { overview.save(direction, first.id, first.revision) }
        assertEquals(0, repository.analysisRuns(0, 10).size)
    }

    @Test fun `ordinary tags and similar template names cannot invent a direction`() {
        repository.createRecord(RecordDraft("合成资料：当前方向只是这句话的主题。", Occurrence(today), setOf("个人方向")), "ordinary")
        val fake = repository.saveTemplate(TemplateDraft("当前方向", Cadence.EVENT,
            questions = listOf(TemplateQuestion(OverviewNotes.GOALS, "其他问题"))))
        repository.createRecord(RecordDraft(template = TemplateRef(fake.id, fake.version), occurrence = Occurrence(today),
            answers = mapOf(OverviewNotes.GOALS to "合成文字不能冒充专门填写的目标")), "other-template")
        assertNull(overview.open().direction)
        assertEquals(0, overview.open().directionRecords)
    }

    @Test fun `archiving latest direction does not silently restore an older goal and deletion removes exported text`() {
        overview.save(direction.copy(goals = "合成较早目标"), creationId = "old")
        clock.now = clock.now.plusSeconds(1)
        val newer = overview.save(direction.copy(goals = "合成最新方向唯一标记"), creationId = "new")
        val archived = repository.setArchived(newer.id, newer.revision, true)
        assertNull(overview.open().direction)
        assertFalse(export().getValue("overview.md").contains("合成较早目标"))
        repository.deleteRecord(newer.id, archived.revision)
        assertTrue(export().values.none { it.contains("合成最新方向唯一标记") })
    }

    @Test fun `new template versions do not reinterpret earlier statements and ordinary record edits remain authoritative`() {
        val record = overview.save(direction, creationId = "direction")
        val ref = record.draft.template!!
        repository.saveTemplate(OverviewNotes.template.copy(name = "合成自定义模板"), ref.id, ref.version)
        assertEquals(direction, overview.open().direction!!.direction)
        val edited = repository.updateRecord(record.id, record.revision, record.draft.copy(
            body = "合成独立补充说明不能被专用编辑丢弃", topics = setOf("生活", "学习")))
        val saved = overview.save(direction.copy(attention = "合成新的关注"), record.id, edited.revision)
        assertEquals(edited.draft.body, saved.draft.body)
        assertEquals(edited.draft.topics, saved.draft.topics)
        assertEquals(ref, saved.draft.template)
    }

    @Test fun `overview export respects date and topic scope and never substitutes an older included statement`() {
        overview.save(direction.copy(statedOn = today.minusDays(15), goals = "合成过去的方向"), creationId = "old")
        clock.now = clock.now.plusSeconds(1)
        overview.save(direction.copy(goals = "合成现在的方向独有内容"), creationId = "current")
        val full = export()
        assertTrue(full.getValue("overview.md").contains("合成现在的方向独有内容"))
        assertTrue(full.getValue("README.md").contains("overview.md"))
        assertEquals("overview.md", JSONObject(full.getValue("manifest.json")).getString("overview"))
        val historical = export(ReadingExportOptions(scope = ExportScope(through = today.minusDays(1))))
        assertFalse(historical.getValue("overview.md").contains("合成过去的方向"))
        assertTrue(historical.values.none { it.contains("合成现在的方向独有内容") })
        val unrelated = export(ReadingExportOptions(scope = ExportScope(topics = setOf("其他"))))
        assertTrue(unrelated.values.none { it.contains("合成现在的方向独有内容") })
        val evidence = export(ReadingExportOptions(kind = ReadingPackageKind.EVIDENCE, question = "合成问题"))
        assertFalse("overview.md" in evidence)
    }

    @Test fun `overview excerpts are bounded and keep unicode source intact`() {
        val longGoal = "合成" + "😀月光".repeat(350) + "原文末尾独有标记"
        val record = overview.save(direction.copy(goals = longGoal), creationId = "direction")
        val entries = export()
        val text = entries.getValue("overview.md")
        assertTrue(text.contains("节选")); assertFalse(text.contains("原文末尾独有标记"))
        val original = entries.filterKeys { it.startsWith("records/") }.values.single()
        assertTrue(original.contains(longGoal))
        assertEquals(longGoal, repository.record(record.id)!!.draft.answers[OverviewNotes.GOALS])
        assertTrue(text.toByteArray().size < 16_384)
    }

    @Test fun `direction revisions survive complete backup restore and known deletion wins over an older backup`() {
        val first = overview.save(direction, creationId = "direction")
        overview.save(direction.copy(attention = "合成修订关注点"), first.id, first.revision)
        val backup = File(temporary.root, "synthetic-overview.tlb")
        val password = "synthetic-overview-password".toCharArray()
        DataTransfer(context, repository, clock).exportBackup(backup, password)
        val otherName = "synthetic-overview-restore-${UUID.randomUUID()}.db"
        LifeRepository(context, clock, otherName).use { target ->
            val transfer = DataTransfer(context, target, clock)
            transfer.restoreBackup(backup, password, transfer.previewBackup(backup, password))
            val restored = PersonalOverview(target, clock).open().direction!!
            assertEquals("合成修订关注点", restored.direction.attention)
            assertEquals(2, target.history(first.id).size)
            target.deleteRecord(first.id, restored.record.revision)
            transfer.restoreBackup(backup, password, transfer.previewBackup(backup, password), replaceExisting = true)
            assertNull(PersonalOverview(target, clock).open().direction)
            assertNull(target.record(first.id))
        }
        password.fill('\u0000'); context.deleteDatabase(otherName)
    }

    @Test fun `bounded overview shows total active actions and review dates without declaring execution`() {
        overview.save(direction.copy(reviewOn = today), creationId = "direction")
        val actions = ActionExperiments(repository, clock)
        repeat(5) { index -> actions.create(ActionDraft("合成行动 $index", "有半小时时", "做一个例子", "能运行并说明", today.plusDays(1), "Asia/Shanghai"), "action-$index") }
        val value = overview.open(limit = 2)
        assertEquals(5, value.actions.total); assertEquals(2, value.actions.items.size)
        assertTrue(value.direction!!.reviewDue)
        assertEquals(0, value.profiles.total); assertEquals(0, value.pendingKnowledge)
        assertThrows(IllegalArgumentException::class.java) { overview.save(direction.copy(statedOn = today.plusDays(1))) }
    }

    private fun export(options: ReadingExportOptions = ReadingExportOptions()): Map<String, String> {
        val attachments = temporary.newFolder()
        val snapshot = repository.captureSnapshot(attachments)
        val zip = File(temporary.root, "${UUID.randomUUID()}.zip")
        KnowledgeArchive.write(snapshot, attachments, zip, options)
        return ZipFile(zip).use { archive -> archive.entries().asSequence().filter { !it.isDirectory }
            .associate { it.name to archive.getInputStream(it).bufferedReader().readText() } }
    }
    private class MutableClock : Clock() {
        var now: Instant = Instant.parse("2026-10-20T04:00:00Z")
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
}
