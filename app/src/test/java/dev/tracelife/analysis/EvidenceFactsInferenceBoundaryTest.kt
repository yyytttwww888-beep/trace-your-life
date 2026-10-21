package dev.tracelife.analysis

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EvidenceFactsInferenceBoundaryTest {
    private fun source(text: String, id: String = "a".repeat(64), record: String = "synthetic-record",
        date: String = "2026-10-18") = mapOf(
        "id" to id, "recordId" to record, "revision" to 1, "field" to "body", "start" to 0,
        "end" to text.codePointCount(0, text.length), "totalCodePoints" to text.codePointCount(0, text.length),
        "sha256" to AnalysisRunCodec.sha256(text), "text" to text, "startDate" to date, "endDate" to date)

    private fun pack(vararg sources: Map<String, Any>) =
        requireNotNull(EvidenceFacts.build(mapOf("evidence" to sources.toList())))

    @Test fun `captured reported plan duration cannot become a delay behind a conservative conclusion`() {
        // Exact synthetic sources and generated claim from live2 plan-execution #4.
        val facts = pack(
            source("合成验收资料，完全虚构，不是真实个人经历：计划这周每天练习二十分钟，这是打算，还没有执行。",
                record = "plan-intention", date = "2026-10-12"),
            source("合成验收资料，完全虚构，不是真实个人经历：回看：二十分钟计划没有开始。记得打开过练习页面，不能确定是否完成过练习。想改成下周五分钟，到今天还没试这个新安排。",
                id = "b".repeat(64), record = "plan-review"))
        val target = "就这两条记录看，二十分钟的每日练习没有开始，五分钟后的小安排也还没试，因此目前没有可判断“哪种方法有用”的实际练习结果；这只能说明本段记录内的情况，不能推断记录之外是否练习过。"
        val proof = EvidenceFacts.check(target, facts)
        assertEquals(listOf(EvidenceFactRule.TIME_ROLE_CHANGED), proof.findings.map { it.rule })
        assertEquals("五分钟后的小安排也还没试", proof.findings.single().targetQuote)
        assertEquals(ReviewVerdict.UNSUPPORTED,
            EvidenceFacts.apply(ClaimReview(ReviewVerdict.SUPPORTED, facts.sources.map { it.evidenceId }), proof).verdict)
    }

    @Test fun `reported scope binding generalizes across amounts topics and record wording`() {
        listOf("十三" to "拉伸", "29" to "揉面", "七" to "朗读").forEach { (amount, activity) ->
            val facts = pack(source("合成资料：计划${activity}${amount}分钟，尚未尝试。"))
            listOf("就这些记录看", "从本周复盘来看", "根据目前这条记录", "依据已有记载", "记录显示").forEach { scope ->
                val target = "$scope，${amount}分钟后的小安排仍未尝试。"
                assertTrue(target, EvidenceFacts.check(target, facts).findings.any { it.rule == EvidenceFactRule.TIME_ROLE_CHANGED })
            }
        }
    }

    @Test fun `reported scope cannot borrow a quantity shared by multiple owners`() {
        val facts = pack(source("合成资料：计划朗读十三分钟。"),
            source("合成资料：计划拉伸十三分钟。", "b".repeat(64), "other-record"))
        assertFalse(EvidenceFacts.check("就这两条记录看，十三分钟后的小安排还没试。", facts).blocked)
        val unbound = pack(source("合成资料：计划朗读十三分钟。"))
        assertFalse(EvidenceFacts.check("另一个安排是十三分钟后开始揉面。", unbound).blocked)
        assertFalse(EvidenceFacts.check("根据记录看到了变化。另一个安排是十三分钟后开始揉面。", unbound).blocked)
    }

    @Test fun `reported evidence context does not turn an explicit new experiment into a historical claim`() {
        val facts = pack(source("合成资料：计划朗读十三分钟，尚未尝试。"))
        listOf("就这些记录看，下次可以等十三分钟后开始朗读，这是新的待选实验。",
            "根据这条记录，建议：下一次先等待十三分钟再开始朗读，记录感受。").forEach {
            assertFalse(it, EvidenceFacts.check(it, facts).blocked)
        }
    }

    @Test fun `captured denial of a causal inference does not assert adequacy`() {
        // Exact synthetic source and generated claim from live2 historical-correction #2.
        val facts = pack(source("合成验收资料，完全虚构，不是真实个人经历：今天先跑一个小模块，再根据报错补接口说明，完成一次熟悉的练习。有40分钟空闲，不能确认是启动方式导致完成。",
            date = "2026-10-20"))
        val target = "这次有40分钟空闲，任务被描述为熟悉；这些是当时的情境条件，不能据此推出时间充足或任务简单是完成的原因。"
        val proof = EvidenceFacts.check(target, facts)
        assertFalse(proof.blocked)
        assertEquals(ReviewVerdict.SUPPORTED,
            EvidenceFacts.apply(ClaimReview(ReviewVerdict.SUPPORTED, facts.sources.map { it.evidenceId }), proof).verdict)
    }

    @Test fun `nonentailment verbs are unasserted in targets and cannot supply positive source support`() {
        listOf("不能据此推出", "无法由此推断", "尚不能认定", "还不能直接证明", "不足以说明", "无法确认", "不能断定").forEach { prefix ->
            val original = "合成资料：完成一次熟悉的烘焙，有23分钟空闲；${prefix}时间充足或配方更熟悉是完成的原因。"
            val facts = pack(source(original))
            assertEquals(prefix, EvidenceFactMode.HYPOTHETICAL,
                facts.facts.single { it.kind == EvidenceFactKind.TIME_ADEQUACY }.mode)
            assertFalse(prefix, EvidenceFacts.check("${prefix}时间充足或配方更熟悉是完成的原因。", facts).blocked)
            assertTrue(prefix, EvidenceFacts.check("这次时间充足。", facts).findings.any { it.rule == EvidenceFactRule.UNGROUNDED_ADEQUACY })
            assertTrue(prefix, EvidenceFacts.check("这次配方更熟悉。", facts).findings.any { it.rule == EvidenceFactRule.UNGROUNDED_COMPARISON })
            assertEquals(facts, EvidenceFacts.parsePack(EvidenceFacts.packMap(facts)))
        }
    }

    @Test fun `inability to exclude is uncertainty rather than evidence of negation or affirmation`() {
        listOf("不能排除", "无法排除", "尚不能排除").forEach { prefix ->
            val facts = pack(source("合成资料：有31分钟空闲；${prefix}时间充足是原因。"))
            assertEquals(EvidenceFactMode.HYPOTHETICAL,
                facts.facts.single { it.kind == EvidenceFactKind.TIME_ADEQUACY }.mode)
            assertFalse(EvidenceFacts.check("${prefix}时间充足是原因。", facts).blocked)
            assertTrue(EvidenceFacts.check("这次时间充足。", facts).blocked)
        }
    }

    @Test fun `later denial cannot erase an earlier assertion and contrast ends inference scope`() {
        val facts = pack(source("合成资料：完成一次熟悉的绘画练习，有37分钟空闲。"))
        listOf("这次时间充足，不能据此推出已经形成规律。",
            "这次时间充足但不能认定已经形成规律。",
            "这次任务更熟悉，因此无法证明方法稳定有效。",
            "不能据此认定方法有效但这次时间充足。",
            "无法据此证明方法有效，不过这次时间充足。").forEach {
            assertTrue(it, EvidenceFacts.check(it, facts).blocked)
        }
    }
}
