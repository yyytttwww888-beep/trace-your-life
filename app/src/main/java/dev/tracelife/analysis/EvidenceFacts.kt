package dev.tracelife.analysis

/** Literal extraction is bounded assistance, never a complete semantic interpretation. */
enum class EvidenceFactKind { DURATION, DELAY, AVAILABLE_TIME, SEQUENCE, FIRST_STEP, TIME_ADEQUACY, COMPARISON, CONDITION_DIFFERENCE, ATTRIBUTION, ACTION_BEHAVIOR, EXECUTION }
enum class EvidenceFactMode { ASSERTED, NEGATED, PLANNED, HYPOTHETICAL }
data class EvidenceFactSource(
    val evidenceId: String, val sourceType: String, val recordId: String, val revision: Int,
    val field: String, val start: Int, val end: Int, val totalCodePoints: Int, val sha256: String,
    val textSha256: String, val from: String? = null, val through: String? = null,
)
data class EvidenceLiteralFact(
    val source: Int, val kind: EvidenceFactKind, val start: Int, val end: Int,
    val quote: String, val value: String, val related: String? = null,
    val mode: EvidenceFactMode = EvidenceFactMode.ASSERTED,
)
data class EvidenceFactPack(val version: Int, val sources: List<EvidenceFactSource>, val facts: List<EvidenceLiteralFact>, val sha256: String, val complete: Boolean = false)
enum class EvidenceFactRule { TIME_ROLE_CHANGED, STEP_ORDER_CHANGED, UNGROUNDED_ADEQUACY, UNGROUNDED_COMPARISON, ATTRIBUTION_CHANGED, EXECUTION_CHANGED }
data class EvidenceFactFinding(
    val rule: EvidenceFactRule, val targetStart: Int, val targetEnd: Int, val targetQuote: String,
    val sourceFacts: List<Int>, val reason: String,
)
data class EvidenceFactReview(val version: Int, val pack: EvidenceFactPack, val targetSha256: String, val findings: List<EvidenceFactFinding>,
    val targetText: String, val modelVerdict: ReviewVerdict? = null) {
    val blocked: Boolean get() = findings.isNotEmpty()
}

object EvidenceFacts {
    const val VERSION = 1
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val amount = Regex("([0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千万]+)(秒钟?|分钟|小时)")
    private val order = Regex("先([^，,。；;！？\\n]{1,45})[，,、](?:然后|再|接着|随后)([^，,。；;！？\\n]{1,45})")
    private val firstStep = Regex("先([^，,。；;！？\\n]{1,45})")
    private val adequacy = Regex("时间(?:也|很|较|比较|十分|非常|相对)?(充足|足够|充分|不够|不足|紧张)")
    private val comparison = Regex("(更|较|比较|更加)?(熟悉|复杂|简单|有效|稳定|困难|容易|清晰)")
    private val difference = Regex("([^，,。；;：:！？\\n]{2,40}?)(?:不同|不一样|有差异)")
    private val attribution = Regex("(?:记录者|用户|本人|我)(?:本人)?(?:把[^，,。；;！？\\n]{0,24})?(?:归因于|归因为)([^，,。；;！？\\n]{1,35})")
    private val execution = Regex("(已经|已|没有|尚未|还没|未曾|未能|没能)(?:执行|完成|实施)(?:了)?([^，,。；;！？\\n]{1,40})")
    private val actionFields = listOf("/plan/behavior", "/reviewedPlan/behavior", "/nextPlan/behavior", "/review/execution", "/review/observedText")
    private data class Literal(val kind: EvidenceFactKind, val start: Int, val end: Int, val quote: String,
        val value: String, val related: String? = null, val mode: EvidenceFactMode = EvidenceFactMode.ASSERTED)

    fun build(envelope: Map<String, Any?>): EvidenceFactPack? {
        val sources = mutableListOf<EvidenceFactSource>()
        val facts = mutableListOf<EvidenceLiteralFact>()
        fun append(value: Map<String, Any?>, field: String, text: String, action: Boolean) {
            require(text.isNotEmpty() && wellFormed(text) && text.codePointCount(0, text.length) <= 16_384)
            val start = if (action) 0 else integer(value["start"])
            val end = if (action) text.codePointCount(0, text.length) else integer(value["end"])
            val total = if (action) end else integer(value["totalCodePoints"])
            require(start >= 0 && end > start && end <= total && end - start == text.codePointCount(0, text.length))
            val review = value["review"] as? Map<*, *>
            val from = if (action) review?.get("reviewedOn") as? String else value["startDate"] as? String
            val through = if (action) from else value["endDate"] as? String
            val source = EvidenceFactSource(string(value, "id"), if (action) "ACTION" else "RECORD",
                string(value, if (action) "actionId" else "recordId"), integer(value["revision"]), field,
                start, end, total, string(value, "sha256"), AnalysisRunCodec.sha256(text), from, through)
            val index = sources.size
            sources += source
            val extracted = if (field == "/review/execution") listOf(Literal(EvidenceFactKind.EXECUTION, 0, text.length, text, text)) else
                extract(text) + if (action && field.endsWith("/behavior") && text.length <= 200)
                    listOf(Literal(EvidenceFactKind.ACTION_BEHAVIOR, 0, text.length, text, text)) else emptyList()
            facts += extracted.map { literal -> EvidenceLiteralFact(index, literal.kind,
                start + text.codePointCount(0, literal.start), start + text.codePointCount(0, literal.end),
                literal.quote, literal.value, literal.related, literal.mode) }
        }
        list(envelope["evidence"]).forEach { raw ->
            val value = obj(raw)
            append(value, string(value, "field"), string(value, "text"), false)
        }
        list(envelope["actionEvidence"]).forEach { raw ->
            val value = obj(raw)
            actionFields.forEach { field ->
                val text = pointer(value, field) as? String
                if (!text.isNullOrBlank()) append(value, field, text, true)
            }
        }
        if (sources.isEmpty()) return null
        val unsigned = EvidenceFactPack(VERSION, sources, facts.distinct(), "")
        return unsigned.copy(sha256 = AnalysisRunCodec.sha256(AnalysisRunCodec.json(packBody(unsigned)))).also(::validatePack)
    }

    fun check(target: String, pack: EvidenceFactPack): EvidenceFactReview {
        validatePack(pack)
        require(target.isNotBlank() && target.length <= 2_000 && wellFormed(target))
        return EvidenceFactReview(VERSION, pack, AnalysisRunCodec.sha256(target), findings(target, pack), target)
    }

    fun apply(review: ClaimReview, check: EvidenceFactReview): ClaimReview {
        validateReview(check)
        require(review.factCheck == null || review.factCheck == check || review.factCheck == check.copy(modelVerdict = review.verdict))
        val proof = check.copy(modelVerdict = review.verdict)
        if (!check.blocked) return review.copy(factCheck = proof)
        val explanations = check.findings.map { "原文事实核对：${it.reason}" }.distinct()
        val notes = (review.unknowns + explanations).distinct()
        require(notes.size <= 20 && notes.all { it.length <= 2_000 }) { "事实核对说明超出保存预算" }
        return review.copy(verdict = if (review.verdict == ReviewVerdict.SUPPORTED) ReviewVerdict.UNSUPPORTED else review.verdict,
            issues = (review.issues + ReviewIssue.OTHER).distinct(), unknowns = notes, factCheck = proof)
    }

    private fun findings(target: String, pack: EvidenceFactPack): List<EvidenceFactFinding> {
        val result = mutableListOf<EvidenceFactFinding>()
        val extracted = extract(target)
        fun add(rule: EvidenceFactRule, literal: Literal, refs: List<Int>, reason: String) {
            if (refs.isEmpty()) return
            result += EvidenceFactFinding(rule, target.codePointCount(0, literal.start), target.codePointCount(0, literal.end),
                literal.quote, refs.distinct().sorted(), reason)
        }
        extracted.forEach { current ->
            if (newProposal(target, current.start, current.end) || current.mode == EvidenceFactMode.HYPOTHETICAL || current.mode == EvidenceFactMode.NEGATED) return@forEach
            val eligible = pack.facts.indices.filter { index -> sameOccurrence(target, current.end, pack.sources[pack.facts[index].source]) }
            fun matching(predicate: (EvidenceLiteralFact) -> Boolean) = eligible.filter { predicate(pack.facts[it]) }
            when (current.kind) {
                EvidenceFactKind.DELAY -> {
                    val quantities = matching { it.value == current.value && positive(it) && it.kind in setOf(EvidenceFactKind.DURATION, EvidenceFactKind.DELAY, EvidenceFactKind.AVAILABLE_TIME) }
                    val roles = quantities.map { pack.facts[it].kind }.toSet()
                    val owners = quantities.map { pack.sources[pack.facts[it].source].recordId }.distinct()
                    if (roles == setOf(EvidenceFactKind.DURATION) && owners.size == 1 && quantities.all { temporalBinding(target, current, pack.facts[it]) })
                        add(EvidenceFactRule.TIME_ROLE_CHANGED, current, quantities, "原文数量表示持续时长，目标却改成等待后开始；同一数字不能替换时间角色。")
                }
                EvidenceFactKind.SEQUENCE -> {
                    val sequences = matching { it.kind == EvidenceFactKind.SEQUENCE &&
                        (it.mode == EvidenceFactMode.ASSERTED || it.mode == current.mode && current.mode == EvidenceFactMode.PLANNED) }
                    val same = sequences.any { pack.facts[it].value == current.value && pack.facts[it].related == current.related }
                    val opposite = sequences.filter { pack.facts[it].value == current.related && pack.facts[it].related == current.value }
                    if (!same && opposite.isNotEmpty() && oneOccurrence(target, current.end, pack))
                        add(EvidenceFactRule.STEP_ORDER_CHANGED, current, opposite, "目标两个步骤的先后与原文明确顺序相反。")
                }
                EvidenceFactKind.FIRST_STEP -> {
                    val sequences = matching { it.kind == EvidenceFactKind.SEQUENCE &&
                        (it.mode == EvidenceFactMode.ASSERTED || it.mode == current.mode && current.mode == EvidenceFactMode.PLANNED) }
                    val starts = matching { it.kind == EvidenceFactKind.FIRST_STEP && it.value == current.value &&
                        (it.mode == EvidenceFactMode.ASSERTED || it.mode == current.mode && current.mode == EvidenceFactMode.PLANNED) }
                    val second = sequences.filter { pack.facts[it].related == current.value && pack.facts[it].value != current.value }
                    if (starts.isEmpty() && second.isNotEmpty() && oneOccurrence(target, current.end, pack))
                        add(EvidenceFactRule.STEP_ORDER_CHANGED, current, second, "目标把原文后进行的步骤写成了起始步骤。")
                }
                EvidenceFactKind.TIME_ADEQUACY -> {
                    val explicit = matching { it.kind == EvidenceFactKind.TIME_ADEQUACY && it.value == current.value && it.mode == current.mode }
                    val available = matching { it.kind == EvidenceFactKind.AVAILABLE_TIME && it.mode == EvidenceFactMode.ASSERTED }
                    val owners = available.map { pack.sources[pack.facts[it].source].recordId }.distinct()
                    if (explicit.isEmpty() && available.isNotEmpty() && owners.size == 1 && oneOccurrence(target, current.end, pack))
                        add(EvidenceFactRule.UNGROUNDED_ADEQUACY, current, available, "原文明确的是可用时长，不能仅由数量认证时间充足或不足。")
                }
                EvidenceFactKind.COMPARISON -> if (current.related == "MORE") {
                    val explicit = matching { it.kind == EvidenceFactKind.COMPARISON && it.value == current.value && it.related == "MORE" && it.mode == EvidenceFactMode.ASSERTED }
                    val local = matching { it.kind == EvidenceFactKind.COMPARISON && it.value == current.value && it.related == "LOCAL" && it.mode == EvidenceFactMode.ASSERTED }
                    val owners = local.map { pack.sources[pack.facts[it].source].recordId }.distinct()
                    if (explicit.isEmpty() && local.isNotEmpty() && owners.size == 1 && oneOccurrence(target, current.end, pack))
                        add(EvidenceFactRule.UNGROUNDED_COMPARISON, current, local, "原文只描述本次条件，目标增加了“更”的比较关系，缺少明确比较依据。")
                }
                EvidenceFactKind.ATTRIBUTION -> {
                    val explicit = matching { it.kind == EvidenceFactKind.ATTRIBUTION && it.value == current.value && it.mode == EvidenceFactMode.ASSERTED }
                    val conditions = matching { it.kind == EvidenceFactKind.CONDITION_DIFFERENCE && it.value == current.value && it.mode == EvidenceFactMode.ASSERTED }
                    if (explicit.isEmpty() && conditions.isNotEmpty())
                        add(EvidenceFactRule.ATTRIBUTION_CHANGED, current, conditions, "原文说明条件不同，目标却称记录者已作因果归因；条件差异不等于用户表达了该观点。")
                }
                EvidenceFactKind.EXECUTION -> if (current.value == "EXECUTED") {
                    val behavior = current.related
                    if (behavior != null) {
                        val negatives = matching { it.kind == EvidenceFactKind.EXECUTION && it.value == "NOT_EXECUTED" && it.related == behavior && it.mode == EvidenceFactMode.ASSERTED }
                        val positives = matching { it.kind == EvidenceFactKind.EXECUTION && it.value == "EXECUTED" && it.related == behavior && it.mode == EvidenceFactMode.ASSERTED }
                        if (negatives.isNotEmpty() && positives.isEmpty() && oneOccurrence(target, current.end, pack))
                            add(EvidenceFactRule.EXECUTION_CHANGED, current, negatives, "原文明示该行为尚未完成或执行，目标却称已经执行。")
                        val actionBehavior = matching { it.kind == EvidenceFactKind.ACTION_BEHAVIOR && it.value == behavior &&
                            pack.sources[it.source].field == "/reviewedPlan/behavior" }
                        val owners = actionBehavior.map { pack.sources[pack.facts[it].source].recordId }.toSet()
                        val actionStates = matching { it.kind == EvidenceFactKind.EXECUTION && it.related == null &&
                            pack.sources[it.source].sourceType == "ACTION" && pack.sources[it.source].recordId in owners }
                        if (owners.size == 1 && actionStates.isNotEmpty() && actionStates.all { pack.facts[it].value == "NOT_EXECUTED" })
                            add(EvidenceFactRule.EXECUTION_CHANGED, current, actionStates + actionBehavior, "该行动回看明确报告未执行，目标却将同一行动写成已经完成。")
                    }
                }
                else -> Unit
            }
        }
        return result.distinct().also { require(it.size <= 24 && it.all { value -> value.targetQuote.length <= 500 }) }
    }

    /** Avoid borrowing a numerical/adjectival fact from one of several unbound events. */
    private fun oneOccurrence(target: String, position: Int, pack: EvidenceFactPack): Boolean =
        pack.sources.filter { sameOccurrence(target, position, it) }.map { Triple(it.sourceType, it.recordId, it.revision) }.distinct().size == 1

    private fun positive(fact: EvidenceLiteralFact): Boolean =
        fact.mode == EvidenceFactMode.ASSERTED || fact.mode == EvidenceFactMode.PLANNED

    private fun temporalBinding(target: String, literal: Literal, source: EvidenceLiteralFact): Boolean {
        val sourceAction = temporalAction(source.quote, source.kind)
        val targetAction = temporalAction(literal.quote, literal.kind)
        if (sourceAction != null && targetAction != null) return sourceAction == targetAction
        val preceding = target.substring(maxOf(0, literal.start - 160), literal.end)
        val reported = Regex("(?:原文|原记录|原计划|记录中|回看|提到)").containsMatchIn(preceding)
        // An exact repeated source clause provides a local occurrence anchor even if its
        // action was omitted. It must occur before this changed role, not somewhere else.
        val sourceClause = source.quote.trim()
        val repeated = sourceClause.length >= 6 && target.substring(maxOf(0, literal.start - 160), literal.start).contains(sourceClause)
        if (!reported && !repeated) return false
        // An omitted action can only be tied back through an explicit source reference.
        return sourceAction == null || targetAction == null
    }

    private fun temporalAction(text: String, kind: EvidenceFactKind): String? {
        val quantity = amount.find(text) ?: return null
        val before = text.substring(0, quantity.range.first)
        val after = text.substring(quantity.range.last + 1)
        val raw = if (kind == EvidenceFactKind.DELAY) {
            after.replace(Regex("^(?:(?:之|以)?后)?(?:再)?(?:开始)?"), "").ifBlank {
                before.replace(Regex("(?:先)?(?:等待|等候|等|间隔|隔|过)$"), "") }
        } else before
        val normalized = raw.substringAfterLast('：').substringAfterLast(':')
            .replace(Regex("^(?:(?:我|本人|记录者|用户)|(?:原记录中|原文|记录中)的?|计划(?:是)?|打算|想改成|下周|每天|每次|准备|先|开始|时长为|持续)+"), "")
            .replace(Regex("(?:只是|尚未|还没|没有|的计划|这个|这是|是设想|未执行).*$"), "").trim('“', '”', '「', '」', ' ', '。')
        return normalized.takeIf { it.length in 2..30 && !Regex("(?:计划|安排|改成|分钟|等待)").containsMatchIn(it) }
    }

    private fun sameOccurrence(text: String, position: Int, source: EvidenceFactSource): Boolean {
        val prefix = text.substring(sentenceStart(text, position), position)
        val date = Regex("(?:(\\d{4})[-年])?(\\d{1,2})[-月](\\d{1,2})日?").findAll(prefix).lastOrNull() ?: return true
        val from = source.from ?: return false
        if (source.through != from) return false
        val parsed = runCatching { java.time.LocalDate.parse(from) }.getOrNull() ?: return false
        return date.groupValues[2].toInt() == parsed.monthValue && date.groupValues[3].toInt() == parsed.dayOfMonth &&
            (date.groupValues[1].isEmpty() || date.groupValues[1].toInt() == parsed.year)
    }

    private fun extract(text: String): List<Literal> {
        val found = mutableListOf<Literal>()
        fun add(kind: EvidenceFactKind, match: MatchResult, value: String, related: String? = null) {
            val begin = clauseStart(text, match.range.first)
            val end = (match.range.last + 1 until text.length).firstOrNull { text[it] in "，,。；;！？!?\n" } ?: text.length
            val quote = text.substring(begin, end)
            if (quote.length > 500) return
            // A later "not executed" limits execution, not the role of an earlier duration.
            found += Literal(kind, begin, end, quote, value, related, mode(text.substring(begin, match.range.first)))
        }
        amount.findAll(text).forEach { match ->
            val number = number(match.groupValues[1]) ?: return@forEach
            val value = number.multiply(java.math.BigDecimal(when (match.groupValues[2]) { "分钟" -> 60; "小时" -> 3600; else -> 1 })).stripTrailingZeros().toPlainString()
            val before = text.substring(clauseStart(text, match.range.first), match.range.first)
            val after = text.substring(match.range.last + 1, minOf(text.length, match.range.last + 6))
            val kind = when {
                Regex("^(?:之|以)?后").containsMatchIn(after) || Regex("(?:等|等待|等候|间隔|隔|过)\\s*$").containsMatchIn(before) -> EvidenceFactKind.DELAY
                Regex("(?:空闲|可用|剩余|还剩|腾出|有)\\s*$").containsMatchIn(before) && Regex("^(?:的)?(?:空闲|时间|可用)").containsMatchIn(after) -> EvidenceFactKind.AVAILABLE_TIME
                Regex("(?:距离|还有|剩下|剩余|倒计时)").containsMatchIn(before) || after.startsWith("内") -> return@forEach
                else -> EvidenceFactKind.DURATION
            }
            add(kind, match, value)
        }
        order.findAll(text).forEach { add(EvidenceFactKind.SEQUENCE, it, action(it.groupValues[1]), action(it.groupValues[2])) }
        firstStep.findAll(text).forEach { add(EvidenceFactKind.FIRST_STEP, it, action(it.groupValues[1])) }
        adequacy.findAll(text).forEach { add(EvidenceFactKind.TIME_ADEQUACY, it, it.groupValues[1]) }
        comparison.findAll(text).forEach { add(EvidenceFactKind.COMPARISON, it, it.groupValues[2], if (it.groupValues[1].isBlank()) "LOCAL" else "MORE") }
        difference.findAll(text).forEach { match ->
            val phrase = match.groupValues[1].substringBeforeLast('与', match.groupValues[1]).substringBefore("相比")
            val value = factors(phrase)
            if (value.isNotBlank()) add(EvidenceFactKind.CONDITION_DIFFERENCE, match, value)
        }
        attribution.findAll(text).forEach { add(EvidenceFactKind.ATTRIBUTION, it, factors(it.groupValues[1])) }
        execution.findAll(text).forEach { add(EvidenceFactKind.EXECUTION, it,
            if (it.groupValues[1] in setOf("已", "已经")) "EXECUTED" else "NOT_EXECUTED", action(it.groupValues[2])) }
        return found.distinct()
    }

    private fun action(text: String): String = text.trim().replace(Regex("^(?:我|自己|本人)"), "").trim('“', '”', '「', '」', ' ', '。')
    private fun factors(text: String): String = text.trim().replace(Regex("^(?:本次|这次|当天|原文|自述|记录者|用户|我)+"), "")
        .replace(Regex("(?:以及|和|与|及|、|的|条件|差异|不同|[“”「」\\s])"), "")
    private fun mode(text: String): EvidenceFactMode = when {
        Regex("(?:如果|假如|假设|可能|也许|是否|能否)").containsMatchIn(text) -> EvidenceFactMode.HYPOTHETICAL
        Regex("(?:不是|并非|不曾|没有|未曾|未做|没做|不再|不要|尚未|还没|没能|未能)").containsMatchIn(text) -> EvidenceFactMode.NEGATED
        Regex("(?:计划|打算|想改|准备|下周|拟)").containsMatchIn(text) -> EvidenceFactMode.PLANNED
        else -> EvidenceFactMode.ASSERTED
    }
    private fun newProposal(text: String, position: Int, end: Int): Boolean {
        // A previous factual clause must not turn a newly introduced experiment into history.
        // Nor may advice in the next clause excuse an earlier historical assertion.
        val context = text.substring(clauseStart(text, position), end).substringBefore('，').substringBefore(',')
        val proposal = Regex("(?:下次|下一次|接下来|今后|未来|不妨|建议|可先|可以|可尝试|可考虑|试着)").find(context)
        val proposing = proposal != null
        val reporting = proposal?.let { Regex("(?:原文|原记录|记录中|记录者|自述|提到|说过|表示|归因)")
            .containsMatchIn(context.substring(0, it.range.first)) } ?: false
        return proposing && !reporting
    }
    private fun clauseStart(text: String, position: Int): Int = (position - 1 downTo 0).firstOrNull { text[it] in "，,。；;！？!?\n：:" }?.plus(1) ?: 0
    private fun sentenceStart(text: String, position: Int): Int = (position - 1 downTo 0).firstOrNull { text[it] in "。；;！？!?\n" }?.plus(1) ?: 0
    private fun number(text: String): java.math.BigDecimal? {
        text.toBigDecimalOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        val units = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10000)
        // Consecutive spoken digits such as 五六分钟 are an approximate range, not 56 minutes.
        if (text.all { it in digits }) return if (text.length == 1) java.math.BigDecimal(digits.getValue(text.single())) else null
        var total = 0L; var pending = 0; var previous = 10001
        var previousWasDigit = false
        text.forEach { character ->
            if (character in digits) {
                if (previousWasDigit && pending != 0) return null
                pending = digits.getValue(character); previousWasDigit = true
            } else {
                val unit = units[character] ?: return null
                if (unit >= previous) return null
                total += (if (pending == 0) 1 else pending) * unit
                pending = 0; previous = unit; previousWasDigit = false
            }
        }
        if (previous > 10 && pending != 0 && '零' !in text && '〇' !in text) return null
        return java.math.BigDecimal(total + pending)
    }

    fun packMap(pack: EvidenceFactPack): Map<String, Any?> = packBody(pack) + ("sha256" to pack.sha256)
    private fun packBody(pack: EvidenceFactPack): Map<String, Any?> = mapOf("version" to pack.version, "complete" to pack.complete,
        "sources" to pack.sources.map { source -> mapOf("id" to source.evidenceId, "type" to source.sourceType, "record" to source.recordId,
            "revision" to source.revision, "field" to source.field, "start" to source.start, "end" to source.end, "total" to source.totalCodePoints,
            "sha256" to source.sha256, "textSha256" to source.textSha256, "from" to source.from, "through" to source.through) },
        "facts" to pack.facts.map { fact -> mapOf("source" to fact.source, "kind" to fact.kind.name, "start" to fact.start, "end" to fact.end,
            "quote" to fact.quote, "value" to fact.value, "related" to fact.related, "mode" to fact.mode.name) })
    fun parsePack(raw: Any?): EvidenceFactPack {
        val value = obj(raw, setOf("version", "complete", "sources", "facts", "sha256"))
        val sources = requiredList(value, "sources").map { item ->
            val source = obj(item, setOf("id", "type", "record", "revision", "field", "start", "end", "total", "sha256", "textSha256", "from", "through"))
            EvidenceFactSource(string(source, "id"), string(source, "type"), string(source, "record"), integer(source["revision"]),
                string(source, "field"), integer(source["start"]), integer(source["end"]), integer(source["total"]),
                string(source, "sha256"), string(source, "textSha256"), nullableString(source, "from"), nullableString(source, "through"))
        }
        val facts = requiredList(value, "facts").map { item ->
            val fact = obj(item, setOf("source", "kind", "start", "end", "quote", "value", "related", "mode"))
            EvidenceLiteralFact(integer(fact["source"]), EvidenceFactKind.valueOf(string(fact, "kind")), integer(fact["start"]), integer(fact["end"]),
                string(fact, "quote"), string(fact, "value"), nullableString(fact, "related"), EvidenceFactMode.valueOf(string(fact, "mode")))
        }
        return EvidenceFactPack(integer(value["version"]), sources, facts, string(value, "sha256"), requireNotNull(value["complete"] as? Boolean)).also(::validatePack)
    }
    fun reviewMap(review: EvidenceFactReview): Map<String, Any?> = mapOf("version" to review.version, "pack" to packMap(review.pack),
        "targetSha256" to review.targetSha256, "targetText" to review.targetText, "modelVerdict" to review.modelVerdict?.name,
        "findings" to review.findings.map { finding -> mapOf("rule" to finding.rule.name, "start" to finding.targetStart, "end" to finding.targetEnd,
            "quote" to finding.targetQuote, "sourceFacts" to finding.sourceFacts, "reason" to finding.reason) })
    fun parseReview(raw: Any?): EvidenceFactReview {
        val value = obj(raw, setOf("version", "pack", "targetSha256", "targetText", "modelVerdict", "findings"))
        return EvidenceFactReview(integer(value["version"]), parsePack(value["pack"]), string(value, "targetSha256"), requiredList(value, "findings").map { item ->
            val finding = obj(item, setOf("rule", "start", "end", "quote", "sourceFacts", "reason"))
            EvidenceFactFinding(EvidenceFactRule.valueOf(string(finding, "rule")), integer(finding["start"]), integer(finding["end"]),
                string(finding, "quote"), requiredList(finding, "sourceFacts").map(::integer), string(finding, "reason"))
        }, string(value, "targetText"), nullableString(value, "modelVerdict")?.let(ReviewVerdict::valueOf)).also(::validateReview)
    }
    fun validatePack(pack: EvidenceFactPack) {
        require(pack.version == VERSION && !pack.complete && pack.sources.size in 1..160 && pack.facts.size <= 256)
        require(pack.sha256.matches(hashPattern) && pack.sha256 == AnalysisRunCodec.sha256(AnalysisRunCodec.json(packBody(pack))))
        require(pack.sources.distinct().size == pack.sources.size && pack.facts.distinct().size == pack.facts.size)
        pack.sources.forEach { source ->
            require(source.evidenceId.matches(hashPattern) && source.sourceType in setOf("RECORD", "ACTION") && source.recordId.isNotBlank() && source.recordId.length <= 200)
            require(source.revision > 0 && source.field.isNotBlank() && source.field.length <= 200 && source.start >= 0 && source.end > source.start && source.end <= source.totalCodePoints)
            require(source.sha256.matches(hashPattern) && source.textSha256.matches(hashPattern))
            require((source.from == null) == (source.through == null))
            source.from?.let { require(java.time.LocalDate.parse(it) <= java.time.LocalDate.parse(requireNotNull(source.through))) }
        }
        pack.facts.forEach { fact ->
            require(fact.source in pack.sources.indices)
            val source = pack.sources[fact.source]
            require(fact.start >= source.start && fact.end > fact.start && fact.end <= source.end && fact.quote.length <= 500 && wellFormed(fact.quote))
            require(fact.quote.codePointCount(0, fact.quote.length) == fact.end - fact.start && fact.value.isNotBlank() && fact.value.length <= 200 && (fact.related?.length ?: 0) <= 200)
            if (source.sourceType == "ACTION" && source.field == "/review/execution") {
                require(fact.kind == EvidenceFactKind.EXECUTION && fact.value == fact.quote && fact.value in setOf("EXECUTED", "NOT_EXECUTED", "UNKNOWN"))
            } else if (fact.kind == EvidenceFactKind.ACTION_BEHAVIOR) {
                require(source.sourceType == "ACTION" && source.field.endsWith("/behavior") && fact.quote == fact.value && fact.related == null)
            } else require(extract(fact.quote).any { it.kind == fact.kind && it.value == fact.value && it.related == fact.related && it.mode == fact.mode })
        }
        require(AnalysisRunCodec.json(packMap(pack)).toByteArray(Charsets.UTF_8).size <= 65_536) { "来源事实表超过预算" }
    }
    fun validateReview(review: EvidenceFactReview) {
        validatePack(review.pack)
        require(review.version == VERSION && review.targetText.isNotBlank() && review.targetText.length <= 2_000 && wellFormed(review.targetText))
        require(review.targetSha256 == AnalysisRunCodec.sha256(review.targetText) && review.findings == findings(review.targetText, review.pack))
        require(review.findings.all { it.reason.length <= 180 })
        require(AnalysisRunCodec.json(reviewMap(review) - "pack").toByteArray(Charsets.UTF_8).size <= 32_768)
    }
    private fun pointer(root: Map<String, Any?>, path: String): Any? = path.removePrefix("/").split('/').fold(root as Any?) { value, key -> (value as? Map<*, *>)?.get(key) }
    @Suppress("UNCHECKED_CAST") private fun obj(value: Any?, keys: Set<String>? = null): Map<String, Any?> =
        requireNotNull(value as? Map<String, Any?>).also { if (keys != null) require(it.keys == keys) }
    private fun string(value: Map<String, Any?>, key: String): String = requireNotNull(value[key] as? String)
    private fun nullableString(value: Map<String, Any?>, key: String): String? = value[key]?.let { requireNotNull(it as? String) }
    private fun integer(value: Any?): Int = requireNotNull((value as? Number)?.toString()?.toIntOrNull())
    private fun list(value: Any?): List<*> = if (value == null) emptyList<Any>() else requireNotNull(value as? List<*>)
    private fun requiredList(value: Map<String, Any?>, key: String): List<*> = requireNotNull(value[key] as? List<*>)
    private fun wellFormed(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            if (c.isHighSurrogate()) { if (index == value.length || !value[index++].isLowSurrogate()) return false }
            else if (c.isLowSurrogate()) return false
        }
        return true
    }
}
