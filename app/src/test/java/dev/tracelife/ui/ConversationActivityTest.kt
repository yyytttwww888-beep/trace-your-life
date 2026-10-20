package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import dev.tracelife.TraceLifeApplication
import dev.tracelife.analysis.AnalysisProvider
import dev.tracelife.analysis.BatchProposal
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ModelRequest
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.conversation.ConversationTurn
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlertDialog
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Synthetic local journeys. The online provider is never invoked by these UI tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationActivityTest {
    private lateinit var app: TraceLifeApplication
    private var controller: ActivityController<ConversationActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val provider = SyntheticProvider()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        app.io.submit {}.get(20, TimeUnit.SECONDS)
    }

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `free question and example preview remain local and survive recreation`() {
        open()
        click("conversationExample:0")
        val question = view<EditText>("conversationQuestion").text.toString()
        assertTrue(question.contains("三个月"))
        requireNotNull(controller).recreate()
        drain()
        assertEquals(question, view<EditText>("conversationQuestion").text.toString())
        click("previewConversation")
        assertTrue(texts().contains(question))
        assertEquals(LocalDate.now().toString(), view<EditText>("conversationThrough").text.toString())
        assertNotNull(LocalDate.parse(view<EditText>("conversationFrom").text.toString()))
        assertNotNull(activity.window.decorView.findViewWithTag<View>("sendConversation"))
        assertTrue(app.conversations.history().isEmpty())
        assertEquals(0, provider.calls)
    }

    @Test fun `edited absolute dates survive preview recreation and send does not bypass configuration`() {
        open()
        view<EditText>("conversationQuestion").setText("这周我的学习方法有哪些变化？")
        click("previewConversation")
        view<EditText>("conversationFrom").setText("2026-07-01")
        view<EditText>("conversationThrough").setText("2026-09-30")
        requireNotNull(controller).recreate()
        drain()
        assertEquals("2026-07-01", view<EditText>("conversationFrom").text.toString())
        assertEquals("2026-09-30", view<EditText>("conversationThrough").text.toString())
        click("sendConversation")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.findViewById<TextView>(android.R.id.message).text.contains("密钥"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        drain()
        assertTrue(app.conversations.history().isEmpty())
        assertEquals(0, provider.calls)
    }

    @Test fun `invalid dates keep the question visible and never create a task`() {
        open()
        view<EditText>("conversationQuestion").setText("这周有什么值得保留的学习做法？")
        click("previewConversation")
        view<EditText>("conversationFrom").setText("2026-10-22")
        view<EditText>("conversationThrough").setText("2026-10-01")
        click("sendConversation")
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().findViewById<TextView>(android.R.id.message).text.contains("结束日期"))
        assertTrue(texts().contains("这周有什么值得保留的学习做法？"))
        assertTrue(app.conversations.history().isEmpty())
    }

    @Test fun `rotation cannot accept the default range of an ambiguous question on the users behalf`() {
        open()
        view<EditText>("conversationQuestion").setText("最近学习方法有哪些变化？")
        click("previewConversation")
        assertNull(activity.window.decorView.findViewWithTag<View>("sendConversation"))
        requireNotNull(controller).recreate()
        drain()
        assertNull(activity.window.decorView.findViewWithTag<View>("sendConversation"))
        assertTrue(texts().any { it.contains("时间还不明确") })
        click("conversationUpdateRange")
        assertNotNull(activity.window.decorView.findViewWithTag<View>("sendConversation"))
        assertTrue(app.conversations.history().isEmpty())
    }

    @Test fun `persisted answer opens existing evidence flow and selecting a finding binds a local followup`() {
        val turn = seedTurn()
        val calls = provider.calls
        open()
        click("conversationThread:${turn.threadId}")
        assertTrue(texts().any { it.contains("先做练习") })
        click("conversationOpenAnswer:${turn.id}")
        val destination = shadowOf(activity).nextStartedActivity
        assertEquals(ReflectionActivity::class.java.name, destination.component!!.className)
        assertEquals(turn.runId, destination.getStringExtra(ReflectionActivity.EXTRA_RUN_ID))
        click("conversationFinding:${turn.id}:1")
        val selected = Robolectric.buildActivity(ReflectionActivity::class.java, shadowOf(activity).nextStartedActivity).setup()
        drain()
        assertNotNull(selected.get().window.decorView.findViewWithTag<View>("reflectionEvidence"))
        val selectedText = descendants(selected.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(selectedText.any { it.contains("仍有未解决的题目") })
        assertFalse(selectedText.any { it.contains("先做练习再查资料的做法") })
        selected.pause().stop().destroy()
        click("conversationFollowup:${turn.id}:0")
        assertTrue(texts().any { it.contains("第 1 条发现") })
        assertTrue(texts().any { it.contains("具体出现在哪几次记录") })
        requireNotNull(controller).recreate()
        drain()
        assertTrue(texts().any { it.contains("第 1 条发现") })
        assertEquals(1, app.conversations.turns(turn.threadId).size)
        assertEquals(calls, provider.calls)
        assertEquals(calls, app.analysis.task(turn.runId).state.calls.size)
    }

    @Test fun `opening and recreating an existing thread preserves followup draft without new requests`() {
        val turn = seedTurn()
        val calls = provider.calls
        open(Intent(app, ConversationActivity::class.java).putExtra(ConversationActivity.EXTRA_THREAD_ID, turn.threadId))
        view<EditText>("conversationQuestion").setText("第二条发现后来有没有改善？")
        requireNotNull(controller).recreate()
        drain()
        assertEquals("第二条发现后来有没有改善？", view<EditText>("conversationQuestion").text.toString())
        assertTrue(texts().contains(turn.question))
        assertEquals(1, app.conversations.turns(turn.threadId).size)
        assertEquals(calls, app.analysis.task(turn.runId).state.calls.size)
    }

    @Test fun `opening a long thread positions the latest question inside the viewport`() {
        val (first, second) = seedLongThread()
        val calls = provider.calls
        open(Intent(app, ConversationActivity::class.java).putExtra(ConversationActivity.EXTRA_THREAD_ID, first.threadId))
        val scroll = layoutConversation()
        val newest = view<View>("conversationTurn:${second.id}")
        assertTrue("The preceding answer must extend beyond the viewport", newest.top > scroll.height)
        assertTrue("The latest answer must not remain buried below the first", scroll.scrollY > 0)
        assertTurnAtTop(second.id, scroll)
        assertEquals(calls, provider.calls)
    }

    @Test fun `rotation and returning from evidence preserve the earlier question being read`() {
        val (first, second) = seedLongThread()
        open(Intent(app, ConversationActivity::class.java).putExtra(ConversationActivity.EXTRA_THREAD_ID, first.threadId))
        val initial = layoutConversation()
        initial.scrollTo(0, view<View>("conversationTurn:${first.id}").top - Ui.dp(activity, 8))
        requireNotNull(controller).recreate()
        drain()
        assertTurnAtTop(first.id, layoutConversation())
        assertTrue(view<View>("conversationTurn:${second.id}").top > layoutConversation().scrollY + layoutConversation().height)
        click("conversationFinding:${first.id}:0")
        assertEquals(ReflectionActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        requireNotNull(controller).pause().stop().start().resume().visible()
        drain()
        assertTurnAtTop(first.id, layoutConversation())
    }

    @Test fun `source edits mark the old answer and do not offer a stale finding as a followup target`() {
        val turn = seedTurn()
        app.io.submit {
            val original = app.repository.listRecords().items.single()
            app.repository.updateRecord(original.id, original.revision, original.draft.copy(body = "合成纠正：这次先查了资料，尚未完成练习。"))
        }.get(20, TimeUnit.SECONDS)
        open(Intent(app, ConversationActivity::class.java).putExtra(ConversationActivity.EXTRA_THREAD_ID, turn.threadId))
        assertTrue(texts().any { it.contains("旧回答需要更新") })
        assertTrue(texts().any { it.contains("这是历史回答") })
        assertFalse(texts().any { it.contains("部分记录中的发现") })
        assertNull(activity.window.decorView.findViewWithTag<View>("conversationFollowup:${turn.id}:0"))
        assertNotNull(activity.window.decorView.findViewWithTag<View>("conversationOpenAnswer:${turn.id}"))
    }

    @Test fun `refreshing a stale followup returns to its complete original question and chosen scope`() {
        val first = seedTurn()
        val followup = app.io.submit<ConversationTurn> {
            val next = app.conversations.submit(app.conversations.prepare("后来有没有改善？", first.threadId))
            app.reflectionWorkflow.process(next.runId, provider)
            val record = app.repository.listRecords().items.single()
            app.repository.updateRecord(record.id, record.revision, record.draft.copy(body = "合成补充：练习结果还有其他因素。"))
            next
        }.get(20, TimeUnit.SECONDS)
        val calls = provider.calls
        open(Intent(app, ConversationActivity::class.java).putExtra(ConversationActivity.EXTRA_THREAD_ID, first.threadId))
        click("conversationReask:${followup.id}")
        assertTrue(texts().contains(first.question))
        assertFalse(texts().contains(followup.question))
        assertEquals(first.scope.from.toString(), view<EditText>("conversationFrom").text.toString())
        assertEquals(first.scope.through.toString(), view<EditText>("conversationThrough").text.toString())
        assertNull(activity.window.decorView.findViewWithTag<View>("conversationReference"))
        assertNotNull(activity.window.decorView.findViewWithTag<View>("sendConversation"))
        assertEquals(1, app.conversations.history().size)
        assertEquals(2, app.conversations.turns(first.threadId).size)
        assertEquals(calls, provider.calls)
    }

    @Test fun `switching topic explicitly clears a selected finding while retaining the rewritten question`() {
        val turn = seedTurn()
        open(Intent(app, ConversationActivity::class.java).putExtra(ConversationActivity.EXTRA_THREAD_ID, turn.threadId))
        click("conversationFollowup:${turn.id}:0")
        click("conversationEditQuestion")
        view<EditText>("conversationQuestion").setText("这周运动有哪些值得关注的变化？")
        click("conversationClearReference")
        assertEquals("这周运动有哪些值得关注的变化？", view<EditText>("conversationQuestion").text.toString())
        click("previewConversation")
        assertTrue(texts().contains("关注：运动"))
        assertNull(activity.window.decorView.findViewWithTag<View>("conversationReference"))
        assertEquals(1, app.conversations.turns(turn.threadId).size)
    }

    @Test fun `reflection entry exposes questions without starting an analysis`() {
        val reflection = Robolectric.buildActivity(ReflectionActivity::class.java).setup()
        drain()
        reflection.get().window.decorView.findViewWithTag<View>("openConversation").performClick()
        drain()
        assertEquals(ConversationActivity::class.java.name, shadowOf(reflection.get()).nextStartedActivity.component!!.className)
        assertTrue(app.conversations.history().isEmpty())
        reflection.pause().stop().destroy()
    }

    @Test fun `long conversation opens its latest page and earlier questions remain reachable after recreation`() {
        val turns = app.io.submit<List<ConversationTurn>> {
            var threadId: String? = null
            List(21) { index ->
                app.conversations.submit(app.conversations.prepare("这周学习方法有哪些变化？合成问题 $index", threadId)).also {
                    threadId = it.threadId
                }
            }
        }.get(20, TimeUnit.SECONDS)
        open()
        click("conversationThread:${turns.first().threadId}")
        assertTrue(texts().contains(turns.last().question))
        assertFalse(texts().contains(turns.first().question))
        click("conversationTurnsPrevious")
        assertTrue(texts().contains(turns.first().question))
        assertFalse(texts().contains(turns.last().question))
        requireNotNull(controller).recreate()
        drain()
        assertTrue(texts().contains(turns.first().question))
        click("conversationTurnsNext")
        assertTrue(texts().contains(turns.last().question))
        assertEquals(0, provider.calls)
    }

    @Test fun `delete states its effects and preserves the thread until explicit confirmation`() {
        val turn = seedTurn()
        open(Intent(app, ConversationActivity::class.java).putExtra(ConversationActivity.EXTRA_THREAD_ID, turn.threadId))
        click("conversationManagement")
        click("conversationDelete")
        val cancelled = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(cancelled.findViewById<TextView>(android.R.id.message).text.contains("机器结果和知识"))
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        drain()
        assertEquals(1, app.conversations.history().size)
        click("conversationDelete")
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain()
        assertTrue(app.conversations.history().isEmpty())
        assertEquals(1, app.repository.listRecords().items.size)
        assertNotNull(activity.window.decorView.findViewWithTag<View>("conversationQuestion"))
    }

    private fun seedTurn(): ConversationTurn = app.io.submit<ConversationTurn> {
        app.repository.createRecord(RecordDraft("合成资料：先做练习，再查资料；一次练习中还没有解决的题目需要回看。", Occurrence(LocalDate.now()), topics = setOf("学习")))
        val turn = app.conversations.submit(app.conversations.prepare("这周我的学习方法有什么值得保留的做法和问题？"))
        app.reflectionWorkflow.process(turn.runId, provider)
        turn
    }.get(20, TimeUnit.SECONDS)

    private fun seedLongThread(): Pair<ConversationTurn, ConversationTurn> {
        provider.longFindings = true
        val first = seedTurn()
        provider.longFindings = false
        val second = app.io.submit<ConversationTurn> {
            val turn = app.conversations.submit(app.conversations.prepare("这周学习有哪些反复出现的问题？", first.threadId))
            app.reflectionWorkflow.process(turn.runId, provider)
            turn
        }.get(20, TimeUnit.SECONDS)
        return first to second
    }

    private fun layoutConversation(): ScrollView {
        val decor = activity.window.decorView
        repeat(2) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 480, 800)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return descendants(decor).filterIsInstance<ScrollView>().first()
    }

    private fun assertTurnAtTop(id: String, scroll: ScrollView) {
        val topInViewport = view<View>("conversationTurn:$id").top - scroll.scrollY
        assertTrue("Question should be visible at the top, was $topInViewport px", topInViewport in 0..Ui.dp(activity, 12))
    }

    private fun open(intent: Intent? = null) {
        controller = if (intent == null) Robolectric.buildActivity(ConversationActivity::class.java).setup()
        else Robolectric.buildActivity(ConversationActivity::class.java, intent).setup()
        drain()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun texts() = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun <T : View> view(tag: String): T = requireNotNull(activity.window.decorView.findViewWithTag<T>(tag)) { "Missing $tag" }
    private fun click(tag: String) { assertTrue(view<View>(tag).performClick()); drain() }
    private fun drain() {
        repeat(4) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() }
    }

    private class SyntheticProvider : AnalysisProvider {
        override val id = "synthetic-conversation-ui"
        override val contextWindowTokens = 128_000
        var calls = 0
        var longFindings = false
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            calls++
            val sources = JSONObject(request.payloadJson).getJSONArray("evidence")
            val evidence = (0 until sources.length()).map { sources.getJSONObject(it).getString("id") }
            return BatchProposal(listOf(
                ProposedClaim("值得留下：合成记录中先做练习再查资料的做法值得继续回看。" +
                    if (longFindings) "\n合成长回答：保留当时的条件与实际结果，继续回看这次做法。".repeat(35) else "",
                    evidence, ClaimKind.POSSIBLE_INTERPRETATION),
                ProposedClaim("值得关注：合成记录中仍有未解决的题目。", evidence, ClaimKind.POSSIBLE_INTERPRETATION),
            ))
        }
    }
}
