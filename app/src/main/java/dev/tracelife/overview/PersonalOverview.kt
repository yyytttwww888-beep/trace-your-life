package dev.tracelife.overview

import dev.tracelife.actions.ActionExperiments
import dev.tracelife.actions.ActionFilter
import dev.tracelife.actions.ActionIndexItem
import dev.tracelife.actions.ActionStatus
import dev.tracelife.knowledge.ExperienceSummary
import dev.tracelife.knowledge.KnowledgeContinuity
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeFilter
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.knowledge.KnowledgeView
import dev.tracelife.model.*
import dev.tracelife.profile.PersonalProfile
import dev.tracelife.profile.ProfileEntryView
import dev.tracelife.profile.ProfileQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class PersonalDirection(
    val stage: String = "", val goals: String = "", val attention: String = "", val signals: String = "",
    val statedOn: LocalDate, val reviewOn: LocalDate? = null, val zoneId: String = ZoneId.systemDefault().id,
)
data class DirectionRecord(val record: RecordRevision, val direction: PersonalDirection, val reviewDue: Boolean)
data class OverviewSection<T>(val items: List<T>, val total: Int)
data class PersonalOverviewSnapshot(
    val source: SourceStamp, val generation: Long, val evaluatedAt: Instant,
    val direction: DirectionRecord?, val directionRecords: Int,
    val profiles: OverviewSection<ProfileEntryView>, val experiences: OverviewSection<ExperienceSummary>,
    val recent: OverviewSection<KnowledgeView>, val actions: OverviewSection<ActionIndexItem>,
    val pendingKnowledge: Int,
)

/** A fixed, versioned question schema over ordinary source records. User prose never becomes a model profile. */
object OverviewNotes {
    const val STAGE = "overview.stage"
    const val GOALS = "overview.goals"
    const val ATTENTION = "overview.attention"
    const val SIGNALS = "overview.signals"
    const val REVIEW_ON = "overview.review_on"
    val template = TemplateDraft("当前方向", Cadence.EVENT,
        "由你填写当前阶段、目标和关注点。保存为普通记录，保留修订与原文；不是自动推断的画像。",
        listOf(
            TemplateQuestion(STAGE, "当前阶段与约束", "现在承担什么角色，有哪些现实条件？", "personal_direction_stage"),
            TemplateQuestion(GOALS, "当前目标", "希望维持、改变或达成什么，为什么值得做？", "personal_direction_goals"),
            TemplateQuestion(ATTENTION, "目前关注", "有哪些需要留意的问题或想保留的事情？", "personal_direction_attention"),
            TemplateQuestion(SIGNALS, "如何观察变化", "通过什么具体迹象，判断是否接近自己的目标？", "personal_direction_signals"),
            TemplateQuestion(REVIEW_ON, "计划回看日期", "可留空；填写 YYYY-MM-DD。到期表示适合再看，不代表失败。", "personal_direction_review_on"),
        ), "Trace Life personal direction / schema 1. 用户自述；不是行为证据或人格测量。")

    fun matches(value: TemplateVersion?): Boolean = value?.draft == template

    /** A custom edit to a template does not relabel other answers as personal direction. */
    fun read(record: RecordRevision, boundTemplate: TemplateVersion?): PersonalDirection? {
        if (!matches(boundTemplate) || record.draft.template != boundTemplate?.let { TemplateRef(it.id, it.version) }) return null
        val statedOn = record.draft.occurrence.startDate ?: return null
        val answers = record.draft.answers
        return PersonalDirection(answers[STAGE].orEmpty(), answers[GOALS].orEmpty(), answers[ATTENTION].orEmpty(),
            answers[SIGNALS].orEmpty(), statedOn,
            answers[REVIEW_ON]?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            record.draft.occurrence.zoneId)
    }

    /** Archive state is not a new statement. Walk only unchanged drafts back to their actual edit. */
    fun contentChangedAt(record: RecordRevision, previous: (Int) -> RecordRevision?): Instant {
        var current = record
        while (current.revision > 1) {
            val before = requireNotNull(previous(current.revision - 1)) { "当前方向缺少历史修订，无法判断声明顺序" }
            require(before.id == record.id && before.revision == current.revision - 1)
            if (before.draft != current.draft) break
            current = before
        }
        return current.updatedAt
    }

    val order: Comparator<Pair<RecordRevision, Instant>> =
        compareByDescending<Pair<RecordRevision, Instant>> { it.second }.thenBy { it.first.id }
}

/** Local entry point only: bounded result sections, no model calls and no independent copy of source text. */
class PersonalOverview(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    fun open(limit: Int = 3): PersonalOverviewSnapshot {
        require(limit in 1..10)
        val source = repository.analysisSourceStamp(); val generation = repository.currentAnalysisGeneration()
        val at = clock.instant(); val fixed = Clock.fixed(at, clock.zone)
        var direction: DirectionRecord? = null; var directionOrder: Pair<RecordRevision, Instant>? = null
        var directionCount = 0; var offset = 0
        val templates = mutableMapOf<TemplateRef, TemplateVersion?>()
        do {
            val page = repository.listRecords(RecordFilter(includeArchived = true, limit = 100, offset = offset))
            page.items.forEach { record ->
                val ref = record.draft.template ?: return@forEach
                if (!templates.containsKey(ref)) templates[ref] = repository.template(ref.id, ref.version)
                val value = OverviewNotes.read(record, templates[ref]) ?: return@forEach
                directionCount++
                val today = at.atZone(ZoneId.of(value.zoneId)).toLocalDate()
                val order = record to OverviewNotes.contentChangedAt(record) { repository.record(record.id, it) }
                if (record.updatedAt <= at && value.statedOn <= today &&
                    (directionOrder == null || OverviewNotes.order.compare(order, directionOrder!!) < 0)) {
                    direction = DirectionRecord(record, value, value.reviewOn?.let { it <= today } == true)
                    directionOrder = order
                }
            }
            offset = page.nextOffset ?: -1
        } while (offset >= 0)
        // Archiving the latest statement does not silently re-adopt an older goal.
        if (direction?.record?.archived == true) direction = null

        val profile = PersonalProfile(repository, fixed)
        val profileSnapshot = profile.open(ProfileQuery(zoneId = clock.zone.id))
        val profilePage = profile.entries(profileSnapshot, limit = limit)
        val profileItems = profilePage.items.map { profile.entry(profileSnapshot, it.id) }
        val library = KnowledgeLibrary(repository, fixed)
        val recent = mutableListOf<KnowledgeView>(); var recentCount = 0; var pending = 0
        library.scanCurrent(KnowledgeFilter(includeInvalid = true)) { view ->
            if (view.evidenceState != KnowledgeEvidenceState.CURRENT) pending++
            else if (view.value.publishedAt <= at && view.value.publishedAt >= at.minusSeconds(30L * 86_400)) {
                recentCount++
                recent += view
                recent.sortWith(compareByDescending<KnowledgeView> { it.value.publishedAt }.thenBy { it.value.id })
                if (recent.size > limit) recent.removeAt(recent.lastIndex)
            }
        }
        val continuity = KnowledgeContinuity(repository, fixed)
        val experienceItems = mutableListOf<ExperienceSummary>(); var experienceCount = 0; offset = 0
        do {
            val page = continuity.page(offset = offset, limit = 50)
            page.items.forEach { item ->
                val current = item.current.knowledge
                if (current?.evidenceState == KnowledgeEvidenceState.CURRENT && current.isCurrentRevision) {
                    experienceCount++
                    if (experienceItems.size < limit) experienceItems += item
                }
            }
            offset = page.nextOffset ?: -1
        } while (offset >= 0)
        val actions = ActionExperiments(repository, fixed).list(ActionFilter(status = ActionStatus.OPEN), limit)
        if (repository.analysisSourceStamp() != source || repository.currentAnalysisGeneration() != generation)
            throw StaleSnapshotException("个人概况在读取时有变化，请重新打开")
        return PersonalOverviewSnapshot(source, generation, at, direction, directionCount,
            OverviewSection(profileItems, profilePage.total), OverviewSection(experienceItems, experienceCount),
            OverviewSection(recent, recentCount), OverviewSection(actions.items, actions.total), pending)
    }

    /** Caller keeps creationId while retrying; edits use the ordinary record revision check. */
    fun save(value: PersonalDirection, recordId: String? = null, expectedRevision: Int? = null,
        creationId: String = UUID.randomUUID().toString()): RecordRevision {
        require(value.statedOn.year in 1..9999 && (value.reviewOn == null || value.reviewOn.year in 1..9999))
        require(value.reviewOn == null || value.reviewOn >= value.statedOn) { "回看日期不能早于声明日期" }
        require(listOf(value.stage, value.goals, value.attention, value.signals).any { it.isNotBlank() }) { "至少留下一个目标、关注点或阶段说明" }
        require(listOf(value.stage, value.goals, value.attention, value.signals).all { it.length <= 2_000 }) { "每项最多 2000 字" }
        ZoneId.of(value.zoneId)
        require(value.statedOn <= clock.instant().atZone(ZoneId.of(value.zoneId)).toLocalDate()) { "声明日期不能晚于今天；未来计划请写在目标中" }
        val previous = recordId?.let { requireNotNull(repository.record(it)) { "原记录已删除" } }
        val bound = if (previous != null) {
            require(expectedRevision != null && !previous.archived) { "请重新打开当前方向后修改" }
            val ref = requireNotNull(previous.draft.template)
            requireNotNull(repository.template(ref.id, ref.version)).also { require(OverviewNotes.matches(it)) { "这不是当前方向记录" } }
        } else {
            require(expectedRevision == null)
            repository.listTemplates(includeArchived = true).firstOrNull { !it.archived && OverviewNotes.matches(it) }
                ?: repository.saveTemplate(OverviewNotes.template)
        }
        val answers = mapOf(OverviewNotes.STAGE to value.stage, OverviewNotes.GOALS to value.goals,
            OverviewNotes.ATTENTION to value.attention, OverviewNotes.SIGNALS to value.signals,
            OverviewNotes.REVIEW_ON to value.reviewOn?.toString().orEmpty()).filterValues { it.isNotBlank() }
        val draft = RecordDraft(previous?.draft?.body ?: "当前方向（个人自述）", Occurrence(value.statedOn, zoneId = value.zoneId),
            previous?.draft?.topics ?: setOf("个人方向"), TemplateRef(bound.id, bound.version), answers,
            previous?.draft?.attachmentIds.orEmpty())
        return if (recordId == null) repository.createRecord(draft, creationId)
            else repository.updateRecord(recordId, requireNotNull(expectedRevision), draft)
    }
}
