package dev.tracelife.reports

import dev.tracelife.actions.ActionEvidenceReceipt
import dev.tracelife.analysis.AnalysisConflictException
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisStatus
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.analysis.EvidenceReceipt
import dev.tracelife.analysis.ReductionDigest
import dev.tracelife.analysis.ReductionInput
import dev.tracelife.analysis.ReductionNodeRef
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceFragment
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.util.Collections

/** App reading handle for one derived node. It is not an original-evidence or publication token. */
class PeriodDraft internal constructor(
    val runId: String,
    val taskVersion: Int,
    val source: SourceStamp,
    val scope: AnalysisQuery,
    val node: ReductionNodeRef,
    val inputs: List<ReductionInput>,
    val leafCount: Int,
    internal val evidenceIndex: List<EvidenceReceipt>,
    internal val actionEvidenceIndex: List<ActionEvidenceReceipt> = emptyList(),
) {
    val originalActionEvents: Int get() = actionEvidenceIndex.size
    val originalFragments: Int get() = evidenceIndex.size
    val originalsSemanticallyReviewed: Boolean get() = false
}

data class DraftActionEvidencePage(val items: List<ActionEvidenceReceipt>, val total: Int, val nextOffset: Int?)

data class DraftEvidencePage(val items: List<EvidenceReceipt>, val total: Int, val nextOffset: Int?)

/** Trusted local-app navigation only. Providers receive neither this service nor the repository. */
class PeriodReductionReports(private val repository: LifeRepository, private val analysis: ControlledAnalysis) {
    fun open(runId: String, node: ReductionNodeRef): PeriodDraft {
        val task = analysis.task(runId)
        val view = analysis.reductionNode(runId, node.batchKey)
        require(view.ref == node) { "归并节点身份与已保存结果不一致" }
        val scope = task.state.request.scope.copy(recordIds = task.state.request.scope.recordIds?.let {
            Collections.unmodifiableSet(it.toSet())
        })
        return PeriodDraft(runId, task.stored.version, task.stored.source, scope, node,
            view.inputs, view.leafRefs.size, Collections.unmodifiableList(view.originalEvidence.toList()),
            Collections.unmodifiableList(view.originalActionEvidence.toList())).also(::assertCurrent)
    }

    fun body(draft: PeriodDraft): ReductionDigest = current(draft) {
        val digest = analysis.currentReduction(draft.runId, draft.node)
        ReductionDigest(Collections.unmodifiableList(digest.statements.map {
            it.copy(inputIds = Collections.unmodifiableList(it.inputIds.toList()))
        }), Collections.unmodifiableList(digest.unknowns.toList()))
    }

    /** Parent nodes retain their own citations; every context source is available in this index. */
    fun evidence(draft: PeriodDraft, offset: Int = 0, limit: Int = 25): DraftEvidencePage = current(draft) {
        require(offset >= 0 && limit in 1..100) { "草稿来源分页范围无效" }
        val end = minOf(draft.evidenceIndex.size.toLong(), offset.toLong() + limit).toInt()
        val items = if (offset >= draft.evidenceIndex.size) emptyList() else draft.evidenceIndex.subList(offset, end).toList()
        DraftEvidencePage(items, draft.evidenceIndex.size, end.takeIf { offset < it && it < draft.evidenceIndex.size })
    }

    fun actionEvidence(draft: PeriodDraft, offset: Int = 0, limit: Int = 25): DraftActionEvidencePage = current(draft) {
        require(offset >= 0 && limit in 1..100)
        val end = minOf(draft.actionEvidenceIndex.size.toLong(), offset.toLong() + limit).toInt()
        val items = if (offset >= draft.actionEvidenceIndex.size) emptyList() else draft.actionEvidenceIndex.subList(offset, end).toList()
        DraftActionEvidencePage(items, draft.actionEvidenceIndex.size, end.takeIf { offset < it && it < draft.actionEvidenceIndex.size })
    }

    /** One bounded complete action leaf, retaining old plan, report and next plan semantics. */
    fun actionOriginal(draft: PeriodDraft, evidenceId: String): Map<String, Any?> = current(draft) {
        val receipt = requireNotNull(draft.actionEvidenceIndex.singleOrNull { it.id == evidenceId }) { "行动引用不在草稿来源链" }
        repository.actionEvidenceBody(receipt)
    }

    /** Offset is relative to a registered original fragment, never the whole record. */
    fun original(draft: PeriodDraft, evidenceId: String, start: Int = 0, maxCodePoints: Int = 1_024): SourceFragment = current(draft) {
        require(start >= 0 && maxCodePoints in 1..4_096) { "草稿原文分片范围无效" }
        val receipt = requireNotNull(draft.evidenceIndex.singleOrNull { it.id == evidenceId }) { "引用不在草稿的来源链中" }
        require(start < receipt.end - receipt.start) { "原文分片超出已登记引用" }
        val absoluteStart = receipt.start + start
        val count = minOf(maxCodePoints, receipt.end - absoluteStart)
        repository.analysisSourceFragment(draft.source, draft.scope, receipt.recordId, receipt.field, absoluteStart, count).also {
            if (it.revision != receipt.revision || it.totalCodePoints != receipt.totalCodePoints ||
                it.start != absoluteStart || it.end != absoluteStart + count ||
                start == 0 && count == receipt.end - receipt.start && AnalysisRunCodec.sha256(it.text) != receipt.sha256) {
                throw StaleSnapshotException("草稿原文与登记的来源版本不一致")
            }
        }
    }

    fun assertCurrent(draft: PeriodDraft) {
        analysis.currentReduction(draft.runId, draft.node)
        val task = analysis.task(draft.runId)
        if (task.stored.version != draft.taskVersion || task.stored.source != draft.source ||
            !repository.analysisSourceCurrent(draft.source, draft.scope) || task.status in setOf(AnalysisStatus.STALE, AnalysisStatus.EXPIRED)) {
            throw AnalysisConflictException("草稿任务或来源已变化，请重新打开")
        }
    }

    private fun <T> current(draft: PeriodDraft, block: () -> T): T {
        assertCurrent(draft)
        return block().also { assertCurrent(draft) }
    }
}
