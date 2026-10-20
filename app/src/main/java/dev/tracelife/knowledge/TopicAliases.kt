package dev.tracelife.knowledge

import dev.tracelife.model.*
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.TopicAliasInput
import java.text.Normalizer
import java.util.Collections
import java.util.Locale

data class TopicAliasRule(val id: String, val revision: Int, val canonical: String, val aliases: Set<String>, val archived: Boolean)
data class TopicAliasProblem(val id: String, val revision: Int, val archived: Boolean, val reason: String)
data class TopicAliasPage(val items: List<TopicAliasRule>, val total: Int, val nextOffset: Int?, val problems: List<TopicAliasProblem> = emptyList())

/** User-owned navigation vocabulary. These rules never rewrite tags or establish facts about life. */
class TopicAliases(private val repository: LifeRepository) {
    fun page(offset: Int = 0, limit: Int = 25, includeArchived: Boolean = false): TopicAliasPage {
        require(offset >= 0 && limit in 1..100)
        val inputs = repository.topicAliasInputs(offset, limit, includeArchived)
        require(inputs.total <= MAX_TOTAL) { "主题叫法目录超过上限，请先归档或删除多余规则" }
        val problems = mutableListOf<TopicAliasProblem>()
        val items = inputs.items.mapNotNull { input ->
            try { rule(input) } catch (failure: IllegalArgumentException) {
                problems += TopicAliasProblem(input.id, input.revision, input.archived,
                    failure.message ?: "配置不完整，请修复后使用。")
                null
            }
        }
        return TopicAliasPage(items, inputs.total, inputs.nextOffset, problems)
    }

    fun get(id: String): TopicAliasRule? = repository.withTopicAliasTransaction {
        val record = repository.record(id) ?: return@withTopicAliasTransaction null
        if (!isConfiguration(record)) return@withTopicAliasTransaction null
        rule(TopicAliasInput(record.id, record.revision, record.draft.answers[TopicAliasTemplate.CANONICAL].orEmpty(),
            record.draft.answers[TopicAliasTemplate.ALIASES].orEmpty(), record.archived))
    }

    fun save(canonical: String, aliases: Set<String>, id: String? = null, expectedRevision: Int? = null): TopicAliasRule =
        repository.withTopicAliasTransaction {
            require((id == null) == (expectedRevision == null)) { "修改规则须携带实际查看的版本" }
            val normalized = normalize(canonical)
            val terms = normalizeAliases(normalized, aliases)
            val current = id?.let { requireNotNull(repository.record(it)).also { value ->
                require(isConfiguration(value)) { "规则已删除或其模板内容已改变" }
            } }
            if (current != null && current.revision != expectedRevision) throw RevisionConflictException("规则已有新版本，请重新查看")
            val total = repository.topicAliasInputs(0, 1, true).total
            require(id != null || total < MAX_TOTAL) { "主题叫法最多保存 $MAX_TOTAL 项，请先清理历史规则" }
            if (current?.archived != true) validateActive(id, normalized, terms)
            val template = current?.draft?.template ?: TopicAliasTemplate.obtain(repository)
            val draft = (current?.draft ?: RecordDraft(BODY, template = template)).copy(answers = mapOf(
                TopicAliasTemplate.CANONICAL to normalized, TopicAliasTemplate.ALIASES to terms.joinToString("\n")))
            val saved = if (current == null) repository.createRecord(draft)
                else repository.updateRecord(current.id, requireNotNull(expectedRevision), draft)
            requireNotNull(get(saved.id))
        }

    fun archive(id: String, expectedRevision: Int, archived: Boolean = true): TopicAliasRule = repository.withTopicAliasTransaction {
        val current = requireNotNull(get(id)) { "规则已删除或模板内容已改变" }
        if (current.revision != expectedRevision) throw RevisionConflictException("规则已有新版本，请重新查看")
        if (!archived) validateActive(id, current.canonical, current.aliases)
        repository.setArchived(id, expectedRevision, archived)
        requireNotNull(get(id))
    }

    /** Existing malformed configuration stays visible and repairable without becoming navigation. */
    fun problemRecord(id: String, expectedRevision: Int): RecordRevision = repository.withTopicAliasTransaction {
        val record = requireNotNull(repository.record(id)) { "配置已删除" }
        require(isConfiguration(record)) { "这不是主题叫法配置" }
        if (record.revision != expectedRevision) throw RevisionConflictException("配置已变化，请重新查看")
        record
    }

    fun archiveProblem(id: String, expectedRevision: Int) = repository.withTopicAliasTransaction {
        problemRecord(id, expectedRevision)
        repository.setArchived(id, expectedRevision, true)
    }

    /** Match normalized exact words; bounded expansion is navigation, never a factual inference. */
    fun expand(topics: Set<String>): Set<String> = repository.withTopicAliasTransaction {
        require(topics.size <= 100) { "一次最多拓展 100 个导航词" }
        if (topics.isEmpty()) return@withTopicAliasTransaction emptySet()
        val normalized = topics.map(::normalize).toSet()
        val rules = active()
        ensureUnique(rules)
        val result = linkedSetOf<String>().apply { addAll(topics); addAll(normalized) }
        rules.forEach { rule ->
            val group = rule.aliases + rule.canonical
            if (normalized.any { it in group }) result.addAll(group)
        }
        Collections.unmodifiableSet(result)
    }

    /** Select only user-defined groups actually named in the question; no whole dictionary enters model context. */
    fun matching(text: String): Set<String> = repository.withTopicAliasTransaction {
        require(text.length <= 250_000)
        val normalized = TextMatch.normalize(text)
        val rules = active()
        ensureUnique(rules)
        rules.flatMap { rule ->
            val group = rule.aliases + rule.canonical
            if (group.any { containsWord(normalized, it) }) group else emptySet()
        }.toSet()
    }

    fun isConfiguration(record: RecordRevision): Boolean = record.draft.template?.let { ref ->
        repository.template(ref.id, ref.version)?.draft?.questions?.let(TopicAliasTemplate::supports)
    } == true

    private fun validateActive(excluding: String?, canonical: String, aliases: Set<String>) {
        val other = active().filter { it.id != excluding }
        require(other.size < MAX_ACTIVE) { "最多同时启用 $MAX_ACTIVE 个主题叫法，请先归档暂时不用的规则" }
        ensureUnique(other + TopicAliasRule(excluding.orEmpty(), 0, canonical, aliases, false))
    }

    private fun active(): List<TopicAliasRule> {
        val rules = mutableListOf<TopicAliasRule>()
        var offset = 0
        do {
            val inputs = repository.topicAliasInputs(offset, 100, false)
            // Rejected rows remain explicitly listed on the management page with repair controls.
            inputs.items.forEach { input ->
                try { rules += rule(input) } catch (_: IllegalArgumentException) { }
            }
            require(rules.size <= MAX_ACTIVE) { "启用的主题叫法超过 $MAX_ACTIVE 项，请到主题叫法归档暂时不用的规则" }
            offset = inputs.nextOffset ?: -1
        } while (offset >= 0)
        return rules
    }

    private fun ensureUnique(rules: List<TopicAliasRule>) {
        val owners = mutableMapOf<String, String>()
        rules.forEach { rule -> (rule.aliases + rule.canonical).forEach { term ->
            require(owners.putIfAbsent(term, rule.id) == null) { "“$term”已属于另一组叫法，请先修改或归档冲突规则" }
        } }
    }

    private fun rule(input: TopicAliasInput): TopicAliasRule {
        require(input.problem == null) { requireNotNull(input.problem) }
        val canonical = normalize(input.canonical)
        val aliases = normalizeAliases(canonical, input.aliasesText.split('\n').toSet())
        return TopicAliasRule(input.id, input.revision, canonical, Collections.unmodifiableSet(aliases), input.archived)
    }

    companion object {
        const val MAX_ACTIVE = 100
        const val MAX_TOTAL = 10_000
        private const val BODY = "主题叫法配置：只用于导航与检索；不是生活事件、个人事实或行为证据。"
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT).also {
            require(it.isNotEmpty() && it.codePointCount(0, it.length) <= 80 && it.none(Char::isISOControl)) { "每个叫法须为 1–80 个字符，不能含换行或控制字符" }
        }
        internal fun containsWord(text: String, word: String): Boolean {
            if (word.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return word in text
            return Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(word) + "(?![\\p{L}\\p{N}_])").containsMatchIn(text)
        }
        private fun normalizeAliases(canonical: String, aliases: Set<String>): LinkedHashSet<String> =
            aliases.map(::normalize).filter { it != canonical }.toCollection(linkedSetOf()).also {
                require(it.size in 1..20) { "每组须有 1–20 个不同于主叫法的别名" }
            }
    }
}

/** Exact question content is the contract; template names and user supplied intents alone are insufficient. */
object TopicAliasTemplate {
    const val CANONICAL = "navigation.alias.v1.canonical"
    const val ALIASES = "navigation.alias.v1.aliases"
    val draft = TemplateDraft("主题叫法", Cadence.EVENT, "本模板保存用户指定的导航规则，不是生活记录，不作为事实或行为证据。", listOf(
        TemplateQuestion(CANONICAL, "主要叫法", "仅用于查找和主题导航，不改写原始标签。", "navigation_alias_canonical_v1"),
        TemplateQuestion(ALIASES, "其他叫法", "每行一个同义叫法；由用户确认，不能据此推断个人事实。", "navigation_alias_terms_v1"),
    ))
    fun supports(questions: List<TemplateQuestion>): Boolean = questions == draft.questions
    fun obtain(repository: LifeRepository): TemplateRef {
        val template = repository.listTemplates().firstOrNull { supports(it.draft.questions) } ?: repository.saveTemplate(draft)
        return TemplateRef(template.id, template.version)
    }
}
