package dev.tracelife.profile

import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.SourceDescriptor
import java.time.LocalDate
import java.util.Collections

/** Program checks establish temporal provenance, never psychological validity or event independence. */
internal object LongitudinalProfileProtocol {
    fun targetMap(target: ProfileAnalysisTarget): Map<String, Any?> = mapOf(
        "dimension" to target.dimension.name, "layer" to target.layer.name,
    ) + if (target.periods.isEmpty()) emptyMap() else mapOf("periods" to target.periods.map {
        mapOf("from" to it.from.toString(), "through" to it.through.toString())
    })

    fun parseTarget(raw: Any?, allowLongTerm: Boolean = true): ProfileAnalysisTarget {
        val value = raw as? Map<*, *> ?: error("画像目标需要对象")
        val layer = ProfileLayer.valueOf(value["layer"] as? String ?: error("画像层次缺失"))
        require(allowLongTerm || layer != ProfileLayer.LONG_TERM) { "旧版分析不支持纵向画像计划" }
        require(value.keys == if (layer == ProfileLayer.LONG_TERM) setOf("dimension", "layer", "periods") else setOf("dimension", "layer"))
        val periods = if (layer != ProfileLayer.LONG_TERM) emptyList() else {
            val entries = value["periods"] as? List<*> ?: error("比较周期需要列表")
            require(entries.size in 2..12)
            entries.map { rawPeriod ->
                val period = rawPeriod as? Map<*, *> ?: error("周期需要对象")
                require(period.keys == setOf("from", "through"))
                ProfileComparisonPeriod(LocalDate.parse(period["from"] as? String ?: error("缺少周期开始")),
                    LocalDate.parse(period["through"] as? String ?: error("缺少周期结束")))
            }
        }
        return ProfileAnalysisTarget(ProfileDimension.valueOf(value["dimension"] as? String ?: error("画像维度缺失")),
            layer, Collections.unmodifiableList(ArrayList(periods)))
    }

    fun validateScope(target: ProfileAnalysisTarget, scope: AnalysisQuery) {
        if (target.layer != ProfileLayer.LONG_TERM) return
        require(target.periods.first().from == scope.from && target.periods.last().through == scope.through) {
            "纵向比较周期须完整覆盖任务日期范围"
        }
    }

    fun periodFor(target: ProfileAnalysisTarget, occurrence: Occurrence): Int? {
        val from = occurrence.startDate ?: return null
        val through = occurrence.endDate ?: return null
        return target.periods.indexOfFirst { from >= it.from && through <= it.through }.takeIf { it >= 0 }
    }

    /** Keeps only selected metadata; unrelated bodies and the full source directory never enter a model request. */
    fun sources(analysis: ControlledAnalysis, runId: String, recordIds: Set<String>): List<SourceDescriptor> {
        val selected = mutableListOf<SourceDescriptor>()
        var offset: Int? = 0
        while (offset != null && selected.size < recordIds.size) {
            val page = analysis.evidencePage(runId, offset).page
            selected += page.items.filter { it.id in recordIds }
            offset = page.nextOffset
        }
        require(selected.map { it.id }.toSet() == recordIds) { "纵向引用来源已不可用" }
        analysis.route(runId)
        return selected
    }

    fun validateInputs(target: ProfileAnalysisTarget, receipts: List<EvidenceReceipt>, sources: List<SourceDescriptor>) {
        if (target.layer != ProfileLayer.LONG_TERM) return
        require(representedPeriods(target, receipts, sources).size >= 2) { "纵向综合须同时读取至少两个明确日期周期的原文" }
    }

    fun validateProposal(target: ProfileAnalysisTarget, proposal: BatchProposal,
        receipts: List<EvidenceReceipt>, sources: List<SourceDescriptor>) {
        if (target.layer != ProfileLayer.LONG_TERM) return
        proposal.claims.forEach { claim ->
            require(claim.kind == ClaimKind.POSSIBLE_INTERPRETATION)
            require(representedPeriods(target, receipts.filter { it.id in claim.evidenceIds }, sources).size >= 2) {
                "纵向候选不能把单周期、未知日期或跨期资料当作多个周期支持"
            }
        }
    }

    fun validateReview(target: ProfileAnalysisTarget, review: ClaimReview,
        receipts: List<EvidenceReceipt>, sources: List<SourceDescriptor>) {
        if (target.layer != ProfileLayer.LONG_TERM || review.verdict != ReviewVerdict.SUPPORTED) return
        require(representedPeriods(target, receipts.filter { it.id in review.supportingEvidenceIds }, sources).size >= 2) {
            "纵向支持意见需要至少两个明确日期周期的原文"
        }
    }

    private fun representedPeriods(target: ProfileAnalysisTarget, receipts: List<EvidenceReceipt>,
        sources: List<SourceDescriptor>): Set<Int> {
        val byIdentity = sources.associateBy { it.id to it.revision }
        return receipts.mapNotNull { receipt ->
            val source = requireNotNull(byIdentity[receipt.recordId to receipt.revision]) { "纵向引用修订与目录不符" }
            periodFor(target, source.occurrence)
        }.toSet()
    }
}
