package dev.tracelife.analysis

import dev.tracelife.storage.SourceFieldKind
import dev.tracelife.storage.UnreadableSourceException

/** Metadata-only inventory, shared by extraction and synthesis with separate receipt ledgers. */
internal class TextAnalysisInventory(private val analysis: ControlledAnalysis) {
    internal data class TextInventory(val coverage: AnalysisProcessingCoverage, val selections: List<EvidenceSelection>)
    private data class Span(val start: Int, val end: Int)

    /** Keep only one metadata page, bounded receipt ranges and at most maxBatches selections. */
    fun build(task: AnalysisTask, purpose: CallPurpose, candidateLimit: Int,
        completedEvidence: List<EvidenceReceipt>? = null): TextInventory {
        require(purpose in setOf(CallPurpose.EXTRACTION, CallPurpose.SYNTHESIS, CallPurpose.PROFILE_SCAN) && candidateLimit in 0..100)
        // Maintenance scans belong to one exact claim and plan. The caller must supply only the
        // validated context's receipts; scans for another claim never satisfy this coverage.
        require(purpose != CallPurpose.PROFILE_SCAN || completedEvidence != null)
        require(purpose == CallPurpose.PROFILE_SCAN || completedEvidence == null)
        val runId = task.stored.id
        val route = analysis.route(runId)
        val fragmentSize = task.state.request.limits.fragmentCodePoints
        val successful = (completedEvidence ?: task.state.calls.filter { it.status == CallStatus.SUCCEEDED && it.purpose == purpose }.flatMap { it.evidence })
            .groupBy { Triple(it.recordId, it.revision, it.field) }
        val selections = mutableListOf<EvidenceSelection>()
        var textLength = 0L
        var completedLength = 0L
        var remaining = 0L
        var attachments = 0
        var unreadable = 0
        var offset: Int? = 0
        while (offset != null) {
            val page = analysis.evidencePage(runId, offset).page
            for (record in page.items) {
                val fields = try {
                    analysis.fields(runId, record.id)
                } catch (_: UnreadableSourceException) {
                    unreadable++
                    continue
                }
                for (field in fields) {
                    if (field.kind == SourceFieldKind.ATTACHMENT) { attachments++; continue }
                    val ranges = merged(successful[Triple(record.id, record.revision, field.key)].orEmpty())
                    textLength += field.codePoints
                    completedLength += ranges.sumOf { (it.end - it.start).toLong() }
                    val wholeFragments = field.codePoints / fragmentSize
                    val tail = field.codePoints % fragmentSize != 0
                    var coveredFragments = 0L
                    for (span in ranges) {
                        val firstWhole = (span.start.toLong() + fragmentSize - 1) / fragmentSize
                        val afterLastWhole = minOf((span.end / fragmentSize).toLong(), wholeFragments.toLong())
                        coveredFragments += maxOf(0, afterLastWhole - firstWhole)
                        if (tail && span.start <= wholeFragments * fragmentSize && span.end == field.codePoints) coveredFragments++
                    }
                    remaining += wholeFragments.toLong() + if (tail) 1 else 0
                    remaining -= coveredFragments
                    var start = 0
                    while (start < field.codePoints && selections.size < candidateLimit) {
                        val end = minOf(start.toLong() + fragmentSize, field.codePoints.toLong()).toInt()
                        val covered = ranges.firstOrNull { it.start <= start && it.end >= end }
                        if (covered == null) {
                            selections += EvidenceSelection(record.id, field.key, start)
                            start = end
                        } else {
                            // Skip whole covered runs without enumerating millions of tiny fragments.
                            start = if (covered.end == field.codePoints) field.codePoints else covered.end / fragmentSize * fragmentSize
                        }
                    }
                }
            }
            offset = page.nextOffset
        }
        // Recheck the same source/expiry after the metadata scan, including an empty range.
        analysis.route(runId)
        return TextInventory(AnalysisProcessingCoverage(route.records, textLength, completedLength, remaining,
            attachments, unreadable, route.unknownTimeRecords, route.overlappingRecords, route.missingExactIds.toSet()), selections.toList())
    }

    private fun merged(receipts: List<EvidenceReceipt>): List<Span> {
        val result = mutableListOf<Span>()
        for (receipt in receipts.sortedBy { it.start }) {
            val previous = result.lastOrNull()
            if (previous == null || receipt.start > previous.end) result += Span(receipt.start, receipt.end)
            else result[result.lastIndex] = previous.copy(end = maxOf(previous.end, receipt.end))
        }
        return result
    }

    companion object {
        fun coveredCodePoints(receipts: List<EvidenceReceipt>): Long = receipts
            .groupBy { Triple(it.recordId, it.revision, it.field) }.values.sumOf { ranges ->
                var end = 0
                var count = 0L
                ranges.sortedBy { it.start }.forEach {
                    count += maxOf(0, it.end - maxOf(end, it.start))
                    end = maxOf(end, it.end)
                }
                count
            }
    }
}
