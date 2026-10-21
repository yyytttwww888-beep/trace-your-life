package dev.tracelife.analysis

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Independent synthetic controls: a local guard must distinguish a contradiction from lack of proof.
 * No network, frozen quality fixtures, or model verdicts are changed by these tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EvidenceFactsAdversarialTest {
    @Test fun `same quantity in different activities is not a time role contradiction`() {
        val facts = pack(source("我揉面十一分钟。"))
        assertRule(facts, "原记录是先等待十一分钟再开始揉面。", EvidenceFactRule.TIME_ROLE_CHANGED)
        assertOpen(facts, "先等待十一分钟再开始烘焙。")
        assertOpen(facts, "揉面十一分钟。")
    }

    @Test fun `a negated duration cannot establish an affirmative duration conflict`() {
        val target = "原记录是先等待十一分钟再开始揉面。"
        assertRule(pack(source("我揉面十一分钟。")), target, EvidenceFactRule.TIME_ROLE_CHANGED)
        assertOpen(pack(source("我没有揉面十一分钟。")), target)
    }

    @Test fun `hypothetical order cannot establish the actual opposite order`() {
        val reversed = "先调节温度，再称量原料。"
        assertRule(pack(source("先称量原料，再调节温度。")), reversed, EvidenceFactRule.STEP_ORDER_CHANGED)
        assertOpen(pack(source("如果先称量原料，再调节温度，可能更稳妥。")), reversed)
        assertOpen(pack(source("并非先称量原料，再调节温度。")), reversed)
        assertOpen(pack(source("先称量原料，再调节温度。")), "下次可以先调节温度，再称量原料。")
    }

    @Test fun `same clause dates select the right event instead of borrowing another days role`() {
        val facts = pack(
            source("揉面十三分钟。", "duration", "2026-10-03"),
            source("先等待十三分钟再开始揉面。", "waiting", "2026-10-04"),
        )
        assertRule(facts, "10月3日先等待十三分钟再开始揉面。", EvidenceFactRule.TIME_ROLE_CHANGED)
        assertOpen(facts, "10月4日先等待十三分钟再开始揉面。")
        assertOpen(facts, "10月3日揉面十三分钟。")
    }

    @Test fun `a date range does not identify a single event on its first day`() {
        val target = "10月3日，先等待十七分钟再开始拉伸。"
        assertRule(pack(source("拉伸十七分钟。", from = "2026-10-03")), target, EvidenceFactRule.TIME_ROLE_CHANGED)
        assertOpen(pack(source("拉伸十七分钟。", from = "2026-10-03", through = "2026-10-07")), target)
        assertOpen(pack(source("拉伸十七分钟。", from = "2026-10-04")), target)
    }

    @Test fun `new advice remains new after a faithful source summary in the same sentence`() {
        val facts = pack(source("我计划揉面十一分钟。"))
        assertRule(facts, "原记录的计划是先等待十一分钟再开始揉面。", EvidenceFactRule.TIME_ROLE_CHANGED)
        assertOpen(facts, "原文计划揉面十一分钟，下次可先等待十一分钟再开始揉面。")
        assertOpen(facts, "如果下次先等待十一分钟再开始揉面，效果仍需记录。")
    }

    @Test fun `later advice does not erase an earlier unsupported adequacy statement`() {
        val facts = pack(source("本次有37分钟空闲。"))
        assertRule(facts, "这次时间充足，建议下次记录开始时间。", EvidenceFactRule.UNGROUNDED_ADEQUACY)
        assertOpen(facts, "下次可以选时间充足的场合，并记录开始时间。")
        assertOpen(facts, "这次有37分钟空闲，建议下次记录开始时间。")
    }

    @Test fun `negated and hypothetical comparisons cannot license an actual comparison`() {
        val target = "这次配方更简单。"
        assertRule(pack(source("这次配方简单。")), target, EvidenceFactRule.UNGROUNDED_COMPARISON)
        assertRule(pack(source("这次配方简单。并非更简单。")), target, EvidenceFactRule.UNGROUNDED_COMPARISON)
        assertRule(pack(source("这次配方简单。如果更简单，准备会更快。")), target, EvidenceFactRule.UNGROUNDED_COMPARISON)
        assertOpen(pack(source("这次配方更简单。")), target)
        assertOpen(pack(source("这次配方简单。")), "下次可以试更简单的配方，仍待验证。")
    }

    @Test fun `negative or hypothetical attribution must not be turned into the authors actual view`() {
        val target = "记录者把差异归因于温度和湿度，解释仍待验证。"
        val conditions = "温度和湿度与上次不同。"
        assertRule(pack(source(conditions)), target, EvidenceFactRule.ATTRIBUTION_CHANGED)
        assertRule(pack(source(conditions + "并非我把差异归因于温度和湿度。")), target, EvidenceFactRule.ATTRIBUTION_CHANGED)
        assertRule(pack(source(conditions + "如果我把差异归因于温度和湿度，会过早下结论。")), target, EvidenceFactRule.ATTRIBUTION_CHANGED)
        assertOpen(pack(source(conditions + "我把差异归因于温度和湿度，解释仍待验证。")), target)
        assertOpen(pack(source(conditions)), "温度和湿度可能与差异有关，解释仍待验证。")
    }

    private fun assertRule(pack: EvidenceFactPack, target: String, rule: EvidenceFactRule) {
        val result = EvidenceFacts.check(target, pack)
        assertTrue("Expected $rule for $target; findings=${result.findings}", result.findings.any { it.rule == rule })
    }

    private fun assertOpen(pack: EvidenceFactPack, target: String) {
        val result = EvidenceFacts.check(target, pack)
        assertFalse("A local source guard must abstain for $target; findings=${result.findings}", result.blocked)
    }

    private fun pack(vararg sources: Map<String, Any?>) = requireNotNull(EvidenceFacts.build(mapOf("evidence" to sources.toList())))

    private fun source(text: String, id: String = "synthetic-adversarial", from: String = "2026-10-03", through: String = from): Map<String, Any?> = mapOf(
        "id" to AnalysisRunCodec.sha256("source:$id:$text:$from:$through"), "recordId" to id, "revision" to 1,
        "field" to "body", "start" to 0, "end" to text.codePointCount(0, text.length),
        "totalCodePoints" to text.codePointCount(0, text.length), "sha256" to AnalysisRunCodec.sha256(text),
        "text" to text, "startDate" to from, "endDate" to through,
    )
}
