package dev.tracelife.providers

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.ReviewIssue
import dev.tracelife.analysis.ReviewVerdict
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fabricated provider output only. These tests check enforceable protocol rules, not model truth. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekReviewProtocolTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val text = "第一句事实。第二句限制。"

    @Test fun `program parts retain exact punctuation whitespace and Unicode with bounded complete coverage`() {
        val targets = listOf("  合成🌙：先核对；再记录。\n ", "持续12.5分钟。然后回看!", "甲。".repeat(50), "只有一个无标点段落😀")
        targets.forEach { target ->
            val parts = DeepSeekReviewProtocol.parts(target)
            assertEquals(target, parts.joinToString("") { it.getValue("text") })
            assertTrue(parts.size in 1..24)
            assertEquals(parts.indices.map { "p${it + 1}" }, parts.map { it.getValue("partId") })
            val value = parse(target, result(checks = parts.map { check(it.getValue("partId")) }))
            assertEquals(ReviewVerdict.SUPPORTED, value.verdict)
        }
        assertEquals("持续12.5分钟。", DeepSeekReviewProtocol.parts(targets[1]).first().getValue("text"))
        listOf(
            listOf(check("p1")),
            listOf(check("p2"), check("p1")),
            listOf(check("p1"), check("p3")),
            listOf(check("p1"), check("p1")),
            emptyList<Map<String, Any?>>(),
            listOf(check("p1"), check("p2") + ("text" to "偷偷改写原文")),
        ).forEach { assertInvalid(result(checks = it)) }
    }

    @Test fun `malformed Unicode target and response diagnostics fail closed`() {
        assertThrows(IllegalArgumentException::class.java) { DeepSeekReviewProtocol.parts("\uD83D") }
        assertThrows(IllegalArgumentException::class.java) { DeepSeekReviewProtocol.parts("\uDE00") }
        val brokenReason = """{"verdict":"SUPPORTED","checks":[{"partId":"p1","statementType":"REPORTED_FACT","status":"SUPPORTED","reason":"\ud83d","anchors":[{"evidenceId":"$a","field":"/text","quote":"合成原文与限定相符"}]}],"issues":[],"unknowns":[]}"""
        assertThrows(IllegalArgumentException::class.java) { DeepSeekReviewProtocol.parse(brokenReason, envelope("😀"), false) }
    }

    @Test fun `clause commas split facts from cautious tails while numeric separators and enumeration stay intact`() {
        val cases = linkedMapOf(
            "任务更熟悉、时间也充足，所以这个做法是否适用，目前没有依据。" to listOf(
                "任务更熟悉、时间也充足，", "所以这个做法是否适用，", "目前没有依据。"),
            "合成支出1,000.25元，耗时12.5分钟, then reflect." to listOf(
                "合成支出1,000.25元，", "耗时12.5分钟,", " then reflect."),
            "累计-1,234,567.89，下一项1,000,再核对。" to listOf("累计-1,234,567.89，", "下一项1,000,", "再核对。"),
            "１２,３４５.６单位，甲、乙、丙。" to listOf("１２,３４５.６单位，", "甲、乙、丙。"),
            "编号1,2分别核对。" to listOf("编号1,", "2分别核对。"),
        )
        cases.forEach { (target, expected) ->
            val parts = DeepSeekReviewProtocol.parts(target)
            assertEquals(expected, parts.map { it.getValue("text") })
            assertEquals(target, parts.joinToString("") { it.getValue("text") })
        }
    }

    @Test fun `comma partitions retain conditional context and never truncate the twenty fourth part`() {
        val target = "  如果任务陌生，先核对接口，再试小例子，仍需回看😀。  "
        val original = envelope(target) + ("feedback" to mapOf("decision" to "CORRECT", "userText" to "合成历史条件不是本期事实"))
        val wire = DeepSeekReviewProtocol.wireEnvelope(original)
        assertEquals(original - "target", wire - "target")
        assertEquals(mapOf("parts" to listOf(
            mapOf("partId" to "p1", "text" to "  如果任务陌生，"),
            mapOf("partId" to "p2", "text" to "先核对接口，"),
            mapOf("partId" to "p3", "text" to "再试小例子，"),
            mapOf("partId" to "p4", "text" to "仍需回看😀。  "),
        )), wire["target"])
        val bounded = List(31) { "条件$it，" }.joinToString("") + "尾部反例不能丢。"
        val parts = DeepSeekReviewProtocol.parts(bounded)
        assertEquals(24, parts.size)
        assertEquals(List(23) { "条件$it，" }, parts.take(23).map { it.getValue("text") })
        assertEquals((23..30).joinToString("") { "条件$it，" } + "尾部反例不能丢。", parts.last().getValue("text"))
        assertEquals(bounded, parts.joinToString("") { it.getValue("text") })
    }

    @Test fun `unsupported premise plus supported cautious ending cannot be published as supported`() {
        val value = parse(text, result(checks = listOf(
            check("p1", "UNSUPPORTED", emptyList(), "数值不支持时间紧张这一前提"),
            check("p2"))))
        assertEquals(ReviewVerdict.UNSUPPORTED, value.verdict)
        assertTrue(ReviewIssue.OTHER in value.issues)
        assertTrue(value.unknowns.any { it.contains("第一句事实") && it.contains("时间紧张") })
    }

    @Test fun `blocking unknown and unresolved issue each prevent overall support`() {
        val blocked = parse(text, result(checks = listOf(check("p1", "UNSUPPORTED", emptyList()), check("p2")),
            unknowns = listOf(unknown("必要比较基线没有提供"))))
        assertEquals(ReviewVerdict.UNSUPPORTED, blocked.verdict)
        assertTrue(ReviewIssue.INSUFFICIENT_CONTEXT in blocked.issues)
        assertTrue(blocked.unknowns.any { it.contains("阻断当前判断") })
        val issue = parse(text, result(issues = listOf("CAUSAL_OVERREACH")))
        assertEquals(ReviewVerdict.UNSUPPORTED, issue.verdict)
        assertEquals(listOf(ReviewIssue.CAUSAL_OVERREACH), issue.issues)
        assertInvalid(result(unknowns = listOf(unknown("更远时期适用性未测", false))))
    }

    @Test fun `never upgrades overall rejection or invents a direct counterexample`() {
        assertEquals(ReviewVerdict.UNSUPPORTED, parse(text, result(verdict = "UNSUPPORTED")).verdict)
        val context = parse(text, result(verdict = "INSUFFICIENT_CONTEXT"))
        assertEquals(ReviewVerdict.INSUFFICIENT_CONTEXT, context.verdict)
        assertTrue(ReviewIssue.INSUFFICIENT_CONTEXT in context.issues)
        val noCounterexample = parse(text, result(verdict = "CONTRADICTED"))
        assertEquals(ReviewVerdict.UNSUPPORTED, noCounterexample.verdict)
        assertTrue(noCounterexample.contradictingEvidenceIds.isEmpty())
        assertTrue(noCounterexample.unknowns.any { it.contains("没有提供直接反证") })
    }

    @Test fun `counterevidence dominates same receipt used to support another clause`() {
        val value = parse(text, result(checks = listOf(
            check("p1", ids = listOf(a, b)),
            check("p2", "CONTRADICTED", listOf(a), "同一原文明确否定第二句"))))
        assertEquals(ReviewVerdict.CONTRADICTED, value.verdict)
        assertEquals(listOf(a), value.contradictingEvidenceIds)
        assertEquals(listOf(b), value.supportingEvidenceIds)
    }

    @Test fun `all check citations must be actual inputs even if they will not be support receipts`() {
        listOf(
            check("p1", ids = listOf("c".repeat(64))),
            check("p1", "UNSUPPORTED", listOf("c".repeat(64))),
            check("p1", ids = listOf(a, a)),
            check("p1", ids = emptyList()),
            check("p1", "CONTRADICTED", emptyList()),
            check("p1", ids = List(33) { a }),
        ).forEach { assertInvalid(result(checks = listOf(it, check("p2")))) }
        val unknown = parse(text, result(checks = listOf(check("p1", "UNSUPPORTED", emptyList()), check("p2"))))
        assertEquals(ReviewVerdict.UNSUPPORTED, unknown.verdict)
    }

    @Test fun `future optional experiments can retain support without claiming observed effectiveness`() {
        val target = "可以在下一次练习前先写假设，再观察是否完成；这只是待选实验，效果未证明。"
        val value = parse(target, result(checks = listOf(
            check("p1", reason = "针对已有困难提出有限实验，没有声称已执行", statementType = "FUTURE_PROPOSAL"),
            check("p2", reason = "继续描述同一个待选实验的观察步骤", statementType = "FUTURE_PROPOSAL"),
            check("p3", statementType = "LIMITATION"), check("p4", statementType = "LIMITATION"))))
        assertEquals(ReviewVerdict.SUPPORTED, value.verdict)
        assertTrue(DeepSeekReviewProtocol.INSTRUCTIONS.contains("不要求已执行或已有效"))
    }

    @Test fun `interpretation category accepts domain terminology without relaxing anchors or verdicts`() {
        val supported = result(checks = listOf(check("p1", statementType = "POSSIBLE_INTERPRETATION"), check("p2")))
        assertEquals(ReviewVerdict.SUPPORTED, parse(text, supported).verdict)
        assertInvalid(result(checks = listOf(check("p1", statementType = "CERTAIN_PERSONALITY"), check("p2"))))
        listOf("POSSIBLE_INTERPRETATION", "FUTURE_PROPOSAL", "LIMITATION").forEach { type ->
            assertInvalid(result(checks = listOf(check("p1", ids = emptyList(), statementType = type), check("p2"))))
            val rejected = result(checks = listOf(check("p1", "UNSUPPORTED", emptyList(), statementType = type), check("p2")))
            assertEquals(ReviewVerdict.UNSUPPORTED, parse(text, rejected).verdict)
        }
    }

    @Test fun `metric and action source identities remain distinct and checked`() {
        val metric = "metric-v1-$a"
        val input = mapOf("target" to mapOf("text" to text), "metricEvidence" to listOf(mapOf("id" to metric, "values" to mapOf("totalRecords" to 3))))
        val body = AnalysisRunCodec.json(result(checks = listOf(check("p1", ids = listOf(metric), field = "/values/totalRecords", quote = "3"),
            check("p2", ids = listOf(metric), field = "/values/totalRecords", quote = "3"))))
        assertEquals(listOf(metric), DeepSeekReviewProtocol.parse(body, input, true).supportingEvidenceIds)
        assertThrows(IllegalArgumentException::class.java) { DeepSeekReviewProtocol.parse(body, input, false) }
        val action = mapOf("target" to mapOf("text" to text), "actionEvidence" to listOf(mapOf("id" to a,
            "plan" to mapOf("behavior" to "合成原文与限定相符"))))
        val actionBody = result(checks = listOf(check("p1", field = "/plan/behavior"), check("p2", field = "/plan/behavior")))
        assertEquals(listOf(a), DeepSeekReviewProtocol.parse(AnalysisRunCodec.json(actionBody), action, false).supportingEvidenceIds)
    }

    @Test fun `bounded diagnostics preserve every rejected clause and blocking explanation`() {
        val parts = List(24) { "片段$it：" + "合成".repeat(30) + "。" }
        val target = parts.joinToString("")
        val checks = parts.mapIndexed { index, _ -> check("p${index + 1}", "UNSUPPORTED", emptyList(), "理由$index：" + "限".repeat(140)) }
        val value = parse(target, result(checks = checks, unknowns = List(12) { unknown("缺口$it", partId = "p${it + 1}") }))
        assertEquals(ReviewVerdict.UNSUPPORTED, value.verdict)
        assertTrue(value.unknowns.size <= 20)
        assertTrue(value.unknowns.all { it.length <= 2_000 })
        val joined = value.unknowns.joinToString("")
        parts.indices.forEach { assertTrue(joined.contains("片段$it：")); assertTrue(joined.contains("理由$it：")) }
        assertThrows(IllegalArgumentException::class.java) {
            parse("甲。".repeat(25), result(checks = List(25) { check("p${it + 1}") }))
        }
        listOf(
            result(checks = listOf(check("p1", reason = "x".repeat(161)), check("p2"))),
            result(checks = listOf(check("p1", reason = " "), check("p2"))),
            result(unknowns = List(13) { unknown("合成缺口") }),
            result(unknowns = listOf(unknown("x".repeat(501)))),
        ).forEach(::assertInvalid)
    }

    @Test fun `wrong types missing fields extra fields duplicate keys and untyped unknowns fail closed`() {
        listOf(
            result() + ("extra" to true),
            result() - "checks",
            result() + ("unknowns" to listOf("旧字符串不可绕过阻断分类")),
            result(unknowns = listOf(mapOf("text" to "合成", "blocksConclusion" to "false"))),
            result(checks = listOf(check("p1") + ("status" to "INSUFFICIENT_CONTEXT"), check("p2"))),
            result(checks = listOf(check("p1") + ("evidenceIds" to listOf(7)), check("p2"))),
            result(checks = listOf(check("p1") + ("partId" to true), check("p2"))),
            result(checks = listOf(check("p1") + ("extra" to true), check("p2"))),
            result(issues = listOf("OTHER", "OTHER")),
        ).forEach { value -> assertThrows(Exception::class.java) { parse(text, value) } }
        val duplicate = AnalysisRunCodec.json(result()).replaceFirst("\"verdict\":", "\"verdict\":\"UNSUPPORTED\",\"verdict\":")
        assertThrows(Exception::class.java) { DeepSeekReviewProtocol.parse(duplicate, envelope(text), false) }
    }

    private fun check(partId: String, status: String = "SUPPORTED", ids: List<String> = listOf(a), reason: String = "合成原文与限定相符",
        statementType: String = "REPORTED_FACT", field: String = "/text", quote: String = "合成原文与限定相符") =
        mapOf("partId" to partId, "statementType" to statementType, "status" to status, "reason" to reason,
            "anchors" to ids.map { mapOf("evidenceId" to it, "field" to field, "quote" to quote) })
    private fun unknown(text: String, blocks: Boolean = true, partId: String = "p1") =
        mapOf("partId" to partId, "text" to text, "blocksConclusion" to blocks)
    private fun result(verdict: String = "SUPPORTED", checks: List<Map<String, Any?>> = listOf(check("p1"), check("p2")),
        issues: List<String> = emptyList(), unknowns: List<Map<String, Any?>> = emptyList()): Map<String, Any?> =
        mapOf("verdict" to verdict, "checks" to checks, "issues" to issues, "unknowns" to unknowns)
    private fun envelope(target: String) = mapOf("target" to mapOf("text" to target),
        "evidence" to listOf(mapOf("id" to a, "text" to "合成原文与限定相符"), mapOf("id" to b, "text" to "合成原文与限定相符")))
    private fun parse(target: String, value: Map<String, Any?>) = DeepSeekReviewProtocol.parse(AnalysisRunCodec.json(value), envelope(target), false)
    private fun assertInvalid(value: Map<String, Any?>) { assertThrows(IllegalArgumentException::class.java) { parse(text, value) } }
}
