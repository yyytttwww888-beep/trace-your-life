package dev.tracelife.ui

import dev.tracelife.analysis.ReflectionStatus
import org.junit.Assert.*
import org.junit.Test

class ConversationPresentationTest {
    @Test fun `preflight budget refusal explains why unchanged integration cannot continue`() {
        val notice = requireNotNull(ConversationPresentation.completionNotice(
            ReflectionStatus.BUDGET_EXHAUSTED, ReflectionStatus.INTEGRATING))
        assertTrue(notice.contains("超过本任务上限"))
        assertTrue(notice.contains("已保留部分发现"))
        assertTrue(notice.contains("缩小日期范围或拆成具体问题"))
        assertEquals(notice, ConversationPresentation.completionNotice(
            ReflectionStatus.BUDGET_EXHAUSTED, ReflectionStatus.BUDGET_EXHAUSTED))
    }

    @Test fun `unrecorded dependency refusal is surfaced while ordinary progress remains quiet`() {
        assertNotNull(ConversationPresentation.completionNotice(
            ReflectionStatus.DEPENDENCY_BLOCKED, ReflectionStatus.INTEGRATING))
        assertNotNull(ConversationPresentation.completionNotice(
            ReflectionStatus.STALE, ReflectionStatus.ANALYZING))
        assertNull(ConversationPresentation.completionNotice(
            ReflectionStatus.INTEGRATING, ReflectionStatus.INTEGRATING))
        assertNull(ConversationPresentation.completionNotice(
            ReflectionStatus.READY, ReflectionStatus.READY))
    }
}
