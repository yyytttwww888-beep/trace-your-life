package dev.tracelife.transfer

import dev.tracelife.knowledge.ExperienceRelation
import dev.tracelife.knowledge.ExperienceValidation
import dev.tracelife.knowledge.StoredExperienceChange
import dev.tracelife.knowledge.StoredExperienceThread

/** A scoped reading projection. Missing steps never become an implicit change of understanding. */
internal object ExperienceExport {
    data class Entry(
        val thread: StoredExperienceThread,
        val changes: List<StoredExperienceChange>,
        val totalChanges: Int,
        val adoptedSequence: Int,
    ) {
        val adoptedIncluded get() = changes.any { it.sequence == adoptedSequence }
    }

    data class Selection(val entries: List<Entry>, val sourceThreads: Int, val sourceChanges: Int) {
        val includedChanges get() = entries.sumOf { it.changes.size }
    }

    fun select(snapshot: RepositorySnapshot, options: ReadingExportOptions,
        knowledge: PublishedKnowledgeExport.Selection): Selection {
        ExperienceValidation.validateCollection(snapshot.experienceThreads, snapshot.experienceChanges,
            snapshot.deletedExperienceThreads)
        val refs = knowledge.entries.map { it.value.id to it.value.revision }.toSet()
        val byThread = snapshot.experienceChanges.groupBy { it.threadId }
        val entries = if (options.kind == ReadingPackageKind.EVIDENCE) emptyList() else
            snapshot.experienceThreads.sortedBy { it.id }.mapNotNull { thread ->
                if (thread.updatedAt > snapshot.capturedAt) return@mapNotNull null
                // Thread topics are independent user organization and also honor an explicit topic scope.
                if (options.scope.topics != null && thread.topics.none { it in options.scope.topics }) return@mapNotNull null
                val all = byThread.getValue(thread.id).sortedBy { it.sequence }
                val included = all.filter { (it.knowledgeId to it.knowledgeRevision) in refs }
                if (included.isEmpty()) return@mapNotNull null
                val adopted = all.last { it.relation in setOf(ExperienceRelation.INITIAL, ExperienceRelation.NARROW, ExperienceRelation.REPLACE) }
                Entry(thread, included, all.size, adopted.sequence)
            }
        return Selection(entries, snapshot.experienceThreads.size, snapshot.experienceChanges.size)
    }
}
