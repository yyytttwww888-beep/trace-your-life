package dev.tracelife.transfer

import dev.tracelife.conversation.ConversationTurn
import dev.tracelife.conversation.ConversationValidation

/** Questions are routing history, never original evidence or a copy of an answer. */
internal object ConversationExport {
    fun select(snapshot: RepositorySnapshot, options: ReadingExportOptions): List<ConversationTurn> {
        ConversationValidation.validateCollection(snapshot.conversations, snapshot.conversationTurns, snapshot.deletedConversations)
        if (options.kind == ReadingPackageKind.EVIDENCE) return emptyList()
        val scope = options.scope
        return snapshot.conversationTurns.filter { turn ->
            (scope.from == null || turn.scope.from >= scope.from) &&
                (scope.through == null || turn.scope.through <= scope.through) &&
                (scope.topics == null || turn.focusTopics.any { it in scope.topics })
        }.sortedWith(compareBy({ it.threadId }, { it.position }))
    }
}
