package dev.tracelife.providers

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.ReviewVerdict
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Minimized from the synthetic profile response on 2026-10-21, not a changed live fixture.
 * Its full dates used /text; its two process limitations had no anchors. These are separate faults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekReviewDateNormalizationTest {
    private val a = "754f0c45be1a6a5c5fd7793424ae7884db617d4ec62d022bc9723410bec25286"
    private val d = "458ca5a12752756f5154922eb0cd732f2f09d531159e8ce0a4d413e49b9bc41c"
    private val datePart = "待验证解释：八月至九月之间，"
    private val sourceA = "练习A：我先读教程，再把目标缩成一个小例子，最后做出了结果。当天练习简单，时间充足，不能归因于某一种方法。"
    private val sourceD = "练习D：仍先写小例子，但任务难度较高、时间很少，没能完成。小例子并不总有效。"
    private val dateAnchors get() = listOf(anchor(a, "2026-08-05"), anchor(d, "2026-09-20"))
    private val records get() = listOf(record(a, sourceA, "2026-08-05"), record(d, sourceD, "2026-09-20"))

    @Test fun `captured process limitations still reject empty anchors after date normalization`() {
        val target = datePart + "未处理的其他批次不算已比较，局部核对不等于全范围反例穷尽。"
        val response = result(listOf(
            check("p1", dateAnchors, "两条记录分别落在八月和九月，跨期比较有日期依据，且以待验证解释表述。"),
            check("p2", emptyList(), "未处理的其他批次不算已比较，是合理范围限制，无相反证据。", "LIMITATION"),
            check("p3", emptyList(), "局部核对不等于全范围反例穷尽，与覆盖信息一致，无相反证据。", "LIMITATION"),
        ))
        assertThrows(IllegalArgumentException::class.java) { parse(target, response) }
    }

    @Test fun `captured complete occurrence dates resolve to the same receipts as explicit date fields`() {
        val response = result(listOf(check("p1", dateAnchors)))
        val corrected = result(listOf(check("p1", listOf(anchor(a, "2026-08-05", "/startDate"),
            anchor(d, "2026-09-20", "/startDate")))))
        val actual = parse(datePart, response)
        assertEquals(ReviewVerdict.SUPPORTED, actual.verdict)
        assertEquals(listOf(a, d), actual.supportingEvidenceIds)
        assertEquals(parse(datePart, corrected), actual)
        assertEquals("The captured wire object stays unchanged", "/text", dateAnchors.first()["field"])
    }

    @Test fun `range end is resolved only from the exact provided end field`() {
        val input = listOf(record(a, "合成资料：这段时间整理了清单。", "2026-08-05", "2026-08-08"))
        val response = result(listOf(check("p1", listOf(anchor(a, "2026-08-08")))))
        assertEquals(listOf(a), parse("记录截止日期为八月八日。", response, input).supportingEvidenceIds)
        assertThrows(IllegalArgumentException::class.java) {
            parse("记录截止日期为八月八日。", result(listOf(check("p1", listOf(anchor(a, "2026-08-08", "/startDate"))))), input)
        }
    }

    @Test fun `other sources dates partial dates and invented dates never become anchors`() {
        listOf("2026-09-20", "2026-08-06", "2026-08", "08-05", "八月五日", "2026-08-05至2026-09-20").forEach { quote ->
            assertThrows("Must not borrow or infer $quote", IllegalArgumentException::class.java) {
                parse("这条记录的日期需要核对。", result(listOf(check("p1", listOf(anchor(a, quote))))))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("这条记录的日期需要核对。", result(listOf(check("p1", listOf(anchor("f".repeat(64), "2026-08-05"))))))
        }
    }

    @Test fun `unknown absent malformed and invalid dates do not acquire certainty`() {
        val base = record(a, "合成资料：具体日期没记。", "2026-08-05")
        listOf(base + mapOf("startDate" to null, "endDate" to null),
            base - "startDate" - "endDate",
            base + mapOf("startDate" to "2026-8-5", "endDate" to "2026-8-5"),
        ).forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                parse("记录发生在八月五日。", result(listOf(check("p1", listOf(anchor(a, "2026-08-05"))))), listOf(source))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("日期无效。", result(listOf(check("p1", listOf(anchor(a, "2026-02-30"))))),
                listOf(base + mapOf("startDate" to "2026-02-30", "endDate" to "2026-02-30")))
        }
    }

    @Test fun `actual text date retains text meaning even when occurrence date differs`() {
        val response = result(listOf(check("p1", listOf(anchor(a, "2026-08-06")))))
        val input = listOf(record(a, "合成资料：准备在2026-08-06回看。", "2026-08-05"))
        assertEquals(listOf(a), parse("计划在八月六日回看。", response, input).supportingEvidenceIds)
    }

    @Test fun `normalization cannot create duplicate anchors or upgrade a rejection`() {
        val duplicate = result(listOf(check("p1", listOf(anchor(a, "2026-08-05"), anchor(a, "2026-08-05", "/startDate")))))
        assertThrows(IllegalArgumentException::class.java) { parse("时间需要核对。", duplicate) }
        val rejected = result(listOf(check("p1", dateAnchors))) + ("verdict" to "UNSUPPORTED")
        assertEquals(ReviewVerdict.UNSUPPORTED, parse(datePart, rejected).verdict)
    }

    @Test fun `date normalization never applies to action text or empty support for life claims`() {
        val action = mapOf("target" to mapOf("text" to "回看发生在八月五日。"), "actionEvidence" to listOf(mapOf(
            "id" to a, "review" to mapOf("reviewedOn" to "2026-08-05", "observedText" to "合成回看。"))))
        assertThrows(IllegalArgumentException::class.java) {
            DeepSeekReviewProtocol.parse(AnalysisRunCodec.json(result(listOf(check("p1", listOf(anchor(a, "2026-08-05")))))), action, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("我已形成稳定习惯。", result(listOf(check("p1", emptyList(), type = "LIMITATION"))))
        }
    }

    private fun record(id: String, text: String, from: String, through: String = from): Map<String, Any?> = mapOf(
        "id" to id, "recordId" to "synthetic-${id.take(8)}", "revision" to 1, "field" to "body",
        "start" to 0, "end" to text.codePointCount(0, text.length), "totalCodePoints" to text.codePointCount(0, text.length),
        "sha256" to AnalysisRunCodec.sha256(text), "text" to text,
        "startDate" to from, "endDate" to through, "zoneId" to "Asia/Shanghai",
    )
    private fun anchor(id: String, quote: String, field: String = "/text") = mapOf("evidenceId" to id, "field" to field, "quote" to quote)
    private fun check(id: String, anchors: List<Map<String, String>>, reason: String = "完整日期与本次同一来源的发生日期一致。", type: String = "POSSIBLE_INTERPRETATION") =
        mapOf("partId" to id, "statementType" to type, "anchors" to anchors, "reason" to reason, "status" to "SUPPORTED")
    private fun result(checks: List<Map<String, Any>>) = mapOf("checks" to checks, "issues" to emptyList<String>(),
        "unknowns" to emptyList<String>(), "verdict" to "SUPPORTED")
    private fun parse(target: String, response: Map<String, Any>, sources: List<Map<String, Any?>> = records) =
        DeepSeekReviewProtocol.parse(AnalysisRunCodec.json(response), mapOf("target" to mapOf("text" to target), "evidence" to sources), false)
}
