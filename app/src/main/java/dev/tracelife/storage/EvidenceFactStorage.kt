package dev.tracelife.storage

import dev.tracelife.actions.ActionEvidenceProtocol
import dev.tracelife.actions.ActionEvidenceReceipt
import dev.tracelife.actions.StoredActionRevision
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.EvidenceFactPack
import dev.tracelife.analysis.EvidenceFacts
import dev.tracelife.analysis.EvidenceReceipt
import dev.tracelife.model.RecordRevision

/** Stored literal structure never substitutes for its original versioned source. */
internal object EvidenceFactStorage {
    private val actionFields = setOf("/plan/behavior", "/reviewedPlan/behavior", "/nextPlan/behavior", "/review/execution", "/review/observedText")

    fun validateBinding(pack: EvidenceFactPack, records: List<EvidenceReceipt>, actions: List<ActionEvidenceReceipt>) {
        EvidenceFacts.validatePack(pack)
        val recordRefs = records.associateBy { it.id }
        val actionRefs = actions.associateBy { it.id }
        pack.sources.forEach { source ->
            when (source.sourceType) {
                "RECORD" -> {
                    val ref = requireNotNull(recordRefs[source.evidenceId]) { "事实结构引用未提供的记录" }
                    require(source.recordId == ref.recordId && source.revision == ref.revision && source.field == ref.field &&
                        source.start == ref.start && source.end == ref.end && source.totalCodePoints == ref.totalCodePoints &&
                        source.sha256 == ref.sha256 && source.textSha256 == ref.sha256) { "事实结构与原文凭据不一致" }
                }
                "ACTION" -> {
                    val ref = requireNotNull(actionRefs[source.evidenceId]) { "事实结构引用未提供的行动" }
                    require(source.recordId == ref.actionId && source.revision == ref.revision && source.sha256 == ref.sha256 &&
                        source.start == 0 && source.end == source.totalCodePoints && source.field in actionFields) { "事实结构与行动凭据不一致" }
                }
                else -> throw IllegalArgumentException("事实来源类型无效")
            }
        }
        require(pack.sources.filter { it.sourceType == "RECORD" }.map { it.evidenceId } == records.map { it.id }) {
            "事实结构遗漏或重排了本次记录输入"
        }
        require(pack.sources.filter { it.sourceType == "ACTION" }.map { it.evidenceId }.distinct() == actions.map { it.id }) {
            "事实结构遗漏或重排了本次行动输入"
        }
    }

    fun validateRecords(pack: EvidenceFactPack, records: Map<Pair<String, Int>, RecordRevision>) {
        EvidenceFacts.validatePack(pack)
        val inputs = mutableListOf<Map<String, Any?>>()
        pack.sources.forEachIndexed { index, source ->
            if (source.sourceType != "RECORD") return@forEachIndexed
            val record = requireNotNull(records[source.recordId to source.revision]) { "事实结构缺少原始记录修订" }
            val text = if (source.field == "body") record.draft.body else {
                require(source.field.startsWith("answer:")) { "事实字段不是正文或复盘答案" }
                requireNotNull(record.draft.answers[source.field.removePrefix("answer:")]) { "事实结构引用的回答不存在" }
            }
            require(text.codePointCount(0, text.length) == source.totalCodePoints)
            val supplied = AnalysisRunCodec.slice(text, source.start, source.end)
            require(AnalysisRunCodec.sha256(supplied) == source.textSha256 && source.sha256 == source.textSha256) {
                "事实结构原文摘要不一致"
            }
            require(source.from == record.draft.occurrence.startDate?.toString() &&
                source.through == record.draft.occurrence.endDate?.toString()) { "事实结构发生日期与来源不同" }
            pack.facts.filter { it.source == index }.forEach { fact ->
                require(AnalysisRunCodec.slice(text, fact.start, fact.end) == fact.quote) { "事实引文与原文位置不同" }
            }
            inputs += AnalysisRunCodec.receiptMap(EvidenceReceipt(source.evidenceId, source.recordId, source.revision,
                source.field, source.start, source.end, source.totalCodePoints, source.sha256)) + mapOf(
                "text" to supplied, "startDate" to record.draft.occurrence.startDate?.toString(),
                "endDate" to record.draft.occurrence.endDate?.toString())
        }
        if (inputs.isNotEmpty()) validateRebuiltType(pack, "RECORD", requireNotNull(EvidenceFacts.build(mapOf("evidence" to inputs))))
    }

    fun validateActions(pack: EvidenceFactPack, receipts: List<ActionEvidenceReceipt>, revisions: List<StoredActionRevision>) {
        val refs = receipts.associateBy { it.id }
        val originals = revisions.associateBy { it.id to it.revision }
        val bodies = linkedMapOf<String, Map<String, Any?>>()
        pack.sources.forEachIndexed { index, source ->
            if (source.sourceType != "ACTION") return@forEachIndexed
            val ref = requireNotNull(refs[source.evidenceId]) { "事实结构缺少行动凭据" }
            val revision = requireNotNull(originals[ref.actionId to ref.revision]) { "事实结构缺少原始行动修订" }
            val body = ActionEvidenceProtocol.modelMap(revision, ref)
            bodies[source.evidenceId] = body
            require(source.field.startsWith("/") && source.field.length > 1)
            val text = source.field.drop(1).split('/').fold(body as Any?) { value, key ->
                (value as? Map<*, *>)?.get(key)
            } as? String ?: throw IllegalArgumentException("事实结构引用的行动字段不是文字")
            require(source.start == 0 && source.end == text.codePointCount(0, text.length) &&
                source.totalCodePoints == source.end && AnalysisRunCodec.sha256(text) == source.textSha256) {
                "事实结构行动字段摘要或位置不同"
            }
            pack.facts.filter { it.source == index }.forEach { fact ->
                require(AnalysisRunCodec.slice(text, fact.start, fact.end) == fact.quote) { "事实引文与行动原文位置不同" }
            }
        }
        if (bodies.isNotEmpty()) validateRebuiltType(pack, "ACTION", requireNotNull(EvidenceFacts.build(mapOf("actionEvidence" to bodies.values.toList()))))
    }

    private fun validateRebuiltType(pack: EvidenceFactPack, sourceType: String, rebuilt: EvidenceFactPack) {
        val indexes = pack.sources.indices.filter { pack.sources[it].sourceType == sourceType }
        val remapped = indexes.withIndex().associate { it.value to it.index }
        require(rebuilt.sources == indexes.map { pack.sources[it] }) { "事实来源与原文重建结果不同" }
        val expected = pack.facts.filter { it.source in remapped }.map { it.copy(source = remapped.getValue(it.source)) }
        require(rebuilt.facts == expected) { "事实结构与原文重建结果不同" }
    }
}
