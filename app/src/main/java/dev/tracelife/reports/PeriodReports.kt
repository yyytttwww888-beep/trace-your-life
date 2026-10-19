package dev.tracelife.reports

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeFilter
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.knowledge.KnowledgeView
import dev.tracelife.metrics.RecordMetrics
import dev.tracelife.metrics.RecordMetricsReport
import dev.tracelife.model.Occurrence
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.model.TemplateRef
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceDescriptor
import dev.tracelife.storage.SourceFieldKind
import dev.tracelife.storage.SourceFragment
import dev.tracelife.storage.SourcePage
import dev.tracelife.storage.SourceStamp
import dev.tracelife.storage.SourceTimeClass
import dev.tracelife.storage.UnreadableSourceException
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.util.Collections

enum class ReportSection { EXPECTATION, ACTUAL, FACTORS, LEARNING, ACTION, FOLLOW_UP, STATE, OTHER }
enum class ReportSynthesisStatus { NOT_GENERATED }

/** Navigation only. A section groups question intent; it does not interpret or confirm the answer. */
data class ReportAnswerRef(
    val recordId: String,
    val revision: Int,
    val field: String,
    val codePoints: Int,
    val question: TemplateQuestion,
    val template: TemplateRef,
    val occurrence: Occurrence,
    val timeClass: SourceTimeClass,
    val section: ReportSection,
)

data class ReportAnswerPage(val items: List<ReportAnswerRef>, val total: Int, val nextOffset: Int?)
data class ReportKnowledgeItem(val view: KnowledgeView, val evidenceTimeClasses: Set<SourceTimeClass>)
data class ReportKnowledgePage(val items: List<ReportKnowledgeItem>, val total: Int, val nextOffset: Int?)

/** A rebuildable, bounded metadata handle, not a saved AI synthesis or a completed review. */
class PeriodReport internal constructor(
    val metrics: RecordMetricsReport,
    val analysisGeneration: Long,
    val evaluatedAt: Instant,
    val validUntil: Instant?,
    val sectionCounts: Map<ReportSection, Int>,
    val knowledgeCount: Int,
    val omittedInvalidKnowledge: Int,
    val omittedOutOfScopeKnowledge: Int,
    val unreadAttachments: Int,
    val unreadableRecords: Int,
    internal val recordIndex: List<SourceDescriptor>,
    internal val answerIndex: List<ReportAnswerRef>,
    internal val knowledgeIndex: List<KnowledgeRef>,
) {
    val source: SourceStamp get() = metrics.source
    val scope: AnalysisQuery get() = metrics.scope
    val synthesisStatus: ReportSynthesisStatus get() = ReportSynthesisStatus.NOT_GENERATED
}

internal data class KnowledgeRef(val id: String, val revision: Int, val evidenceTimeClasses: Set<SourceTimeClass>)

/** Trusted app navigation, never an unbounded model-reading tool. Call off the main thread. */
class PeriodReports(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    private val metrics = RecordMetrics(repository, clock)
    private val knowledge = KnowledgeLibrary(repository, clock)

    fun open(scope: AnalysisQuery): PeriodReport {
        val fixedScope = scope.copy(recordIds = scope.recordIds?.let { Collections.unmodifiableSet(it.toSet()) })
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val evaluatedAt = clock.instant()
        val evaluatedKnowledge = KnowledgeLibrary(repository, Clock.fixed(evaluatedAt, clock.zone))
        // Validate the scope and capacity before calculating or retaining the candidate inventory.
        val first = repository.analysisSourcePage(source, fixedScope, 0, 100)
        require(first.total <= MAX_RECORDS) { "周期报告最多包含 $MAX_RECORDS 条候选记录，请缩小范围" }
        val calculated = metrics.calculate(fixedScope)
        if (calculated.source != source) throw StaleSnapshotException("报告来源在计算期间变化")
        val records = mutableListOf<SourceDescriptor>()
        var page = first
        while (true) {
            records += page.items
            page = repository.analysisSourcePage(source, fixedScope, page.nextOffset ?: break, 100)
        }

        val answers = mutableListOf<ReportAnswerRef>()
        var unreadAttachments = 0
        var unreadableRecords = 0
        records.forEach { record ->
            val fields = try {
                repository.analysisSourceFields(source, fixedScope, record.id)
            } catch (_: UnreadableSourceException) {
                unreadableRecords++
                // The field inventory is unavailable; its attachment count is also unknown.
                return@forEach
            }
            unreadAttachments += fields.count { it.kind == SourceFieldKind.ATTACHMENT }
            val answerFields = fields.filter { it.kind == SourceFieldKind.TEXT && it.key.startsWith("answer:") }
            if (answerFields.isEmpty()) return@forEach
            val template = requireNotNull(repository.analysisSourceTemplate(source, fixedScope, record.id))
            val questions = repository.analysisSourceQuestions(source, fixedScope, record.id)
            answerFields.forEach { field ->
                require(answers.size < MAX_ANSWERS) { "周期报告的答案目录超过 $MAX_ANSWERS 项，请缩小范围" }
                val question = requireNotNull(questions[field.key]) { "答案缺少所绑定版本的问题" }
                answers += ReportAnswerRef(record.id, record.revision, field.key, field.codePoints, question,
                    template, record.occurrence, record.timeClass, section(question.intent))
            }
        }

        val sources = records.associateBy { it.id }
        val included = mutableListOf<KnowledgeRef>()
        var invalid = 0
        var outside = 0
        var validUntil: Instant? = null
        var candidates = 0
        // The library shares decoded task state across this one consistent scan.
        evaluatedKnowledge.scanCurrent(KnowledgeFilter(from = fixedScope.from, through = fixedScope.through,
            includeInvalid = true)) { view ->
            require(++candidates <= MAX_KNOWLEDGE) { "周期报告的知识目录过大，请缩小范围" }
            if (!view.isCurrentRevision || view.evidenceState != KnowledgeEvidenceState.CURRENT) {
                invalid++
                return@scanCurrent
            }
            val evidenceSources = KnowledgeValidation.allEvidence(view.value).map { sources[it.recordId]?.takeIf { record -> record.revision == it.revision } }
            if (evidenceSources.any { it == null }) {
                outside++
                return@scanCurrent
            }
            included += KnowledgeRef(view.value.id, view.value.revision, evidenceSources.map { requireNotNull(it).timeClass }.toSet())
            view.value.expiresAt?.let { expiry -> if (validUntil?.let { expiry < it } != false) validUntil = expiry }
        }

        return PeriodReport(calculated, generation, evaluatedAt, validUntil,
            ReportSection.entries.associateWith { selected -> answers.count { it.section == selected } },
            included.size, invalid, outside, unreadAttachments, unreadableRecords,
            records.toList(), answers.toList(), included.toList()).also(::assertCurrent)
    }

    /** Also checks wall-clock expiry: a report can become invalid without a database write. */
    fun assertCurrent(report: PeriodReport) {
        if (repository.analysisSourceStamp() != report.source ||
            repository.currentAnalysisGeneration() != report.analysisGeneration ||
            report.validUntil?.let { !clock.instant().isBefore(it) } == true) {
            throw StaleSnapshotException("周期报告的来源、知识或有效期已变化，请重新生成")
        }
    }

    fun records(report: PeriodReport, offset: Int = 0, limit: Int = 25): SourcePage = current(report) {
        val items = slice(report.recordIndex, offset, limit)
        SourcePage(items, report.metrics.totalRecords, report.metrics.unknownTimeRecords,
            report.metrics.overlappingRecords, next(offset, items.size, report.recordIndex.size))
    }

    fun answers(report: PeriodReport, section: ReportSection? = null, offset: Int = 0, limit: Int = 25): ReportAnswerPage = current(report) {
        val selected = if (section == null) report.answerIndex else report.answerIndex.filter { it.section == section }
        val items = slice(selected, offset, limit)
        ReportAnswerPage(items, selected.size, next(offset, items.size, selected.size))
    }

    fun answer(report: PeriodReport, ref: ReportAnswerRef, start: Int = 0, maxCodePoints: Int = 1_024): SourceFragment = current(report) {
        require(ref in report.answerIndex) { "答案不在本次报告目录内" }
        repository.analysisSourceFragment(report.source, report.scope, ref.recordId, ref.field, start, maxCodePoints).also {
            if (it.revision != ref.revision || it.totalCodePoints != ref.codePoints)
                throw StaleSnapshotException("答案修订与报告不一致")
        }
    }

    fun knowledge(report: PeriodReport, offset: Int = 0, limit: Int = 25): ReportKnowledgePage = current(report) {
        val items = slice(report.knowledgeIndex, offset, limit).map { ref ->
            val view = knowledge.get(ref.id, ref.revision)
            if (view == null || !view.isCurrentRevision || view.evidenceState != KnowledgeEvidenceState.CURRENT)
                throw StaleSnapshotException("报告知识已失效，请重新生成")
            ReportKnowledgeItem(view, ref.evidenceTimeClasses)
        }
        ReportKnowledgePage(items, report.knowledgeIndex.size, next(offset, items.size, report.knowledgeIndex.size))
    }

    /** [start] is relative to this registered citation, never a way to expand it to the whole field. */
    fun evidence(report: PeriodReport, knowledgeId: String, evidenceId: String, start: Int = 0,
        maxCodePoints: Int = 1_024): SourceFragment = current(report) {
        require(start >= 0 && maxCodePoints in 1..4_096) { "证据分片范围无效" }
        val ref = requireNotNull(report.knowledgeIndex.singleOrNull { it.id == knowledgeId }) { "知识不在本次报告目录内" }
        val view = knowledge.get(ref.id, ref.revision)
        if (view == null || !view.isCurrentRevision || view.evidenceState != KnowledgeEvidenceState.CURRENT)
            throw StaleSnapshotException("报告知识已失效，请重新生成")
        val receipt = requireNotNull(KnowledgeValidation.allEvidence(view.value).singleOrNull { it.id == evidenceId }) { "证据不属于本次知识核对" }
        require(start < receipt.end - receipt.start) { "证据分片超出引用范围" }
        val absoluteStart = receipt.start + start
        val count = minOf(maxCodePoints, receipt.end - absoluteStart)
        repository.analysisSourceFragment(report.source, report.scope, receipt.recordId, receipt.field,
            absoluteStart, count).also { fragment ->
            if (fragment.revision != receipt.revision || fragment.totalCodePoints != receipt.totalCodePoints ||
                fragment.start != absoluteStart || fragment.end != absoluteStart + count ||
                start == 0 && count == receipt.end - receipt.start && AnalysisRunCodec.sha256(fragment.text) != receipt.sha256) {
                throw StaleSnapshotException("知识证据与报告来源不一致")
            }
        }
    }

    private fun <T> current(report: PeriodReport, block: () -> T): T {
        assertCurrent(report)
        return block().also { assertCurrent(report) }
    }

    private fun <T> slice(values: List<T>, offset: Int, limit: Int): List<T> {
        require(offset >= 0 && limit in 1..100) { "报告分页范围无效" }
        if (offset >= values.size) return emptyList()
        return values.subList(offset, minOf(values.size.toLong(), offset.toLong() + limit).toInt()).toList()
    }

    private fun next(offset: Int, size: Int, total: Int): Int? = (offset.toLong() + size).takeIf { it < total }?.toInt()

    private fun section(intent: String): ReportSection = when (intent) {
        "expectation", "direction_and_alignment" -> ReportSection.EXPECTATION
        "actual_and_evidence", "event_timeline" -> ReportSection.ACTUAL
        "contributing_factors", "effective_factors", "preparation_and_context", "contextual_patterns" -> ReportSection.FACTORS
        "reusable_learning", "learning_application" -> ReportSection.LEARNING
        "next_experiment" -> ReportSection.ACTION
        "action_follow_up" -> ReportSection.FOLLOW_UP
        "personal_state" -> ReportSection.STATE
        else -> ReportSection.OTHER
    }

    companion object {
        private const val MAX_RECORDS = 10_000
        private const val MAX_ANSWERS = 20_000
        private const val MAX_KNOWLEDGE = 10_000
    }
}
