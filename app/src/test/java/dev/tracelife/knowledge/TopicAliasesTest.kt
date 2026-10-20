package dev.tracelife.knowledge

import android.app.Application
import android.content.Context
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.AnalysisStatus
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.background.BackgroundPolicy
import dev.tracelife.background.IncrementalOrganizer
import dev.tracelife.model.*
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TopicAliasesTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var aliases: TopicAliases
    private lateinit var files: File
    private val database = "synthetic-alias-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC)
    private val scope = AnalysisQuery(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), "Asia/Shanghai")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        files = File(context.cacheDir, "synthetic-alias-${UUID.randomUUID()}").apply { mkdirs() }
        repository = LifeRepository(context, clock, database)
        aliases = TopicAliases(repository)
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(database); files.deleteRecursively() }

    @Test fun `normalization expands confirmed groups without rewriting original topics`() {
        val source = repository.createRecord(RecordDraft("合成学习记录", Occurrence(scope.from), topics = setOf("ＫＯＴＬＩＮ")))
        val rule = aliases.save(" Kotlin ", setOf("ＫＯＴＬＩＮ", "编程练习", " kotlin学习 "))
        assertEquals("kotlin", rule.canonical)
        assertEquals(setOf("编程练习", "kotlin学习"), rule.aliases)
        assertEquals(setOf("ＫＯＴＬＩＮ", "kotlin", "编程练习", "kotlin学习"), aliases.expand(setOf("ＫＯＴＬＩＮ")))
        assertEquals(setOf("ＫＯＴＬＩＮ"), repository.record(source.id)!!.draft.topics)
        assertEquals(setOf("运动"), aliases.expand(setOf("运动")))
    }

    @Test fun `cross group conflicts fail atomically and stale edits cannot overwrite`() {
        val first = aliases.save("学习方法", setOf("学习策略"))
        val before = repository.listRecords().total
        assertThrows(IllegalArgumentException::class.java) { aliases.save("另一种主题", setOf(" 学习策略 ")) }
        assertEquals(before, repository.listRecords().total)
        val edited = aliases.save("学习方法", setOf("实践策略"), first.id, first.revision)
        assertEquals(first.revision + 1, edited.revision)
        assertThrows(RevisionConflictException::class.java) { aliases.save("错过的修改", setOf("旧词"), first.id, first.revision) }
        assertEquals(setOf("学习策略"), aliases.expand(setOf("学习策略")))
        assertEquals(setOf("学习方法", "实践策略"), aliases.expand(setOf("实践策略")))
        assertEquals(2, repository.history(first.id).size)
    }

    @Test fun `archive disables expansion and restoring rejects a newly conflicting group`() {
        val first = aliases.save("工作", setOf("编码"))
        val archived = aliases.archive(first.id, first.revision)
        assertEquals(setOf("编码"), aliases.expand(setOf("编码")))
        val second = aliases.save("编程", setOf("编码"))
        assertThrows(IllegalArgumentException::class.java) { aliases.archive(first.id, archived.revision, false) }
        aliases.archive(second.id, second.revision)
        val restored = aliases.archive(first.id, archived.revision, false)
        assertFalse(restored.archived)
        assertEquals(setOf("工作", "编码"), aliases.expand(setOf("编码")))
    }

    @Test fun `catalog pages include only exact versioned configuration and ordinary names do not spoof it`() {
        repeat(5) { aliases.save("主题$it", setOf("叫法$it")) }
        val fake = repository.saveTemplate(TopicAliasTemplate.draft.copy(questions = TopicAliasTemplate.draft.questions.map {
            it.copy(prompt = "这是普通生活问题")
        }))
        repository.createRecord(RecordDraft("合成普通记录", template = TemplateRef(fake.id, fake.version),
            answers = mapOf(TopicAliasTemplate.CANONICAL to "假规则", TopicAliasTemplate.ALIASES to "假别名")))
        val page = aliases.page(limit = 2)
        assertEquals(5, page.total); assertEquals(2, page.items.size); assertEquals(2, page.nextOffset)
        val next = aliases.page(requireNotNull(page.nextOffset), 2)
        assertEquals(2, next.items.size)
        assertTrue(page.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).isEmpty())
        assertEquals(setOf("假别名"), aliases.expand(setOf("假别名")))
    }

    @Test fun `editing a template head does not change already bound rule content`() {
        val first = aliases.save("学习", setOf("阅读实践"))
        val record = repository.record(first.id)!!
        val ref = record.draft.template!!
        val template = repository.template(ref.id, ref.version)!!
        repository.saveTemplate(template.draft.copy(questions = template.draft.questions.map { it.copy(hint = "合成自定义问题") }), ref.id, ref.version)
        assertEquals(setOf("学习", "阅读实践"), aliases.expand(setOf("阅读实践")))
        assertEquals(first, aliases.get(first.id))
    }

    @Test fun `life record filtering excludes configuration before pagination and unknown counts while preserving default access`() {
        repeat(5) { aliases.save("导航主题$it", setOf("导航叫法$it")) }
        val first = repository.createRecord(RecordDraft("合成明确日期生活资料", Occurrence(scope.from)), "life-a")
        val second = repository.createRecord(RecordDraft("合成日期未知生活资料"), "life-b")
        val fakeTemplate = repository.saveTemplate(TopicAliasTemplate.draft.copy(questions = TopicAliasTemplate.draft.questions.map {
            it.copy(prompt = "合成生活问题，不是导航配置")
        }))
        val fake = repository.createRecord(RecordDraft("合成同名模板生活资料", template = TemplateRef(fakeTemplate.id, fakeTemplate.version),
            answers = mapOf(TopicAliasTemplate.CANONICAL to "合成标签", TopicAliasTemplate.ALIASES to "合成叫法")), "life-c")

        assertEquals(8, repository.listRecords().total)
        assertEquals(7, repository.listRecords().unknownTimeCount)
        val pages = (0..2).map { offset -> repository.listRecords(RecordFilter(limit = 1, offset = offset, includeConfiguration = false)) }
        assertTrue(pages.all { it.total == 3 && it.unknownTimeCount == 2 && it.items.size == 1 })
        assertEquals(setOf(first.id, second.id, fake.id), pages.flatMap { it.items }.map { it.id }.toSet())
        assertEquals(1, pages.first().nextOffset)
        assertNull(pages.last().nextOffset)
        val dated = repository.listRecords(RecordFilter(includeUnknown = false, includeConfiguration = false))
        assertEquals(listOf(first.id), dated.items.map { it.id })
        assertEquals(2, dated.unknownTimeCount)
        val backup = repository.captureSnapshot(File(files, "filter-backup").apply { mkdirs() })
        assertEquals(8, backup.records.map { it.id }.distinct().size)
    }

    @Test fun `default evidence scope excludes configuration from counts fields and original reads`() {
        val rule = aliases.save("工作", setOf("项目"))
        val source = repository.analysisSourceStamp()
        val page = repository.analysisSourcePage(source, scope, 0, 25)
        assertEquals(0, page.total); assertEquals(0, page.unknown)
        assertThrows(IllegalArgumentException::class.java) { repository.analysisSourceFields(source, scope, rule.id) }
        val exact = scope.copy(recordIds = setOf(rule.id))
        val explicit = repository.analysisSourcePage(source, exact, 0, 25)
        assertEquals(rule.id, explicit.items.single().id)
        assertTrue(repository.analysisSourceFields(source, exact, rule.id).any { it.key == "answer:${TopicAliasTemplate.ALIASES}" })
        assertTrue(repository.record(rule.id)!!.draft.body.contains("不是生活事件"))
    }

    @Test fun `navigation edits do not invalidate life scope but explicit configuration tasks track revisions`() {
        repository.createRecord(RecordDraft("合成经历", Occurrence(scope.from)), "life")
        val analysis = ControlledAnalysis(repository, clock)
        val life = analysis.start(AnalysisRequest("合成范围", scope))
        val rule = aliases.save("工作", setOf("项目"))
        assertEquals(AnalysisStatus.READY, analysis.task(life.stored.id).status)
        val config = analysis.start(AnalysisRequest("查看导航配置", scope.copy(recordIds = setOf(rule.id))))
        aliases.save("工作", setOf("项目实践"), rule.id, rule.revision)
        assertEquals(AnalysisStatus.READY, analysis.task(life.stored.id).status)
        assertEquals(AnalysisStatus.STALE, analysis.task(config.stored.id).status)
    }

    @Test fun `turning a former life record into configuration still invalidates its earlier evidence`() {
        val original = repository.createRecord(RecordDraft("合成旧经历", Occurrence(scope.from)), "former-life")
        val analysis = ControlledAnalysis(repository, clock)
        val task = analysis.start(AnalysisRequest("合成范围", scope))
        val ref = TopicAliasTemplate.obtain(repository)
        repository.updateRecord(original.id, original.revision, RecordDraft("主题叫法配置", template = ref,
            answers = mapOf(TopicAliasTemplate.CANONICAL to "学习", TopicAliasTemplate.ALIASES to "阅读")))
        assertEquals(AnalysisStatus.STALE, analysis.task(task.stored.id).status)
        assertEquals(0, repository.analysisSourcePage(repository.analysisSourceStamp(), scope, 0, 25).total)
        repository.deleteRecord(original.id, 2)
        assertEquals(AnalysisStatus.STALE, analysis.task(task.stored.id).status)
    }

    @Test fun `pure configuration deletion preserves implicit life scopes and permanently removes explicit tasks`() {
        repository.createRecord(RecordDraft("合成经历", Occurrence(scope.from)), "life")
        val analysis = ControlledAnalysis(repository, clock)
        val life = analysis.start(AnalysisRequest("合成生活范围", scope), "life-task")
        val anotherDate = analysis.start(AnalysisRequest("合成其他日期范围", scope.copy(from = scope.from.minusYears(1),
            through = scope.through.minusYears(1))), "other-date-task")
        val rule = aliases.save("学习", setOf("阅读"))
        val exact = scope.copy(recordIds = setOf(rule.id))
        analysis.start(AnalysisRequest("合成尚未读取配置", exact), "unread-config-task")
        analysis.start(AnalysisRequest("合成已读取配置", exact), "read-config-task")
        analysis.runBatch("read-config-task", "read", listOf(dev.tracelife.analysis.EvidenceSelection(rule.id, "body")),
            object : dev.tracelife.analysis.AnalysisProvider {
                override val id = "synthetic-alias-delete"
                override val contextWindowTokens = 100_000
                override fun countInputTokens(request: dev.tracelife.analysis.ModelRequest) = 100
                override fun complete(request: dev.tracelife.analysis.ModelRequest) = dev.tracelife.analysis.BatchProposal(emptyList())
            })
        val backup = File(files, "before-config-delete").apply { mkdirs() }
        val snapshot = repository.captureSnapshot(backup)
        repository.deleteRecord(rule.id, rule.revision)
        assertEquals(AnalysisStatus.READY, analysis.task(life.stored.id).status)
        assertEquals(AnalysisStatus.READY, analysis.task(anotherDate.stored.id).status)
        assertNull(repository.analysisRun("unread-config-task")); assertNull(repository.analysisRun("read-config-task"))
        assertNull(repository.record(rule.id))
        val after = repository.captureSnapshot(File(files, "after-config-delete").apply { mkdirs() })
        assertTrue(after.deletedAnalysisRuns.map { it.id }.containsAll(listOf("unread-config-task", "read-config-task")))
        repository.restoreSnapshot(snapshot, backup, repository.currentGeneration(), replaceExisting = true)
        assertNull(repository.record(rule.id))
        assertNull(repository.analysisRun("unread-config-task")); assertNull(repository.analysisRun("read-config-task"))
        assertNotNull(repository.analysisRun(life.stored.id))
    }

    @Test fun `automatic record organizer never queues navigation configuration as a life event`() {
        IncrementalOrganizer(repository, ControlledAnalysis(repository, clock), File(files, "queue"), clock).use { organizer ->
            organizer.configure(BackgroundPolicy(enabled = true))
            aliases.save("学习", setOf("阅读"))
            organizer.synchronizeChanges()
            assertTrue(organizer.jobs().isEmpty())
            assertEquals(0, organizer.settings().reservedRequestsToday)
        }
    }

    @Test fun `full backup preserves rules and local deletion remains authoritative on restore`() {
        val original = aliases.save("学习", setOf("阅读"))
        val backup = File(files, "backup").apply { mkdirs() }
        val snapshot = repository.captureSnapshot(backup)
        val otherName = "synthetic-alias-restore-${UUID.randomUUID()}.db"
        LifeRepository(context, clock, otherName).use { other ->
            other.restoreSnapshot(snapshot, backup, other.currentGeneration(), replaceExisting = false)
            val restored = TopicAliases(other)
            assertEquals(setOf("学习", "阅读"), restored.expand(setOf("阅读")))
            val rule = requireNotNull(restored.get(original.id))
            other.deleteRecord(rule.id, rule.revision)
            other.restoreSnapshot(snapshot, backup, other.currentGeneration(), replaceExisting = true)
            assertTrue(restored.page().items.isEmpty())
            assertEquals(setOf("阅读"), restored.expand(setOf("阅读")))
        }
        context.deleteDatabase(otherName)
    }

    @Test fun `invalid empty oversized and control words are rejected without partial writes`() {
        val invalid = listOf("", " ", "a\nb", "x".repeat(81))
        invalid.forEach { value -> assertThrows(IllegalArgumentException::class.java) { aliases.save(value, setOf("其他词")) } }
        assertThrows(IllegalArgumentException::class.java) { aliases.save("学习", setOf("学习")) }
        assertThrows(IllegalArgumentException::class.java) { aliases.save("学习", (1..21).map { "词$it" }.toSet()) }
        assertTrue(aliases.page().items.isEmpty())
        assertTrue(repository.listRecords().items.isEmpty())
    }

    @Test fun `malformed configuration stays visible while valid navigation and ordinary question preparation continue`() {
        aliases.save("学习方法", setOf("学习策略"))
        val ref = TopicAliasTemplate.obtain(repository)
        val malformed = repository.createRecord(RecordDraft("合成误用配置模板的原文", template = ref), "broken-empty")
        val oversized = repository.createRecord(RecordDraft("合成长配置原文", template = ref, answers = mapOf(
            TopicAliasTemplate.CANONICAL to "长".repeat(81), TopicAliasTemplate.ALIASES to "别".repeat(2_049))), "broken-long")
        val page = aliases.page()
        assertEquals(3, page.total)
        assertEquals(1, page.items.size)
        assertEquals(setOf(malformed.id, oversized.id), page.problems.map { it.id }.toSet())
        assertEquals(setOf("学习方法", "学习策略"), aliases.expand(setOf("学习策略")))
        assertEquals(setOf("学习方法", "学习策略"), aliases.matching("学习策略"))
        assertTrue(KnowledgeDiscovery(repository, clock).search(DiscoveryQuery("学习策略")).items.isEmpty())
        assertTrue(dev.tracelife.analysis.KnowledgeContextCatalog(repository, clock).plan("学习策略", scope, emptySet()).entries.isEmpty())
        assertEquals(malformed, aliases.problemRecord(malformed.id, malformed.revision))
        assertEquals(oversized.draft, aliases.problemRecord(oversized.id, oversized.revision).draft)
    }

    @Test fun `repairing and archiving malformed configuration preserves original prose metadata and revisions`() {
        val ref = TopicAliasTemplate.obtain(repository)
        val original = repository.createRecord(RecordDraft("合成原始说明必须保留", Occurrence(scope.from),
            setOf("合成旧标签"), ref), "repairable")
        val repaired = aliases.save("学习方法", setOf("学习策略"), original.id, original.revision)
        assertEquals(2, repaired.revision)
        val current = repository.record(original.id)!!
        assertEquals(original.draft.body, current.draft.body)
        assertEquals(original.draft.occurrence, current.draft.occurrence)
        assertEquals(original.draft.topics, current.draft.topics)
        assertEquals(original.draft, repository.record(original.id, 1)!!.draft)
        assertTrue(aliases.page().problems.isEmpty())
        assertThrows(RevisionConflictException::class.java) { aliases.save("其他主题", setOf("其他别名"), original.id, 1) }
        val broken = repository.createRecord(RecordDraft("合成未完成配置", template = ref), "archive-broken")
        aliases.archiveProblem(broken.id, broken.revision)
        assertTrue(repository.record(broken.id)!!.archived)
        assertTrue(aliases.page().problems.isEmpty())
        assertEquals(broken.id, aliases.page(includeArchived = true).problems.single().id)
        assertEquals(broken.draft, repository.record(broken.id, 1)!!.draft)
    }

    @Test fun `concurrent repository instances cannot claim the same alias for different groups`() {
        val executor = Executors.newFixedThreadPool(2)
        val barrier = CountDownLatch(1)
        LifeRepository(context, clock, database).use { second ->
            try {
                val futures = listOf(repository, second).mapIndexed { index, source ->
                    executor.submit(Callable {
                        barrier.await(10, TimeUnit.SECONDS)
                        try { TopicAliases(source).save("主题$index", setOf("共同叫法")); true }
                        catch (_: IllegalArgumentException) { false }
                    })
                }
                barrier.countDown()
                assertEquals(1, futures.count { it.get(20, TimeUnit.SECONDS) })
                assertEquals(1, aliases.page().total)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `conflicting externally edited rules remain manageable but cannot silently route`() {
        val first = aliases.save("学习", setOf("同一个词"))
        val template = TopicAliasTemplate.obtain(repository)
        val other = repository.createRecord(RecordDraft("主题叫法配置", template = template,
            answers = mapOf(TopicAliasTemplate.CANONICAL to "工作", TopicAliasTemplate.ALIASES to "同一个词")))
        assertEquals(2, aliases.page().total)
        assertThrows(IllegalArgumentException::class.java) { aliases.expand(setOf("同一个词")) }
        aliases.archive(other.id, other.revision)
        assertEquals(setOf(first.canonical, "同一个词"), aliases.expand(setOf("同一个词")))
    }
}
