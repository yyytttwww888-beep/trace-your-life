package dev.tracelife.providers

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.ReviewVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic replay of a real failure mode: a cautious ending concealed an altered time role. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekReviewAnchorsTest {
    private val id = "a".repeat(64)

    @Test fun `legacy review that overlooked duration becoming delay cannot bypass grounded checks`() {
        val target = "回看里提到想改成下周五分钟，但到10月18日还没试过这个新安排；所以「五分钟后」只是打算，不是已经验证有效的方法。"
        val source = "合成验收资料：想改成下周五分钟，到今天还没试这个新安排。"
        val envelope = mapOf("target" to mapOf("text" to target), "evidence" to listOf(mapOf("id" to id, "text" to source)))
        val body = AnalysisRunCodec.json(mapOf("verdict" to "SUPPORTED", "checks" to listOf(
            mapOf("partId" to "p1", "status" to "SUPPORTED", "reason" to "想改成下周五分钟、还没试这个新安排。", "evidenceIds" to listOf(id)),
            mapOf("partId" to "p2", "status" to "SUPPORTED", "reason" to "只把五分钟写成想改的打算，不能算已验证有效的方法。", "evidenceIds" to listOf(id)),
        ), "issues" to emptyList<String>(), "unknowns" to emptyList<String>()))
        assertThrows(IllegalArgumentException::class.java) { DeepSeekReviewProtocol.parse(body, envelope, false) }
    }

    @Test fun `supported review cannot add an unanchored claim that an explicitly reported count is unknown`() {
        val envelope = mapOf("target" to mapOf("text" to "本周仅练习一次。"),
            "evidence" to listOf(mapOf("id" to id, "text" to "合成验收资料：本周只练习了一次。")))
        val body = AnalysisRunCodec.json(mapOf("verdict" to "SUPPORTED", "checks" to listOf(
            mapOf("partId" to "p1", "status" to "SUPPORTED", "reason" to "周总结明示只有一次。", "evidenceIds" to listOf(id)),
        ), "issues" to emptyList<String>(), "unknowns" to listOf(mapOf("text" to "其他几天是否练习未知", "blocksConclusion" to false))))
        assertThrows(IllegalArgumentException::class.java) { DeepSeekReviewProtocol.parse(body, envelope, false) }
    }

    @Test fun `lack of a baseline cannot become a direct contradiction using only a source identity`() {
        val envelope = mapOf("target" to mapOf("text" to "比上个月明显更有效。"),
            "evidence" to listOf(mapOf("id" to id, "text" to "合成验收资料：今天做完一次练习，没有记录上个月的情况。")))
        val body = AnalysisRunCodec.json(mapOf("verdict" to "UNSUPPORTED", "checks" to listOf(
            mapOf("partId" to "p1", "status" to "CONTRADICTED", "reason" to "没有上个月记录，因此比较结论被否定。", "evidenceIds" to listOf(id)),
        ), "issues" to emptyList<String>(), "unknowns" to emptyList<String>()))
        assertThrows(IllegalArgumentException::class.java) { DeepSeekReviewProtocol.parse(body, envelope, false) }
    }

    @Test fun `anchors must preserve verbatim content from the cited field in this request`() {
        val source = "合成资料：每次六分钟，还未开始执行。"
        val envelope = input("计划每次练习六分钟，尚未执行。", "evidence", mapOf("text" to source, "question" to "每次六分钟后开始"))
        val good = result(anchors = listOf(anchor(quote = "每次六分钟，还未开始执行。")), parts = 2)
        assertEquals(ReviewVerdict.SUPPORTED, parse(good, envelope).verdict)
        listOf(
            anchor(quote = "每次六分钟后开始"),
            anchor(quote = "每次六分钟，已经开始执行。"),
            anchor(quote = "每次六分钟……未执行"),
            anchor(field = "/question", quote = "每次六分钟后开始"),
            anchor(field = "/id", quote = id),
            anchor(quote = "计划每次练习六分钟，尚未执行。"), // Target is never a source.
            anchor(quote = "每次六分钟") + ("evidenceId" to "b".repeat(64)),
            anchor(quote = " "),
            anchor(quote = "\uD83D"),
        ).forEach { assertInvalid(result(anchors = listOf(it), parts = 2), envelope) }
    }

    @Test fun `action anchors bind separately to the reviewed plan next plan and actual execution report`() {
        val envelope = input("旧计划尚未执行，下一轮拟缩短练习。", "actionEvidence", mapOf(
            "reviewedPlan" to mapOf("behavior" to "练习十二分钟"),
            "nextPlan" to mapOf("behavior" to "练习六分钟"),
            "review" to mapOf("execution" to "NOT_EXECUTED", "observedText" to "合成回看：尚未开始练习"),
            "sourceNature" to "USER_AUTHORED",
        ))
        val anchors = listOf(anchor("/reviewedPlan/behavior", "练习十二分钟"),
            anchor("/nextPlan/behavior", "练习六分钟"), anchor("/review/execution", "NOT_EXECUTED"))
        assertEquals(listOf(id), parse(result(anchors = anchors, parts = 2), envelope).supportingEvidenceIds)
        listOf(
            anchor("/nextPlan/behavior", "练习十二分钟"),
            anchor("/reviewedPlan/behavior", "练习六分钟"),
            anchor("/plan/behavior", "练习六分钟"),
            anchor("/sourceNature", "USER_AUTHORED"),
            anchor("/review", "NOT_EXECUTED"),
            anchor("/review/execution", "EXECUTED"),
        ).forEach { assertInvalid(result(anchors = listOf(it), parts = 2), envelope) }
    }

    @Test fun `metric anchors validate exact scalar values and canonical bounded array paths`() {
        val metricId = "metric-v1-$id"
        val envelope = input("合成指标记录十二条。", "metricEvidence", mapOf(
            "values" to mapOf("totalRecords" to 12, "daily" to listOf(mapOf("date" to "2026-10-20", "records" to 12)), "missingExactIds" to listOf("missing-id")),
            "scope" to mapOf("includeArchived" to false), "source" to mapOf("generation" to 12),
        ), metricId)
        val anchors = listOf(anchor("/values/totalRecords", "12", metricId),
            anchor("/values/daily/0/records", "12", metricId), anchor("/scope/includeArchived", "false", metricId))
        assertEquals(listOf(metricId), parse(result(anchors = anchors), envelope, true).supportingEvidenceIds)
        listOf(
            anchor("/values/totalRecords", "2", metricId),
            anchor("/values/totalRecords", "12.0", metricId),
            anchor("/values/daily/00/records", "12", metricId),
            anchor("/values/daily/1/records", "12", metricId),
            anchor("/values/daily/0/date", "2026", metricId),
            anchor("/values/daily/9999999999999999999999/records", "12", metricId),
            anchor("/values/missingExactIds/0", "missing-id", metricId),
            anchor("/source/generation", "12", metricId),
            anchor("/scope/includeArchived", "alse", metricId),
        ).forEach { assertInvalid(result(anchors = listOf(it)), envelope, true) }
    }

    @Test fun `future proposal can use its historical motivation without pretending its new steps already happened`() {
        val source = "合成资料：接口未知，先写清单后返工两次，未完成。"
        val envelope = input("下次遇到接口未知，可先验证接口再列清单，结束后记录返工次数；这是待选实验。", "evidence", mapOf("text" to source))
        val checks = (1..3).map { check("p$it", "FUTURE_PROPOSAL", "SUPPORTED", listOf(anchor(quote = source)),
            "原文是接口未知与返工情境，验证顺序及回看为未来建议，不声称已经试过或有效。") } +
            check("p4", "LIMITATION", "SUPPORTED", listOf(anchor(quote = source)), "将建议明确保留为待选实验。")
        val body = mapOf("verdict" to "SUPPORTED", "checks" to checks, "issues" to emptyList<String>(), "unknowns" to emptyList<String>())
        assertEquals(ReviewVerdict.SUPPORTED, parse(body, envelope).verdict)
    }

    @Test fun `replayed mixed claim cannot use a supported cautious tail to hide unsupported historical conditions`() {
        // Exact synthetic claim that passed the earlier real model review; fixture inputs stay unchanged.
        val target = "这次任务更熟悉、时间也充足，所以这个做法是否适用于复杂或不熟悉的任务，目前没有依据。"
        val source = "今天先跑一个小模块，再根据报错补接口说明，完成一次熟悉的练习。有40分钟空闲，不能确认是启动方式导致完成。"
        val envelope = input(target, "evidence", mapOf("text" to source)) +
            ("feedback" to mapOf("userText" to "只有一次经历，当时任务更熟悉且时间充足。"))
        assertEquals(listOf("这次任务更熟悉、时间也充足，", "所以这个做法是否适用于复杂或不熟悉的任务，", "目前没有依据。"),
            DeepSeekReviewProtocol.parts(target).map { it.getValue("text") })
        val body = mapOf("verdict" to "SUPPORTED", "checks" to listOf(
            check("p1", "REPORTED_FACT", "UNSUPPORTED", listOf(anchor(quote = "有40分钟空闲")),
                "原文只说熟悉且有40分钟，未给比较基线或充足判断，历史纠正不是本次事实。"),
            check("p2", "LIMITATION", "SUPPORTED", listOf(anchor(quote = source)), "结合后句，对复杂或不熟悉任务的适用性仍未确定。"),
            check("p3", "LIMITATION", "SUPPORTED", listOf(anchor(quote = source)), "本次经历不证明其他情境适用。"),
        ), "issues" to emptyList<String>(), "unknowns" to emptyList<String>())
        val parsed = parse(body, envelope)
        assertEquals(ReviewVerdict.UNSUPPORTED, parsed.verdict)
        assertTrue(parsed.unknowns.any { "这次任务更熟悉、时间也充足" in it && "未给比较基线" in it })
        assertTrue(parsed.contradictingEvidenceIds.isEmpty())
    }

    @Test fun `unknowns may only explain one unsupported premise and never add a free life claim`() {
        val envelope = input("比上个月更有效。", "evidence", mapOf("text" to "合成资料：本次完成练习，未记录上个月情况。"))
        val unsupported = result(status = "UNSUPPORTED", anchors = emptyList(), unknowns = listOf(unknown("p1", "缺少目标比较所需的上月结果")))
        val parsed = parse(unsupported, envelope)
        assertEquals(ReviewVerdict.UNSUPPORTED, parsed.verdict)
        assertTrue(parsed.contradictingEvidenceIds.isEmpty())
        assertTrue(parsed.unknowns.any { "缺少目标比较所需的上月结果" in it })
        listOf(
            result(unknowns = listOf(unknown("p1", "其他日期是否练习未知"))),
            unsupported + ("unknowns" to listOf(unknown("p2", "不存在的目标"))),
            unsupported + ("unknowns" to listOf(unknown("p1", "未来效果未知") + ("blocksConclusion" to false))),
            unsupported + ("unknowns" to listOf(unknown("p1", "缺基线"), unknown("p1", "重复缺基线"))),
            result(status = "CONTRADICTED", unknowns = listOf(unknown("p1", "反证不能用缺据填充"))),
        ).forEach { assertInvalid(it, envelope) }
    }

    @Test fun `a direct counterexample needs a real content anchor and leaves no invented unknown`() {
        val envelope = input("当天完成了接口测试。", "evidence", mapOf("text" to "合成资料：当天没有做接口测试。"))
        val value = parse(result(status = "CONTRADICTED", anchors = listOf(anchor(quote = "当天没有做接口测试。"))), envelope)
        assertEquals(ReviewVerdict.CONTRADICTED, value.verdict)
        assertEquals(listOf(id), value.contradictingEvidenceIds)
        assertTrue(value.supportingEvidenceIds.isEmpty())
    }

    @Test fun `explicit occurrence dates and zone anchor complete supplied values only`() {
        val envelope = input("这条记录发生于十月十八日。", "evidence", mapOf("text" to "合成记录：完成练习。",
            "startDate" to "2026-10-18", "endDate" to "2026-10-18", "zoneId" to "Asia/Shanghai"))
        listOf("/startDate" to "2026-10-18", "/endDate" to "2026-10-18", "/zoneId" to "Asia/Shanghai").forEach { (field, quote) ->
            assertEquals(listOf(id), parse(result(anchors = listOf(anchor(field, quote))), envelope).supportingEvidenceIds)
            assertInvalid(result(anchors = listOf(anchor(field, quote.take(4)))), envelope)
        }
        assertInvalid(result(anchors = listOf(anchor("/date", "2026-10-18"))), envelope)
        assertInvalid(result(anchors = listOf(anchor("/startDate", "2026-10-17"))), envelope)
        val unknownDate = input("发生日期未知。", "evidence", mapOf("text" to "合成记录", "startDate" to null))
        assertInvalid(result(anchors = listOf(anchor("/startDate", "2026-10-18"))), unknownDate)
    }

    @Test fun `continuous provided fragments resolve a boundary quote to every covered receipt`() {
        val first = fragment(id, "合成😀下午遇到未知接", 0, 23)
        val secondId = "b".repeat(64)
        val second = fragment(secondId, "口时返工两次未完成。", first.getValue("end") as Int, 23)
        val envelope = mapOf("target" to mapOf("text" to "下午遇到未知接口。"), "evidence" to listOf(second, first))
        val body = result(anchors = listOf(anchor(quote = "下午遇到未知接口时", evidenceId = secondId)))
        assertEquals(listOf(id, secondId), parse(body, envelope).supportingEvidenceIds)
        assertEquals(listOf(id, secondId), parse(body + ("verdict" to "CONTRADICTED") +
            ("checks" to listOf(check("p1", "REPORTED_FACT", "CONTRADICTED",
                listOf(anchor(quote = "下午遇到未知接口时", evidenceId = secondId)), "合成反证跨越连续片段。"))), envelope).contradictingEvidenceIds)
        assertInvalid(result(anchors = listOf(anchor(quote = "合成😀", evidenceId = secondId))), envelope)
        assertInvalid(result(anchors = listOf(anchor(quote = "下午遇到……接口时", evidenceId = secondId))), envelope)
        assertInvalid(result(anchors = List(2) { anchor(quote = "下午遇到未知接口时", evidenceId = secondId) }), envelope)
    }

    @Test fun `boundary quotes never bridge missing overlapping mismatched or malformed fragments`() {
        val first = fragment(id, "下午遇到未知接", 0, 17)
        val secondId = "b".repeat(64)
        val second = fragment(secondId, "口时返工两次未完成。", 7, 17)
        val body = result(anchors = listOf(anchor(quote = "下午遇到未知接口时", evidenceId = secondId)))
        val variants = listOf(
            first + ("recordId" to "other-record"), first + ("revision" to 2), first + ("field" to "other-field"),
            first + ("start" to 1) + ("end" to 8), first + ("end" to 6), first + ("totalCodePoints" to 18),
        )
        variants.forEach { candidate ->
            assertInvalid(body, mapOf("target" to mapOf("text" to "合成判断。"), "evidence" to listOf(candidate, second)))
        }
        assertInvalid(body, mapOf("target" to mapOf("text" to "合成判断。"), "evidence" to listOf(second)))
        assertInvalid(body, mapOf("target" to mapOf("text" to "合成判断。"), "evidence" to listOf(first, second,
            first + ("id" to "c".repeat(64)))))
        val gap = fragment(id, "下午遇到未知接", 0, 18)
        val afterGap = fragment(secondId, "口时返工两次未完成。", 8, 18)
        assertInvalid(body, mapOf("target" to mapOf("text" to "合成判断。"), "evidence" to listOf(gap, afterGap)))
    }

    private fun fragment(evidenceId: String, text: String, start: Int, total: Int): Map<String, Any?> = mapOf(
        "id" to evidenceId, "recordId" to "synthetic-record", "revision" to 1, "field" to "note",
        "start" to start, "end" to start + text.codePointCount(0, text.length), "totalCodePoints" to total, "text" to text)

    private fun input(target: String, kind: String, content: Map<String, Any?>, evidenceId: String = id) =
        mapOf("target" to mapOf("text" to target), kind to listOf(content + ("id" to evidenceId)))
    private fun anchor(field: String = "/text", quote: String = "合成资料", evidenceId: String = id) =
        mapOf("evidenceId" to evidenceId, "field" to field, "quote" to quote)
    private fun check(partId: String, statementType: String, status: String, anchors: List<Map<String, Any?>>, reason: String) =
        mapOf("partId" to partId, "statementType" to statementType, "status" to status, "reason" to reason, "anchors" to anchors)
    private fun result(status: String = "SUPPORTED", anchors: List<Map<String, Any?>> = listOf(anchor()), unknowns: List<Map<String, Any?>> = emptyList(), parts: Int = 1) =
        mapOf("verdict" to status, "checks" to (1..parts).map { check("p$it", "REPORTED_FACT", status, anchors, "合成目标逐项对照本次原文。") },
            "issues" to emptyList<String>(), "unknowns" to unknowns)
    private fun unknown(partId: String, text: String) = mapOf("partId" to partId, "text" to text, "blocksConclusion" to true)
    private fun parse(body: Map<String, Any?>, envelope: Map<String, Any?>, metric: Boolean = false) =
        DeepSeekReviewProtocol.parse(AnalysisRunCodec.json(body), envelope, metric)
    private fun assertInvalid(body: Map<String, Any?>, envelope: Map<String, Any?>, metric: Boolean = false) {
        assertThrows(IllegalArgumentException::class.java) { parse(body, envelope, metric) }
    }
}
