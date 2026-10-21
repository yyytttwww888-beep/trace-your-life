package dev.tracelife.conversation

import dev.tracelife.storage.AnalysisQuery
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class ConversationIntent { CHANGES, REPEATED_ISSUES, EVIDENCE, IMPROVEMENT, NEXT_STEPS, GENERAL }

data class PlannedQuestion(
    val scope: AnalysisQuery,
    val focusTopics: Set<String>,
    val intent: ConversationIntent,
    val label: String,
    val clarification: String?,
    val refersToPrevious: Boolean,
    val ordinal: Int?,
    val problemOrdinal: Boolean,
)

/** Local, deterministic routing only. Unrecognized dates never silently become a precise claim.
 * Topics express focus, not a filter that discards unclassified records. */
class ConversationQuestionPlanner {
    fun prepare(question: String, today: LocalDate, zoneId: String, previous: AnalysisQuery? = null,
        scopeOverride: AnalysisQuery? = null, forcePrevious: Boolean = false): PlannedQuestion {
        require(question.isNotBlank() && question.length <= 1_000) { "请填写 1 至 1000 字的问题" }
        ZoneId.of(zoneId)
        val intent = when {
            Regex("依据|哪几次|哪些记录|哪几条|哪一天|证据|具体在哪").containsMatchIn(question) -> ConversationIntent.EVIDENCE
            Regex("后来|改善|好转|改进了吗|有效吗").containsMatchIn(question) -> ConversationIntent.IMPROVEMENT
            Regex("可以尝试|下周|接下来|下一步|怎么改|怎么办|该做什么|如何改|需要关注|未来.*(?:关注|提升|调整)").containsMatchIn(question) -> ConversationIntent.NEXT_STEPS
            Regex("变化|改变|不同|演变").containsMatchIn(question) -> ConversationIntent.CHANGES
            Regex("反复|重复|一直|老是").containsMatchIn(question) -> ConversationIntent.REPEATED_ISSUES
            else -> ConversationIntent.GENERAL
        }
        val ordinalMatch = Regex("第([一二三四五六七八九十两\\d]+)(?:个|条|项)?(问题|发现|建议|变化|结论)?").find(question)
        val ordinal = ordinalMatch?.groupValues?.get(1)?.let(::number)
        // Continuing a life activity ("值得继续试") does not refer to an earlier answer.
        // Only a continuation command implies that dependency; explicit selection still wins.
        val continueAnswer = Regex("^(?:(?:请问|请|帮我|帮忙|麻烦|劳驾|那就|那么|能不能|能否|能|可不可以|是否可以|可以|是否能)\\s*)*继续" +
            "(?:[，,：:。！？!?\\s]|$|(?:(?:帮我|详细|具体|深入|再)\\s*)*(?:说|讲|分析|解释|展开|回答)|上一|刚才|那|这|(?:一下吧?|吧)(?:[，,：:。！？!?\\s]|$))")
            .containsMatchIn(question.trim())
        val refers = forcePrevious || ordinalMatch != null || continueAnswer || Regex("后来|那[个条项]|这个问题|这[个条项]建议|上述|刚才|具体在哪|再详细|为什么(?:会|总是)?这样|^为什么[？?]?$|有没有改善|不是这样|不是这个意思|不对|这不准确|我的意思").containsMatchIn(question) ||
            (previous != null && Regex("^((那|那么|所以|接下来|下周)|.*(依据|哪几次|哪些记录|怎么办|可以尝试什么))").containsMatchIn(question))
        val topics = linkedSetOf<String>()
        mapOf("学习" to "学习|读书|阅读|练习|课程|考试", "工作" to "工作|项目|职场|同事|职业",
            "睡眠" to "睡眠|睡觉|入睡|熬夜", "运动" to "运动|跑步|健身|锻炼", "饮食" to "饮食|吃饭|食物|早餐|午餐|晚餐",
            "情绪" to "情绪|焦虑|心情|压力", "关系" to "关系|家人|朋友|伴侣").forEach { (topic, pattern) ->
            if (Regex(pattern).containsMatchIn(question)) topics += topic
        }
        val dates = parseDates(question, today, intent)
        val fallback = if (refers && previous != null) previous.copy(through = if (intent == ConversationIntent.IMPROVEMENT)
            maxOf(today, previous.through) else previous.through) else AnalysisQuery(today.minusDays(29), today, zoneId)
        val scope = scopeOverride ?: dates.first?.let { (from, through) -> AnalysisQuery(from, through, zoneId) } ?: fallback
        require(scope.from <= scope.through && scope.from.year in 1..9999 && scope.through.year in 1..9999) { "开始日期须早于或等于结束日期" }
        ZoneId.of(scope.zoneId)
        val clarification = if (scopeOverride != null) null else dates.second
        val label = when (intent) {
            ConversationIntent.CHANGES -> if (Regex("反复|重复").containsMatchIn(question)) "方法变化与反复出现的问题" else "这段时间的变化"
            ConversationIntent.REPEATED_ISSUES -> "反复出现的问题与条件"
            ConversationIntent.EVIDENCE -> "查阅具体依据"
            ConversationIntent.IMPROVEMENT -> "对照后来是否有改善"
            ConversationIntent.NEXT_STEPS -> "基于已有记录提出可尝试的做法"
            ConversationIntent.GENERAL -> "围绕你的问题回看记录"
        }
        return PlannedQuestion(scope, topics, intent, label, clarification, refers, ordinal,
            ordinalMatch?.groupValues?.get(2) == "问题")
    }

    private fun parseDates(text: String, today: LocalDate, intent: ConversationIntent): Pair<Pair<LocalDate, LocalDate>?, String?> {
        val candidates = mutableListOf<Pair<LocalDate, LocalDate>>()
        var remaining = text
        var error: String? = null
        // Consume full dates before relative expressions, so invalid dates cannot fall back unnoticed.
        val absolute = Regex("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?")
        val matches = absolute.findAll(remaining).toList()
        if (matches.isNotEmpty()) {
            val parsed = matches.mapNotNull { m -> runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() }
            if (parsed.size != matches.size || parsed.size > 2) error = "日期无法确定，请在下方填写要回看的起止日期。"
            else if (parsed.size == 2 && parsed[0] > parsed[1]) error = "开始日期晚于结束日期，请调整起止日期。"
            else candidates += parsed.first() to parsed.last()
            remaining = absolute.replace(remaining, "")
        }
        val relative = Regex("(?:最近|过去|近|这)([一二三四五六七八九十百两\\d]+)(?:个)?(星期|周|天|日|月|年)")
        relative.findAll(remaining).forEach { m ->
            val n = number(m.groupValues[1])
            if (n == null || n !in 1..3650) error = "时间跨度无法确定，请填写起止日期。"
            else {
                val from = when (m.groupValues[2]) {
                    "星期", "周" -> today.minusWeeks(n.toLong()).plusDays(1)
                    "月" -> today.minusMonths(n.toLong()).plusDays(1)
                    "年" -> today.minusYears(n.toLong()).plusDays(1)
                    else -> today.minusDays(n.toLong() - 1)
                }
                if (from.year < 1) error = "时间跨度过大，请填写起止日期。" else candidates += from to today
            }
        }
        remaining = relative.replace(remaining, "")
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val month = today.withDayOfMonth(1)
        val quarter = today.withMonth((today.monthValue - 1) / 3 * 3 + 1).withDayOfMonth(1)
        val named = listOf(
            "上上个星期|上上星期|上上周" to (monday.minusWeeks(2) to monday.minusWeeks(1).minusDays(1)),
            "上上个月|上上月" to (month.minusMonths(2) to month.minusMonths(1).minusDays(1)),
            "上个季度|上季度" to (quarter.minusMonths(3) to quarter.minusDays(1)), "这个季度|本季度|这季度" to (quarter to today),
            "上个月|上月" to (month.minusMonths(1) to month.minusDays(1)), "这个月|本月|这月" to (month to today),
            "上个星期|上星期|上周" to (monday.minusWeeks(1) to monday.minusDays(1)), "这个星期|本星期|本周|这周" to (monday to today),
            "去年" to (today.minusYears(1).withDayOfYear(1) to today.withDayOfYear(1).minusDays(1)), "今年" to (today.withDayOfYear(1) to today),
            "前天" to (today.minusDays(2) to today.minusDays(2)), "昨天|昨晚" to (today.minusDays(1) to today.minusDays(1)), "今天" to (today to today),
        )
        named.forEach { (pattern, range) -> if (Regex(pattern).containsMatchIn(remaining)) {
            candidates += range; remaining = Regex(pattern).replace(remaining, "")
        } }
        val future = Regex("下周|下个月|明天|后天|明年|未来")
        if (future.containsMatchIn(remaining) && intent != ConversationIntent.NEXT_STEPS)
            error = "未来的情况还没有记录，请选择已有记录的日期；也可以问接下来能尝试什么。"
        remaining = future.replace(remaining, "")
        if (Regex("\\d{4}[-/年]|[一二三四五六七八九十\\d]+月|前[一二三四五六七八九十几\\d]+[天周月年]|前段时间|那段时间|最近|过去|这几[天周月年]").containsMatchIn(remaining))
            error = "这段时间还不明确，请在下方填写起止日期。"
        val distinct = candidates.distinct()
        if (distinct.size > 1) error = "问题里有多个时间范围，请选择本次一并回看的起止日期。"
        val result = distinct.singleOrNull()
        if (result != null && result.second > today) error = "结束日期在未来，请选择已有记录的日期。"
        return result to error
    }

    private fun number(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        val digits = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (value.length == 1) return if (value == "十") 10 else digits[value[0]]
        if (value.count { it == '十' } != 1 || value.length > 3) return null
        val parts = value.split('十')
        val tens = if (parts[0].isEmpty()) 1 else parts[0].singleOrNull()?.let(digits::get) ?: return null
        val units = if (parts[1].isEmpty()) 0 else parts[1].singleOrNull()?.let(digits::get) ?: return null
        return tens * 10 + units
    }
}
