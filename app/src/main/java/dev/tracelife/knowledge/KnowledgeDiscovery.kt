package dev.tracelife.knowledge

import dev.tracelife.model.RecordFilter
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.text.Normalizer
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

enum class DiscoveryKind { RECORD, KNOWLEDGE, EXPERIENCE }
data class DiscoveryQuery(val text: String, val from: LocalDate? = null, val through: LocalDate? = null,
    val topic: String? = null, val includePending: Boolean = false) {
    init {
        require(text.isNotBlank() && text.length <= 200) { "请填写 1 至 200 字的查找内容" }
        require(from == null || through == null || from <= through) { "结束日期不能早于开始日期" }
        require(listOfNotNull(from, through).all { it.year in 1..9999 })
        require(topic == null || topic.isNotBlank() && topic.length <= 80)
    }
}
data class DiscoveryHit(val kind: DiscoveryKind, val id: String, val revision: Int, val title: String,
    val excerpt: String, val topics: Set<String>, val from: LocalDate?, val through: LocalDate?,
    val state: KnowledgeEvidenceState?, val matches: List<String>, val score: Int, val updatedAt: Instant, val experienceSequence: Int? = null) {
    val key get() = "${kind.name}:$id"
}
data class DiscoveryPage(val query: DiscoveryQuery, val source: SourceStamp, val generation: Long,
    val evaluatedAt: Instant, val validUntil: Instant?, val items: List<DiscoveryHit>, val total: Int,
    val scanned: Int, val omittedPending: Int, val unknownDates: Int, val hasNext: Boolean, val limit: Int)
data class TopicSuggestion(val topic: String, val reason: String)
data class ExperienceSuggestion(val thread: StoredExperienceThread, val current: KnowledgeView,
    val reasons: List<String>, val score: Int)

/** Local navigation only: scans one bounded page at a time, retaining only the requested results.
 * These functions are never model tools and never convert a text match into a semantic conclusion. */
class KnowledgeDiscovery(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    fun search(query: DiscoveryQuery, limit: Int = 20, previous: DiscoveryPage? = null): DiscoveryPage = consistent { source, generation ->
        require(limit in 1..50)
        val now = clock.instant()
        if (previous != null) {
            require(previous.query == query && previous.limit == limit && previous.hasNext)
            if (previous.source != source || previous.generation != generation || now < previous.evaluatedAt ||
                previous.validUntil?.let { now >= it } == true) throw StaleSnapshotException("查找范围已有变化，请重新查找")
        }
        val evaluated = previous?.evaluatedAt ?: now
        val at = Clock.fixed(evaluated, clock.zone)
        val matcher = TextMatch(query.text)
        val aliases = TopicAliases(repository)
        val aliasWords = aliases.matching(query.text)
        val topicWords = query.topic?.let { aliases.expand(setOf(it)).map(TextMatch::normalize).toSet() }
        fun matchesTopics(topics: Set<String>) = topicWords == null || topics.any { TextMatch.normalize(it) in topicWords }
        fun match(text: String): TextMatch.Match {
            val literal = matcher.match(text)
            val normalized = TextMatch.normalize(text)
            val found = aliasWords.filter { TopicAliases.containsWord(normalized, it) }
            val score = if (found.isEmpty()) 0 else 500 + found.size
            return if (score > literal.score) TextMatch.Match(score, found.take(8)) else literal
        }
        val selected = mutableListOf<DiscoveryHit>()
        val after = previous?.items?.lastOrNull()
        var total = 0; var scanned = 0; var pending = 0; var unknown = 0; var afterCount = 0
        var until: Instant? = null
        fun accept(hit: DiscoveryHit) {
            total++
            if (after != null && ORDER.compare(hit, after) <= 0) return
            afterCount++
            selected += hit; selected.sortWith(ORDER)
            if (selected.size > limit) selected.removeAt(selected.lastIndex)
        }
        var offset = 0
        do {
            val page = repository.listRecords(RecordFilter(query.from, query.through, limit = 30, offset = offset))
            for (record in page.items) {
                scanned++
                if (!matchesTopics(record.draft.topics) || aliases.isConfiguration(record)) continue
                if (record.draft.occurrence.startDate == null) unknown++
                val fields = listOf(record.draft.body) + record.draft.answers.values
                val best = fields.map { it to match(it) }.maxByOrNull { it.second.score } ?: continue
                if (best.second.score == 0) continue
                accept(DiscoveryHit(DiscoveryKind.RECORD, record.id, record.revision,
                    record.draft.occurrence.startDate?.toString() ?: "日期不确定的记录", matcher.excerpt(best.first, best.second),
                    record.draft.topics, record.draft.occurrence.startDate, record.draft.occurrence.endDate,
                    null, best.second.terms, best.second.score, record.updatedAt))
            }
            offset = page.nextOffset ?: -1
        } while (offset >= 0)
        val library = KnowledgeLibrary(repository, at)
        library.scanCurrent(KnowledgeFilter(query.from, query.through, includeInvalid = true)) { view ->
            scanned++
            val value = view.value
            if (!matchesTopics(value.topics)) return@scanCurrent
            if (view.evidenceState == KnowledgeEvidenceState.CURRENT) value.expiresAt?.let { until = until?.let { old -> minOf(old, it) } ?: it }
            val matched = match(value.claim.text)
            if (matched.score == 0) return@scanCurrent
            if (!query.includePending && view.evidenceState != KnowledgeEvidenceState.CURRENT) { pending++; return@scanCurrent }
            accept(DiscoveryHit(DiscoveryKind.KNOWLEDGE, value.id, value.revision, "已保存的理解",
                matcher.excerpt(value.claim.text, matched), value.topics, value.scope.from, value.scope.through,
                view.evidenceState, matched.terms, matched.score, value.publishedAt))
        }
        offset = 0
        do {
            val threads = repository.experienceThreads(offset = offset, limit = 50)
            for (thread in threads) {
                scanned++
                if (!matchesTopics(thread.topics)) continue
                var bestHit: DiscoveryHit? = null
                var changeOffset = 0
                do {
                    val changes = repository.experienceHistory(thread.id, changeOffset, 50)
                    for (change in changes) {
                        val knowledge = library.get(change.knowledgeId, change.knowledgeRevision)
                        // Date filters refer to each fixed publication's source range, never the note's save date.
                        if ((query.from != null || query.through != null) && (knowledge == null ||
                            query.from?.let { knowledge.value.scope.through < it } == true ||
                            query.through?.let { knowledge.value.scope.from > it } == true)) continue
                        val fields = listOf(thread.title, change.userNote.orEmpty(), knowledge?.value?.claim?.text.orEmpty())
                        val best = fields.map { it to match(it) }.maxByOrNull { it.second.score } ?: continue
                        if (best.second.score == 0) continue
                        if (!query.includePending && knowledge?.evidenceState != KnowledgeEvidenceState.CURRENT) { pending++; continue }
                        knowledge?.value?.expiresAt?.takeIf { it > now }?.let { until = until?.let { old -> minOf(old, it) } ?: it }
                        val hit = DiscoveryHit(DiscoveryKind.EXPERIENCE, thread.id, thread.version,
                            "${thread.title} · 第 ${change.sequence} 次变化", matcher.excerpt(best.first, best.second),
                            thread.topics, knowledge?.value?.scope?.from, knowledge?.value?.scope?.through,
                            knowledge?.evidenceState, best.second.terms, best.second.score, change.createdAt, change.sequence)
                        if (bestHit == null || ORDER.compare(hit, bestHit) < 0) bestHit = hit
                    }
                    changeOffset += changes.size
                } while (changes.size == 50)
                bestHit?.let(::accept)
            }
            offset += threads.size
        } while (threads.size == 50)
        DiscoveryPage(query, source, generation, evaluated, until, selected, total, scanned, pending, unknown,
            afterCount > selected.size, limit)
    }

    fun suggestTopics(text: String, existing: Set<String> = emptySet(), limit: Int = 6): List<TopicSuggestion> = consistent { _, _ ->
        require(text.length <= 250_000 && limit in 1..10)
        if (text.isBlank()) return@consistent emptyList()
        val normalized = TextMatch.normalize(text)
        val suggestions = linkedMapOf<String, TopicSuggestion>()
        val known = KnowledgeJourney(repository, clock).topics().map { it.name }
        // Existing spelling wins. We never rename or merge user tags automatically.
        for (topic in known) {
            if (topic !in existing && normalized.contains(TextMatch.normalize(topic)))
                suggestions[topic] = TopicSuggestion(topic, "正文提到了你使用过的「$topic」")
        }
        for ((topic, cues) in TOPIC_CUES) {
            if (topic in existing || suggestions.containsKey(topic)) continue
            val found = cues.firstOrNull { normalized.contains(it) } ?: continue
            suggestions[topic] = TopicSuggestion(topic, "正文提到「$found」，可归入此主题")
        }
        suggestions.values.take(limit)
    }

    fun suggestExperiences(knowledgeId: String, revision: Int, limit: Int = 5): List<ExperienceSuggestion> = consistent { _, _ ->
        require(limit in 1..10)
        val library = KnowledgeLibrary(repository, clock)
        val source = requireNotNull(library.get(knowledgeId, revision)) { "这条知识已删除" }
        require(source.isCurrentRevision && source.evidenceState == KnowledgeEvidenceState.CURRENT) { "请先更新这条知识" }
        val matcher = TextMatch(source.value.claim.text, maxTerms = 256)
        val sourceTopics = TopicAliases(repository).expand(source.value.topics).map(TextMatch::normalize).toSet()
        val result = mutableListOf<ExperienceSuggestion>()
        var offset = 0
        do {
            val threads = repository.experienceThreads(offset = offset, limit = 50)
            for (thread in threads) {
                val adopted = requireNotNull(repository.experienceCurrentChange(thread.id))
                val current = library.get(adopted.knowledgeId, adopted.knowledgeRevision) ?: continue
                if (current.evidenceState != KnowledgeEvidenceState.CURRENT || !current.isCurrentRevision) continue
                // The user can still browse all threads; recommendations omit a duplicate source.
                var eventOffset = 0; var alreadyLinked = false
                do {
                    val events = repository.experienceHistory(thread.id, eventOffset, 100)
                    if (events.any { it.knowledgeId == knowledgeId && it.knowledgeRevision == revision }) { alreadyLinked = true; break }
                    eventOffset += events.size
                } while (events.size == 100)
                if (alreadyLinked) continue
                val shared = thread.topics.filter { TextMatch.normalize(it) in sourceTopics }.toSet()
                val match = matcher.match(thread.title + "\n" + current.value.claim.text)
                if (shared.isEmpty() && match.terms.size < 2) continue
                val reasons = buildList {
                    if (shared.isNotEmpty()) add("共同主题：${shared.sorted().joinToString("、")}")
                    if (match.terms.isNotEmpty()) add("文字中都出现：${match.terms.take(4).joinToString("、")}")
                }
                result += ExperienceSuggestion(thread, current, reasons, shared.size * 30 + match.score)
                result.sortWith(compareByDescending<ExperienceSuggestion> { it.score }.thenBy { it.thread.id })
                if (result.size > limit) result.removeAt(result.lastIndex)
            }
            offset += threads.size
        } while (threads.size == 50)
        result
    }

    private fun <T> consistent(block: (SourceStamp, Long) -> T): T {
        val source = repository.analysisSourceStamp(); val generation = repository.currentAnalysisGeneration()
        val result = block(source, generation)
        if (source != repository.analysisSourceStamp() || generation != repository.currentAnalysisGeneration())
            throw StaleSnapshotException("资料已有变化，请重新打开")
        return result
    }

    companion object {
        private val ORDER = compareByDescending<DiscoveryHit> { it.score }.thenByDescending { it.updatedAt }.thenBy { it.key }
        private val TOPIC_CUES = linkedMapOf(
            "睡眠" to listOf("睡眠", "失眠", "入睡", "熬夜", "早醒", "午睡"),
            "运动" to listOf("跑步", "散步", "游泳", "骑行", "健身", "瑜伽", "运动"),
            "饮食" to listOf("早餐", "午餐", "晚餐", "外卖", "饮食", "零食"),
            "工作" to listOf("工作", "同事", "会议", "项目", "交付", "加班"),
            "学习" to listOf("学习", "读书", "课程", "练习", "复习", "阅读", "学到"),
            "情绪" to listOf("焦虑", "难过", "开心", "生气", "情绪", "烦躁"),
            "关系" to listOf("家人", "朋友", "伴侣", "沟通", "相处"),
        )
    }
}

/** Transparent text retrieval, including Chinese character bigrams; no semantic truth claims. */
internal class TextMatch(text: String, maxTerms: Int = 64) {
    data class Match(val score: Int, val terms: List<String>)
    private val phrase = normalize(text)
    private val terms = buildList {
        Regex("[\\p{IsHan}]+|[\\p{L}\\p{N}_]+").findAll(phrase).forEach { part ->
            val value = part.value
            if (value.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
                val points = value.codePoints().toArray()
                if (points.size == 1) add(value)
                else points.toList().windowed(2).forEach { add(String(it.toIntArray(), 0, 2)) }
            } else if (value.length >= 2) add(value)
        }
    }.distinct().filterNot { it in STOP_WORDS }.take(maxTerms)

    fun match(text: String): Match {
        val normalized = normalize(text)
        val matching = terms.filter { normalized.contains(it) }
        val exact = phrase.isNotBlank() && normalized.contains(phrase)
        val enough = matching.size >= if (terms.size > 3) 2 else 1
        return Match(if (exact) 1_000 + matching.size else if (enough) matching.size * 10 else 0, matching.take(8))
    }

    fun excerpt(text: String, match: Match, size: Int = 180): String {
        // Keep a map to original UTF-16 offsets: case folding and NFKC may expand characters.
        val normalized = StringBuilder()
        val originalOffsets = ArrayList<Int>()
        var offset = 0
        while (offset < text.length) {
            val point = text.codePointAt(offset)
            val folded = normalize(String(Character.toChars(point)))
            normalized.append(folded)
            repeat(folded.length) { originalOffsets += offset }
            offset += Character.charCount(point)
        }
        val matchedOffset = match.terms.map { normalized.indexOf(it) }.filter { it >= 0 }.minOrNull()
        val first = matchedOffset?.let { originalOffsets[it] } ?: 0
        val before = text.codePointCount(0, first)
        val start = (before - 35).coerceAtLeast(0)
        val count = text.codePointCount(0, text.length)
        val end = minOf(count, start + size)
        return (if (start > 0) "…" else "") + text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end)) + if (end < count) "…" else ""
    }
    companion object {
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
        private val STOP_WORDS = setOf("我想", "想找", "找找", "找一", "一下", "以前", "之前", "哪些", "什么", "时候", "事情", "记录", "合成", "值得", "留下", "可以", "自己", "这次", "不能", "可能", "一个", "我的", "怎么", "如何", "相关", "情况", "the", "and", "with")
    }
}
