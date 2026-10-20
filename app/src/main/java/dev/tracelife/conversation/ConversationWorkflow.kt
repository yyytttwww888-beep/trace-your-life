package dev.tracelife.conversation

import dev.tracelife.analysis.AnalysisLimits
import dev.tracelife.analysis.AnalysisTaskUnavailableException
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.analysis.ConversationContext
import dev.tracelife.analysis.ConversationContextCatalog
import dev.tracelife.analysis.ReflectionRun
import dev.tracelife.analysis.ReflectionWorkflow
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ConversationPlan(
    val id: String,
    val threadId: String,
    val question: String,
    val scope: AnalysisQuery,
    val focusTopics: Set<String>,
    val intent: ConversationIntent,
    val label: String,
    val clarification: String?,
    val parentTurnId: String?,
    val parentFindingIndex: Int?,
    val context: ConversationContext?,
    val expectedLastTurnId: String?,
) {
    val canStart: Boolean get() = clarification == null
}

/** Coordinates user intent, durable questions and evidence-bearing answers, without a chat dump.
 * Preparing/submitting/reading never calls a provider. Execution remains an explicit reflection pass. */
class ConversationWorkflow(
    private val repository: LifeRepository,
    private val analysis: ControlledAnalysis,
    private val reflection: ReflectionWorkflow,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val planner = ConversationQuestionPlanner()

    fun prepare(question: String, threadId: String? = null, parentTurnId: String? = null,
        selectedFindingIndex: Int? = null, scopeOverride: AnalysisQuery? = null): ConversationPlan {
        val trimmed = question.trim()
        val thread = threadId?.let { requireNotNull(repository.conversation(it)) { "这段对话已不存在，请重新提问" } }
        val latest = thread?.let { repository.latestConversationTurn(it.id) }
        val requestedParent = parentTurnId?.let { requireNotNull(repository.conversationTurn(it)) { "追问的问题已不存在" } }
        require(requestedParent == null || requestedParent.threadId == thread?.id) { "追问必须来自同一段对话" }
        val candidate = requestedParent ?: latest
        val zone = scopeOverride?.zoneId ?: candidate?.scope?.zoneId ?: clock.zone.id
        val today = LocalDate.now(clock.withZone(ZoneId.of(zone)))
        val routed = planner.prepare(trimmed, today, zone, candidate?.scope, scopeOverride,
            forcePrevious = requestedParent != null || selectedFindingIndex != null)
        require(routed.scope.through <= today) { "只能读取已发生日期的记录；未来做法可在问题中提出" }
        val parent = candidate.takeIf { requestedParent != null || selectedFindingIndex != null || routed.refersToPrevious }
        var issue = routed.clarification
        var chosenIndex: Int? = selectedFindingIndex
        var context: ConversationContext? = null
        if (routed.refersToPrevious && parent == null) issue = "还没有可追问的回答，请写出完整问题。"
        if (parent != null) {
            val previous = inspect(parent)
            if (previous == null || !previous.canReviewFindings || previous.findings.isEmpty())
                issue = "上一轮回答尚未完成或已待更新，请先完成分析，或写出完整问题重新开始。"
            else {
                if (chosenIndex == null && routed.ordinal != null) {
                    // The page numbers all findings together. Never silently renumber a category.
                    chosenIndex = previous.findings.indices.elementAtOrNull(routed.ordinal - 1)
                    if (chosenIndex == null) issue = "无法确定你指的是哪一项，请点选回答中的发现后追问。"
                }
                val selected = if (chosenIndex != null) listOfNotNull(previous.findings.getOrNull(chosenIndex)) else previous.findings
                if (selected.isEmpty() || selected.size > 6) issue = "请点选一项发现后追问，以确定这次要讨论的内容。"
                else if (issue == null) context = ConversationContextCatalog(repository).plan(parent.runId, selected.map { it.target })
            }
        }
        if (Regex("^(?:不是这样|不对|我不是这个意思|不是这个意思|这不准确|我不同意)[。！!？?]*$").matches(trimmed))
            issue = "请说明哪一项不符合，以及实际情况。要纠正或否定已保存的判断，请在原回答中打开依据与保存，明确处理你的意见。"
        val topics = routed.focusTopics.ifEmpty { if (parent != null) parent.focusTopics else emptySet() }
        return ConversationPlan(UUID.randomUUID().toString(), thread?.id ?: UUID.randomUUID().toString(), trimmed,
            routed.scope, topics, routed.intent, routed.label, issue, parent?.id, chosenIndex, context, latest?.id)
    }

    /** Same prepared ID can be submitted again safely, including after the answer's source is deleted. */
    fun submit(plan: ConversationPlan, limits: AnalysisLimits = AnalysisLimits(inputTokenLimit = 32_000)): ConversationTurn =
        synchronized(locks.getOrPut(repository.currentStoreId() + ":" + plan.threadId) { Any() }) {
            require(plan.canStart) { plan.clarification ?: "请先确认问题范围" }
            repository.conversationTurn(plan.id)?.let { existing ->
                require(existing.threadId == plan.threadId && existing.question == plan.question && existing.scope == plan.scope &&
                    existing.focusTopics == plan.focusTopics && existing.parentTurnId == plan.parentTurnId &&
                    existing.parentFindingIndex == plan.parentFindingIndex) { "同一提问身份不能改写" }
                return@synchronized existing
            }
            val latest = repository.latestConversationTurn(plan.threadId)
            require(latest?.id == plan.expectedLastTurnId) { "对话已有新问题，请重新确认本次范围" }
            require((latest?.position ?: 0) < 200) { "这段对话已达 200 轮，请开启新的问题" }
            require(plan.question.isNotBlank() && plan.question.length <= 1_000)
            require(plan.scope.through <= LocalDate.now(clock.withZone(ZoneId.of(plan.scope.zoneId))))
            plan.parentTurnId?.let { parentId ->
                val parent = requireNotNull(repository.conversationTurn(parentId)) { "追问的问题已不存在" }
                require(parent.threadId == plan.threadId && plan.context?.parentRunId == parent.runId)
            }
            require((plan.parentTurnId == null) == (plan.context == null))
            plan.context?.let { require(ConversationContextCatalog(repository).current(it)) { "旧回答的依据或意见已变化，请重新提问" } }
            if (repository.conversation(plan.threadId) == null) repository.createConversation(plan.threadId)
            val runId = "reflection-v1-conversation-${plan.id}"
            reflection.startQuestion(plan.question + "\n" + ANSWER_GUIDANCE, plan.scope, limits, plan.context, runId)
            val turn = ConversationTurn(plan.id, plan.threadId, (latest?.position ?: 0) + 1, plan.question, plan.scope,
                plan.focusTopics, plan.parentTurnId, plan.parentFindingIndex, runId, clock.instant())
            try { repository.appendConversationTurn(turn) } catch (failure: Exception) {
                // Do not leave an invisible, executable task if the independent question could not be saved.
                repository.analysisRun(runId)?.let { run -> runCatching { analysis.delete(run.id, run.version) } }
                throw failure
            }
            turn
        }

    fun history(offset: Int = 0, limit: Int = 20): List<ConversationThread> = repository.conversationPage(offset, limit)
    fun latestTurn(threadId: String): ConversationTurn? = repository.latestConversationTurn(threadId)
    /** Recover the user's original wording, never a model-written replacement or a chat transcript. */
    fun rephraseSource(turn: ConversationTurn): ConversationTurn? {
        var current = turn
        repeat(8) {
            val parent = current.parentTurnId ?: return current
            current = repository.conversationTurn(parent)?.takeIf { it.threadId == turn.threadId } ?: return null
        }
        return current.takeIf { it.parentTurnId == null }
    }
    fun turns(threadId: String, offset: Int = 0, limit: Int = 20): List<ConversationTurn> = repository.conversationTurns(threadId, offset, limit)
    fun inspect(turn: ConversationTurn): ReflectionRun? = try { reflection.inspect(turn.runId) }
        catch (_: AnalysisTaskUnavailableException) { null }
    fun delete(threadId: String) = repository.deleteConversation(threadId)

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        internal const val ANSWER_GUIDANCE = "请围绕上面的实际问题回答记录者本人；按需要分别给出「变化：」「反复出现：」「可以尝试：」或「回答：」，总计最多6条，不强凑类别。" +
            "比较前后方法时说明时间和情境；问题重复出现须有独立事件依据，建议说明尝试条件和可观察信号。" +
            "追问背景只用于确定指代，不是新证据；重新读取本轮原文，检查支持、例外和后来的变化。" +
            "缺少资料就说明未知，不把未记录当成没有进步，不作人格标签、健康诊断、成长评分或未经程序计算的统计。" +
            "正文直接回答问题，不使用‘用户记录了’等第三人称复述。结果是待核对发现，不表示已确认、保存知识或采纳行动。"
    }
}
