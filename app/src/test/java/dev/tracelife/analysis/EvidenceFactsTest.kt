package dev.tracelife.analysis

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EvidenceFactsTest {
    private val id = "a".repeat(64)
    private fun source(text: String, evidenceId: String = id, recordId: String = "synthetic-record", start: Int = 0,
        total: Int = start + text.codePointCount(0, text.length), from: String = "2026-10-18") = mapOf(
        "id" to evidenceId, "recordId" to recordId, "revision" to 1, "field" to "body", "start" to start,
        "end" to start + text.codePointCount(0, text.length), "totalCodePoints" to total,
        "sha256" to AnalysisRunCodec.sha256(text), "text" to text, "startDate" to from, "endDate" to from)
    private fun build(text: String): EvidenceFactPack = requireNotNull(EvidenceFacts.build(mapOf("evidence" to listOf(source(text)))))
    private fun check(source: String, target: String) = EvidenceFacts.check(target, build(source))

    @Test fun `duration becoming delay is blocked for arbitrary amounts but future experiments stay open`() {
        listOf("五" to "5", "十二" to "12", "27" to "27").forEach { (original, normalized) ->
            val pack = build("合成资料：打算练习${original}分钟，还没有尝试。")
            assertTrue(pack.facts.any { it.kind == EvidenceFactKind.DURATION && it.value == (normalized.toInt() * 60).toString() })
            assertTrue(EvidenceFacts.check("记录中的${original}分钟后练习尚未执行。", pack).findings.any { it.rule == EvidenceFactRule.TIME_ROLE_CHANGED })
            assertFalse(EvidenceFacts.check("打算练习${original}分钟，尚未执行。", pack).blocked)
            assertFalse(EvidenceFacts.check("下次可以等${original}分钟后再开始，这是待选实验。", pack).blocked)
        }
        assertFalse(check("合成资料：等八分钟后开始朗读。", "等待八分钟再开始朗读。").blocked)
    }

    @Test fun `explicit order uses the actual action endpoints and distinguishes new proposals`() {
        val source = "合成资料：我先检查接口，再编写清单，最后完成任务。"
        assertTrue(check(source, "记录显示先编写清单，再检查接口。").findings.any { it.rule == EvidenceFactRule.STEP_ORDER_CHANGED })
        assertFalse(check(source, "我先检查接口，再编写清单。").blocked)
        assertFalse(check(source, "下次可以先编写清单，再检查接口，观察是否更合适。").blocked)
        assertTrue(check(source, "记录中先编写清单。").findings.any { it.rule == EvidenceFactRule.STEP_ORDER_CHANGED })
    }

    @Test fun `an exact repeated source clause binds an omitted action without treating a new proposal as history`() {
        val source = "合成资料：我想改成下周十九分钟，到今天还没试这个安排。"
        assertTrue(check(source, "我想改成下周十九分钟，到今天还没试这个安排；所以十九分钟后朗读只是设想，尚未实施。")
            .findings.any { it.rule == EvidenceFactRule.TIME_ROLE_CHANGED })
        assertFalse(check(source, "我想改成下周十九分钟，到今天还没试这个安排；下次可先等待十九分钟再开始朗读，这是新的待选建议。").blocked)
        assertFalse(check(source, "另一个安排是十九分钟后开始朗读。").blocked)
    }

    @Test fun `different activities and multiple events cannot borrow a numerical role or reversed sequence`() {
        assertFalse(check("合成资料：练习五分钟。", "煮饭先等待五分钟再开始。").blocked)
        assertFalse(check("合成资料：计划揉面十一分钟。", "原文计划揉面十一分钟，下次可先等待十一分钟再开始揉面。").blocked)
        assertFalse(build("合成资料：练习五六分钟。").facts.any { it.kind == EvidenceFactKind.DURATION })
        val pack = requireNotNull(EvidenceFacts.build(mapOf("evidence" to listOf(
            source("合成资料：先检查接口，再编写清单。", from = "2026-10-15"),
            source("合成资料：先编写清单，再检查接口。", "b".repeat(64), "another-record", from = "2026-10-18")))))
        assertFalse(EvidenceFacts.check("10月18日先编写清单，再检查接口。", pack).blocked)
        assertFalse(EvidenceFacts.check("10月15日先检查接口，再编写清单；10月18日先编写清单，再检查接口。", pack).blocked)
        assertTrue(EvidenceFacts.check("10月15日先编写清单，再检查接口。", pack).blocked)
    }

    @Test fun `explicit nonexecution remains known and only matches the same behavior`() {
        val source = "合成资料：尚未完成整理资料。"
        assertTrue(check(source, "已经完成整理资料。").findings.any { it.rule == EvidenceFactRule.EXECUTION_CHANGED })
        assertFalse(check(source, "尚未完成整理资料。").blocked)
        assertFalse(check(source, "已经完成提交申请。").blocked)
        assertFalse(check(source, "下次可以完成整理资料。").blocked)
        val action = mapOf("id" to id, "actionId" to "synthetic-action", "revision" to 1, "sha256" to "b".repeat(64),
            "reviewedPlan" to mapOf("behavior" to "整理资料"),
            "review" to mapOf("execution" to "NOT_EXECUTED", "observedText" to "尚未开始", "reviewedOn" to "2026-10-18"))
        val pack = requireNotNull(EvidenceFacts.build(mapOf("actionEvidence" to listOf(action))))
        assertTrue(EvidenceFacts.check("已经完成整理资料。", pack).findings.any { it.rule == EvidenceFactRule.EXECUTION_CHANGED })
        assertFalse(EvidenceFacts.check("已经完成提交申请。", pack).blocked)
        assertEquals(pack, EvidenceFacts.parsePack(EvidenceFacts.packMap(pack)))
    }

    @Test fun `available time does not certify adequacy and a local adjective does not certify comparison`() {
        val source = "合成资料：完成一次熟悉的练习，有40分钟空闲，不能确认启动方式导致完成。"
        assertTrue(check(source, "这次时间充足，因此其他情境是否适用仍未知。").findings.any { it.rule == EvidenceFactRule.UNGROUNDED_ADEQUACY })
        assertTrue(check(source, "这次任务更熟悉，是否稳定仍未知。").findings.any { it.rule == EvidenceFactRule.UNGROUNDED_COMPARISON })
        assertFalse(check(source, "这次有40分钟空闲，完成熟悉的练习，原因仍未知。").blocked)
        assertFalse(check(source, "下次可以选择时间充足的场合，尝试不同方法。").blocked)
        assertFalse(check("合成资料：这次时间充足，任务更熟悉。", "这次时间充足，任务更熟悉。").blocked)
    }

    @Test fun `a prospective colon label preserves the actual captured suggestion without licensing past claims`() {
        val original = "合成验收资料，完全虚构，不是真实个人经历：今天先跑一个小模块，再根据报错补接口说明，完成一次熟悉的练习。有40分钟空闲，不能确认是启动方式导致完成。"
        val suggestion = "可以尝试：在任务较熟悉、有约40分钟空闲时，先跑一个小模块，再按报错补接口说明；观察是否顺利推进和完成，之后回看这次做法是否适合自己，目前尚未采纳。"
        assertFalse(check(original, suggestion).blocked)
        assertTrue(check(original, "这次记录只有一次经历，且任务更熟悉、时间充足，因此不足以说明存在稳定做法。").blocked)

        val other = "合成资料：完成一次熟悉的烘焙，有23分钟空闲。先称量原料，再调节温度。"
        listOf("可以尝试：", "不妨尝试:", "建议：").forEach { label ->
            assertFalse(check(other, label + "在配方更熟悉、时间充足时，先调节温度，再称量原料，记录结果。").blocked)
        }
        assertTrue(check(other, "记录者建议：这次配方更熟悉、时间充足。").blocked)
        assertTrue(check(other, "原文说可以尝试：这次配方更熟悉、时间充足。").blocked)
        assertTrue(check(other, "这次配方更熟悉，建议：下次记录开始时间。").blocked)
        assertTrue(check(other, "可以尝试：先预热烤箱；这次时间充足。").blocked)
    }

    @Test fun `later advice within one clause cannot excuse an earlier factual assertion`() {
        val original = "合成资料：完成一次熟悉的练习，有37分钟空闲。"
        assertTrue(check(original, "这次时间充足所以建议下次记录开始时间。")
            .findings.any { it.rule == EvidenceFactRule.UNGROUNDED_ADEQUACY })
        assertTrue(check(original, "这次任务更熟悉所以建议下次对照难度。")
            .findings.any { it.rule == EvidenceFactRule.UNGROUNDED_COMPARISON })
        assertFalse(check(original, "建议下次选择时间充足的场合再尝试。").blocked)
        assertFalse(check(original, "可以尝试：在时间充足、任务更熟悉时进行练习，记录完成情况。").blocked)
    }

    @Test fun `two different dates in a sentence cannot silently select its last date as the event`() {
        val pack = requireNotNull(EvidenceFacts.build(mapOf("evidence" to listOf(
            source("合成资料：揉面十七分钟。", from = "2026-10-07"),
            source("合成资料：先等待十七分钟再开始揉面。", "b".repeat(64), "other-event", from = "2026-10-09")))))
        assertFalse(EvidenceFacts.check("10月9日在10月7日那次之后先等待十七分钟再开始揉面。", pack).blocked)
        assertFalse(EvidenceFacts.check("10月9日先等待十七分钟再开始揉面。", pack).blocked)
        assertTrue(EvidenceFacts.check("10月7日先等待十七分钟再开始揉面。", pack)
            .findings.any { it.rule == EvidenceFactRule.TIME_ROLE_CHANGED })
        assertTrue(EvidenceFacts.check("10月7日（仍是10月7日）先等待十七分钟再开始揉面。", pack)
            .findings.any { it.rule == EvidenceFactRule.TIME_ROLE_CHANGED })
    }

    @Test fun `conditions differing cannot be rewritten as the authors causal attribution`() {
        val source = "合成资料：完成练习，时间和难度与上次不同，原因没有确定。"
        assertTrue(check(source, "记录者把差异归因于时间和难度，但方法是否有效未知。").findings.any { it.rule == EvidenceFactRule.ATTRIBUTION_CHANGED })
        assertFalse(check(source, "时间和难度不同，可能与结果有关，仍需验证。").blocked)
        assertFalse(check("合成资料：我把差异归因于时间和难度，但尚未验证。", "记录者把差异归因于时间和难度，尚未验证。").blocked)
    }

    @Test fun `source building ignores candidate history and never treats unsupported language as false`() {
        val envelope = mapOf("evidence" to listOf(source("合成资料：练习八分钟。")),
            "target" to mapOf("text" to "等待八分钟。"), "historicalFeedback" to mapOf("text" to "时间充足，任务更熟悉。"))
        assertEquals(EvidenceFacts.build(envelope - "target" - "historicalFeedback"), EvidenceFacts.build(envelope))
        assertNull(EvidenceFacts.build(mapOf("derivedInputs" to listOf(mapOf("text" to "练习八分钟。")))))
        assertFalse(check("合成资料：风景让我感到平静。", "我可能更适合户外学习，仍待观察。").blocked)
        assertFalse(build("合成资料：风景让我感到平静。").complete)
    }

    @Test fun `unicode spans and persisted hashes retain exact source positions and reject tampering`() {
        val text = "😀合成资料：我先检查接口，再编写清单。练习十二分钟。"
        val pack = build(text)
        pack.facts.forEach { fact ->
            assertEquals(fact.quote, text.substring(text.offsetByCodePoints(0, fact.start), text.offsetByCodePoints(0, fact.end)))
        }
        assertEquals(pack, EvidenceFacts.parsePack(EvidenceFacts.packMap(pack)))
        val review = EvidenceFacts.check("记录显示先编写清单，再检查接口。", pack)
        assertEquals(review, EvidenceFacts.parseReview(EvidenceFacts.reviewMap(review)))
        assertThrows(IllegalArgumentException::class.java) { EvidenceFacts.validatePack(pack.copy(sha256 = "0".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { EvidenceFacts.validateReview(review.copy(findings = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { EvidenceFacts.validateReview(review.copy(targetText = "其他陈述。")) }
    }

    @Test fun `answer field accepts the existing 200 character question id boundary`() {
        val maximum = source("合成资料：练习十二分钟。") + ("field" to "answer:" + "q".repeat(200))
        val pack = requireNotNull(EvidenceFacts.build(mapOf("evidence" to listOf(maximum))))
        assertEquals(207, pack.sources.single().field.length)
        assertEquals(pack, EvidenceFacts.parsePack(EvidenceFacts.packMap(pack)))
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceFacts.build(mapOf("evidence" to listOf(maximum + ("field" to "answer:" + "q".repeat(201)))))
        }
    }

    @Test fun `same quantity in unrelated dated records is not a license to mix events`() {
        val pack = requireNotNull(EvidenceFacts.build(mapOf("evidence" to listOf(
            source("合成资料：练习八分钟。", from = "2026-10-15"),
            source("合成资料：等八分钟后开始。", "b".repeat(64), "another-record", from = "2026-10-18")))))
        assertFalse(EvidenceFacts.check("10月18日等八分钟后开始。", pack).blocked)
        assertFalse(EvidenceFacts.check("练习八分钟后，感受仍待观察。", pack).blocked)
    }

    @Test fun `program blocking only lowers support and retains the original model judgment`() {
        val check = check("合成资料：练习九分钟。", "九分钟后练习只是计划。")
        val supported = ClaimReview(ReviewVerdict.SUPPORTED, listOf(id))
        val final = EvidenceFacts.apply(supported, check)
        assertEquals(ReviewVerdict.UNSUPPORTED, final.verdict)
        assertEquals(ReviewVerdict.SUPPORTED, final.factCheck?.modelVerdict)
        assertTrue(final.unknowns.any { "时间" in it || "时长" in it })
        val rejected = ClaimReview(ReviewVerdict.INSUFFICIENT_CONTEXT, unknowns = listOf("原有缺口"))
        assertEquals(ReviewVerdict.INSUFFICIENT_CONTEXT, EvidenceFacts.apply(rejected, check).verdict)
        assertEquals(ReviewVerdict.INSUFFICIENT_CONTEXT, EvidenceFacts.apply(rejected, check("练习九分钟。", "练习九分钟。")).verdict)
    }
}
