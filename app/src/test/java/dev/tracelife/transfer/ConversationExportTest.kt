package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.conversation.ConversationTurn
import dev.tracelife.storage.AnalysisQuery
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
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile

/** Synthetic questions and routing only; no personal material or paid calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationExportTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var name: String
    private lateinit var root: File
    private val now = Instant.parse("2026-10-20T04:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-20")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication(); name = "synthetic-question-export-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, name)
        root = File(context.cacheDir, "synthetic-question-export-${UUID.randomUUID()}").apply { mkdirs() }
        repository.createConversation("thread")
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name); root.deleteRecursively() }

    @Test fun `reading export routes paged user questions without including answer ledger`() {
        add("first", 1, AnalysisQuery(day.minusMonths(3), day, "Asia/Shanghai"), "learning")
        add("second", 2, AnalysisQuery(day.minusDays(7), day, "Asia/Shanghai"), "learning", "first")
        val files = export(ReadingExportOptions(pageSize = 1))
        assertTrue(files.containsKey("conversations/index.md"))
        val threadPages = files.filterKeys { it.matches(Regex("""conversations/threads/[^/]+/[^/]+\.md""")) }
        assertEquals(2, threadPages.size)
        assertTrue(threadPages.values.all { "本页 1 项" in it })
        assertEquals(setOf("1", "2"), threadPages.values.map {
            requireNotNull(Regex("""\[问题 ([12]) ·""").find(it)).groupValues[1]
        }.toSet())
        assertTrue(threadPages.values.any { "第 1/2 页" in it && "下一页" in it })
        assertTrue(threadPages.values.any { "第 2/2 页" in it && "上一页" in it })
        val questions = files.filterKeys { it.startsWith("conversations/questions/") }
        assertEquals(2, questions.size)
        assertTrue(questions.values.any { "合成问题 first" in it })
        assertTrue(questions.values.any { "前一问题" in it })
        assertFalse(files.values.any { "合成模型专用指引不可进入阅读问题" in it })
        val manifest = JSONObject(files.getValue("manifest.json"))
        assertEquals(2, manifest.getJSONObject("conversations").getInt("includedTurns"))
        assertFalse(manifest.getJSONObject("completeness").getBoolean("conversationAnswersIncluded"))
    }

    @Test fun `narrow date and topic export omits outside questions and never copies outside parent text`() {
        add("outside-parent", 1, AnalysisQuery(day.minusMonths(3), day, "Asia/Shanghai"), "learning")
        add("inside-child", 2, AnalysisQuery(day.minusDays(2), day, "Asia/Shanghai"), "learning", "outside-parent")
        add("wrong-topic", 3, AnalysisQuery(day.minusDays(1), day, "Asia/Shanghai"), "work")
        val files = export(ReadingExportOptions(scope = ExportScope(day.minusDays(7), day, setOf("learning"))))
        assertEquals(1, files.keys.count { it.startsWith("conversations/questions/") })
        assertTrue(files.values.any { "合成问题 inside-child" in it })
        assertFalse(files.values.any { "合成问题 outside-parent" in it || "合成问题 wrong-topic" in it })
        assertTrue(files.values.any { "范围外问题" in it })
        assertEquals(2, JSONObject(files.getValue("manifest.json")).getJSONObject("completeness").getInt("omittedConversationTurns"))
    }

    @Test fun `evidence packages omit conversational intentions entirely`() {
        add("first", 1, AnalysisQuery(day.minusMonths(3), day, "Asia/Shanghai"), "learning")
        val files = export(ReadingExportOptions(kind = ReadingPackageKind.EVIDENCE, question = "合成证据任务"))
        assertFalse(files.keys.any { it.startsWith("conversations/") || it.startsWith("data/conversations/") })
        assertFalse(files.values.any { "合成问题 first" in it })
        assertEquals(0, JSONObject(files.getValue("manifest.json")).getJSONObject("conversations").getInt("includedTurns"))
    }

    private fun add(id: String, position: Int, scope: AnalysisQuery, topic: String, parent: String? = null) {
        val question = "合成问题 $id：学习发生了什么变化？"
        val runId = "reflection-v1-conversation-$id"
        ControlledAnalysis(repository, clock).start(AnalysisRequest(question + "\n合成模型专用指引不可进入阅读问题", scope), runId)
        repository.appendConversationTurn(ConversationTurn(id, "thread", position, question, scope, setOf(topic), parent, null, runId, now))
    }
    private fun export(options: ReadingExportOptions): Map<String, String> {
        val attachments = File(root, UUID.randomUUID().toString()).apply { mkdir() }
        val snapshot = repository.captureSnapshot(attachments)
        val target = File(root, "${UUID.randomUUID()}.zip")
        KnowledgeArchive.write(snapshot, attachments, target, options)
        return ZipFile(target).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).bufferedReader().readText() } }
    }
}
