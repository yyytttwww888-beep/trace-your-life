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
}
