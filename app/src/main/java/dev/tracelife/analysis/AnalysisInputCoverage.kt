package dev.tracelife.analysis

import dev.tracelife.storage.SourceFieldKind

/** Facts about this request's actual text, never a semantic review or proof of life completeness. */
internal data class AnalysisInputCoverage(
    val providedFieldsComplete: Boolean,
    val providedRecordsTextComplete: Boolean,
    val allScopeTextProvided: Boolean,
    val scopeAttachments: Int?,
    val unreadableRecords: Int,
) {
    fun map(): Map<String, Any?> = mapOf(
        "providedFieldsComplete" to providedFieldsComplete,
        "providedRecordsTextComplete" to providedRecordsTextComplete,
        "allScopeTextProvided" to allScopeTextProvided,
        "scopeAttachments" to scopeAttachments,
        "attachmentsRead" to 0,
        "unreadableRecords" to unreadableRecords,
        "semanticallyVerified" to false,
    )

    companion object {
        /** Keep only one bounded metadata page and this call's at most 32 registered ranges. */
        fun measure(analysis: ControlledAnalysis, task: AnalysisTask, receipts: List<EvidenceReceipt>,
            route: AnalysisRoute): AnalysisInputCoverage {
            require(receipts.size <= 32 && route.runId == task.stored.id)
            val byField = receipts.groupBy { Triple(it.recordId, it.revision, it.field) }
            val complete = byField.mapValues { (_, values) ->
                val total = values.first().totalCodePoints
                require(values.all { it.totalCodePoints == total && it.start >= 0 && it.end in (it.start + 1)..total })
                var end = 0
                var contiguous = true
                values.sortedBy { it.start }.forEach { range ->
                    if (range.start > end) contiguous = false
                    end = maxOf(end, range.end)
                }
                contiguous && end == total
            }
            val providedRecords = receipts.map { it.recordId }.toSet()
            var providedRecordsComplete = true
            var allScopeComplete = route.missingExactIds.isEmpty()
            var attachments = 0
            var unreadable = 0
            var enumerated = 0
            var offset: Int? = 0
            val seenProvided = mutableSetOf<String>()
            while (offset != null) {
                val metadata = analysis.fieldMetadataPage(task.stored.id, offset, task.stored.version)
                require(metadata.page.total == route.records) { "文字覆盖范围已变化" }
                metadata.page.items.forEach { record ->
                    enumerated++
                    if (record.id in providedRecords) seenProvided += record.id
                    if (record.id in metadata.unreadableRecordIds) {
                        unreadable++
                        allScopeComplete = false
                        if (record.id in providedRecords) providedRecordsComplete = false
                    } else {
                        val fields = metadata.fields.getValue(record.id)
                        attachments += fields.count { it.kind == SourceFieldKind.ATTACHMENT }
                        val fullText = fields.filter { it.kind == SourceFieldKind.TEXT }.all { field ->
                            val key = Triple(record.id, record.revision, field.key)
                            if (key in byField) require(byField.getValue(key).all { it.totalCodePoints == field.codePoints })
                            field.codePoints == 0 || complete[key] == true
                        }
                        if (!fullText) {
                            allScopeComplete = false
                            if (record.id in providedRecords) providedRecordsComplete = false
                        }
                    }
                }
                offset = metadata.page.nextOffset
            }
            require(enumerated == route.records && seenProvided == providedRecords) { "文字覆盖与已登记原文不一致" }
            require(analysis.route(task.stored.id) == route) { "文字覆盖范围在读取期间变化" }
            return AnalysisInputCoverage(complete.values.all { it }, providedRecordsComplete, allScopeComplete,
                attachments.takeIf { unreadable == 0 }, unreadable)
        }
    }
}
