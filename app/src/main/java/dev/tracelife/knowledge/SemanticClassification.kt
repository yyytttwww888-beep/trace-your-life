package dev.tracelife.knowledge

import dev.tracelife.analysis.AnalysisLimits
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.analysis.EvidenceSelection
import dev.tracelife.analysis.NavigationSuggestion
import dev.tracelife.analysis.SemanticNavigationProvider
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceFieldKind
import java.time.LocalDate

data class ClassificationSuggestion(val runId: String, val suggestion: NavigationSuggestion,
    val providedFragments: Int, val totalFragments: Int, val attachmentCount: Int) {
    val coverage: String get() = "读取文字 $providedFragments / $totalFragments 段" +
        (if (providedFragments < totalFragments) "，其余文字尚未读取" else "") +
        (if (attachmentCount > 0) "；$attachmentCount 个附件未读取" else "")
}

/** Saved source -> fixed scope -> registered leaves -> suggestions. Applying tags is an explicit ordinary record revision. */
class SemanticClassification(private val repository: LifeRepository, private val analysis: ControlledAnalysis) {
    fun suggest(recordId: String, expectedRevision: Int, provider: SemanticNavigationProvider): ClassificationSuggestion {
        val record = requireNotNull(repository.record(recordId)) { "记录已不存在" }
        require(record.revision == expectedRevision) { "记录已修改，请重新查看" }
        val occurrence = record.draft.occurrence
        val from = occurrence.startDate ?: LocalDate.of(1, 1, 1)
        val through = occurrence.endDate ?: LocalDate.of(9999, 12, 31)
        val scope = AnalysisQuery(from, through, occurrence.zoneId, includeArchived = true, recordIds = setOf(recordId))
        val task = analysis.start(AnalysisRequest("仅为这条已保存记录的实际提供片段建议主题。可能只提供长记录的一部分；不能概括未读部分或附件。",
            scope, AnalysisLimits(fragmentCodePoints = FRAGMENT_SIZE, inputTokenLimit = 32_000, maxCalls = 1), record.draft.topics))
        val fields = analysis.fields(task.stored.id, recordId)
        val text = fields.filter { it.kind == SourceFieldKind.TEXT && it.codePoints > 0 }
        val total = text.sumOf { (it.codePoints + FRAGMENT_SIZE - 1) / FRAGMENT_SIZE }
        require(total > 0) { "这条记录还没有可整理的文字" }
        val selections = text.asSequence().flatMap { field ->
            (0 until field.codePoints step FRAGMENT_SIZE).asSequence().map { EvidenceSelection(recordId, field.key, it) }
        }.take(MAX_FRAGMENTS).toList()
        val result = analysis.navigate(task.stored.id, provider, selections)
        require(repository.record(recordId)?.revision == expectedRevision) { "记录在整理期间发生变化，请重新查看" }
        return ClassificationSuggestion(task.stored.id, result, selections.size, total, fields.count { it.kind == SourceFieldKind.ATTACHMENT })
    }

    companion object {
        private const val FRAGMENT_SIZE = 400
        private const val MAX_FRAGMENTS = 16
    }
}
