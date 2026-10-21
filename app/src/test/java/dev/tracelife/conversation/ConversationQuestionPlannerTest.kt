package dev.tracelife.conversation

import dev.tracelife.storage.AnalysisQuery
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ConversationQuestionPlannerTest {
    private val planner = ConversationQuestionPlanner()
    private val today = LocalDate.parse("2026-10-20")
    private fun plan(text: String, previous: AnalysisQuery? = null, override: AnalysisQuery? = null) =
        planner.prepare(text, today, "Asia/Shanghai", previous, override)

    @Test fun `continuing a life activity does not imply continuing a model answer`() {
        val previous = AnalysisQuery(today.minusMonths(3), today.minusWeeks(2), "Asia/Shanghai")
        listOf(
            "这周学习上有什么反复卡住的地方，有没有值得继续试的变化？",
            "今天继续练习了什么？", "这三个月我为什么没有继续读书？",
            "请帮我看看这周有什么值得继续练习", "能继续学习这个课程吗", "值得继续试的方法有哪些？",
        ).forEach { question ->
            assertFalse(question, plan(question).refersToPrevious)
            assertFalse(question, plan(question, previous).refersToPrevious)
        }
        listOf("继续", "请继续分析", "继续讲刚才那条", "那就继续", "请帮我继续分析", "继续一下", "继续吧", "那就请继续一下吧").forEach { question ->
            assertTrue(question, plan(question).refersToPrevious)
        }
    }

    @Test fun `polite continuation commands retain the exact previous three month scope`() {
        val previous = AnalysisQuery(today.minusMonths(3), today.minusWeeks(2), "Asia/Shanghai")
        continuationCommands.forEach { question ->
            val value = plan(question, previous)
            assertTrue(question, value.refersToPrevious)
            assertEquals(question, previous, value.scope)
            assertNull(question, value.clarification)
        }
    }

    @Test fun `three calendar months and mixed learning intent retain exact inclusive dates`() {
        val value = plan("这三个月，我在学习上的方法发生了哪些变化，哪些问题反复出现？")
        assertEquals(LocalDate.parse("2026-07-21"), value.scope.from)
        assertEquals(today, value.scope.through)
        assertEquals(setOf("学习"), value.focusTopics)
        assertEquals(ConversationIntent.CHANGES, value.intent)
        assertTrue(value.label.contains("反复"))
        assertNull(value.clarification)
    }

    @Test fun `calendar week leap month and explicit range do not use approximate thirty day months`() {
        assertEquals(AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai"), plan("上周工作有什么问题").scope)
        assertEquals(LocalDate.parse("2024-02-01"), planner.prepare("上个月学习变化", LocalDate.parse("2024-03-31"), "UTC").scope.from)
        assertEquals(LocalDate.parse("2024-02-29"), planner.prepare("上个月学习变化", LocalDate.parse("2024-03-31"), "UTC").scope.through)
        assertEquals(AnalysisQuery(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-09-30"), "Asia/Shanghai"),
            plan("2026年8月1日到2026年9月30日，哪些方法更合适").scope)
    }

    @Test fun `ambiguous unsupported invalid and conflicting dates require explicit range`() {
        listOf("最近学习怎么样", "九月有什么变化", "2026-02-30的记录", "上周和上个月有什么不同", "2026-10-20到2026-10-01").forEach {
            assertNotNull(it, plan(it).clarification)
        }
        val scope = AnalysisQuery(today.minusDays(90), today, "Asia/Shanghai")
        assertNull(plan("九月有什么变化", override = scope).clarification)
        assertEquals(scope, plan("九月有什么变化", override = scope).scope)
    }

    @Test fun `later comparison extends prior scope and future suggestions read only existing dates`() {
        val old = AnalysisQuery(today.minusDays(90), today.minusDays(20), "Asia/Shanghai")
        assertEquals(old.copy(through = today), plan("后来有没有改善", old).scope)
        assertEquals(old, plan("下周可以尝试什么", old).scope)
        assertEquals(ConversationIntent.NEXT_STEPS, plan("未来几个周，我需要关注什么").intent)
        assertEquals(today, plan("未来几个周，我需要关注什么").scope.through)
        assertNotNull(plan("明天睡眠质量怎么样").clarification)
        assertNotNull(plan("2026-11-01的记录").clarification)
    }

    @Test fun `ordinal question and deliberate selected answer preserve reference semantics`() {
        val value = plan("第二个问题具体在哪几次出现")
        assertEquals(2, value.ordinal)
        assertTrue(value.problemOrdinal)
        assertTrue(value.refersToPrevious)
        val scope = AnalysisQuery(today.minusDays(90), today, "Asia/Shanghai")
        assertEquals(scope, planner.prepare("详细说说", today, "Asia/Shanghai", scope, forcePrevious = true).scope)
        assertEquals(today.minusDays(29), plan("学习的方法有什么问题").scope.from)
        assertFalse(plan("为什么这周总睡不好？").refersToPrevious)
        assertFalse(plan("为什么运动后我更难入睡").refersToPrevious)
        assertTrue(plan("为什么会这样").refersToPrevious)
        assertEquals(LocalDate.parse("2026-10-05"), plan("上上周有什么变化").scope.from)
        assertEquals(today.minusDays(2), plan("前天学习了什么").scope.from)
        assertEquals(today.minusDays(1), plan("昨晚的睡眠").scope.from)
    }

    private val continuationCommands = listOf("能继续分析吗？", "麻烦继续解释一下", "可以继续说吗",
        "请问能不能继续分析", "继续详细解释一下", "请继续帮我分析")
}
