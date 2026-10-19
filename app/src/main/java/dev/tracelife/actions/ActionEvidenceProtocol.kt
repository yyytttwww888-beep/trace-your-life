package dev.tracelife.actions

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.storage.AnalysisQuery
import java.time.ZoneId

/** User-authored plans and reports are independent source types, never device measurements. */
enum class ActionEvidenceKind { OPENING_PLAN, PLAN_CHANGE, USER_REVIEW }
data class ActionEvidenceReceipt(val id: String, val actionId: String, val revision: Int,
    val kind: ActionEvidenceKind, val sha256: String)
data class ActionEvidencePlan(val entries: List<ActionEvidenceReceipt>)
data class ActionEvidencePage(val runId: String, val entries: List<ActionEvidenceReceipt>, val total: Int,
    val nextOffset: Int?)

object ActionEvidenceProtocol {
    const val GENERATION_INSTRUCTIONS = "将本次 actionEvidence 的用户行动计划、回看与 evidence 原文对照，提出有范围的可能解释及下一周期关注项。" +
        "OPENING_PLAN/PLAN_CHANGE 只证明用户制定计划，不证明执行；USER_REVIEW.execution 只是用户报告，UNKNOWN 或没有回看均不是未执行。" +
        "planStatus 仅表明计划仍开放或已停止，不证明执行。reviewedPlan 是本次回看针对的旧计划，nextPlan 是之后选择的计划，不能混淆。observedText 是用户自述，不是设备测量或因果证据。" +
        "所有结论须为 POSSIBLE_INTERPRETATION，仅引用本次原文或行动证据 ID。明确资料不足、反例与适用条件；不以记录数代表成长，不计算改善率或综合分。" +
        "建议继续、调整、停止或暂缓只是提案，不自动修改行动；不能宣称未提供的其他批次或全部生活已核对。"
    const val REVIEW_INSTRUCTIONS = "本次须同时核对全部 actionEvidence 与 evidence。计划不等于执行，执行状态是用户报告；UNKNOWN 和未回看不等于失败。" +
        "核对旧计划、实际报告与下一轮计划是否混淆，是否遗漏反例、夸大范围或因果。反馈是待核对背景而非新增证据。"

    /** Trusted program routing scans retained revisions, while the model sees only selected leaves. */
    fun plan(scope: AnalysisQuery, revisions: List<StoredActionRevision>): ActionEvidencePlan {
        val zone = ZoneId.of(scope.zoneId)
        val selected = buildList {
            revisions.groupBy { it.id }.toSortedMap().forEach { (_, chain) ->
                val ordered = chain.sortedBy { it.revision }
                ordered.lastOrNull { it.updatedAt.atZone(zone).toLocalDate() < scope.from }
                    ?.takeIf { it.status == ActionStatus.OPEN }?.let { add(receipt(it, ActionEvidenceKind.OPENING_PLAN)) }
                ordered.forEach { value ->
                    val savedOn = value.updatedAt.atZone(zone).toLocalDate()
                    val reviewInScope = value.review?.reviewedOn?.let { it >= scope.from && it <= scope.through } == true
                    if (reviewInScope) add(receipt(value, ActionEvidenceKind.USER_REVIEW))
                    else if (savedOn >= scope.from && savedOn <= scope.through) {
                        // An out-of-period retrospective review can change today's plan or stop it.
                        // Route only that new plan/state; never leak the old observation text.
                        add(receipt(value, ActionEvidenceKind.PLAN_CHANGE))
                    }
                }
            }
        }.distinct().sortedWith(compareBy({ it.actionId }, { it.revision }, { it.kind.name }))
        return ActionEvidencePlan(selected).also(::validatePlan)
    }

    fun receipt(value: StoredActionRevision, kind: ActionEvidenceKind): ActionEvidenceReceipt {
        val hash = AnalysisRunCodec.sha256(AnalysisRunCodec.json(body(value, kind)))
        return ActionEvidenceReceipt(AnalysisRunCodec.sha256("trace-life:action-evidence:v1:${value.id}:${value.revision}:${kind.name}:$hash"),
            value.id, value.revision, kind, hash)
    }
    fun modelMap(value: StoredActionRevision, ref: ActionEvidenceReceipt): Map<String, Any?> {
        require(receipt(value, ref.kind) == ref) { "行动原始凭据已变化" }
        return receiptMap(ref) + body(value, ref.kind)
    }
    fun validatePlan(value: ActionEvidencePlan) {
        require(value.entries.size <= ActionValidation.MAX_REVISIONS * 2)
        require(value.entries.map { it.id }.distinct().size == value.entries.size)
        value.entries.forEach(::validateReceipt)
        require(value.entries == value.entries.sortedWith(compareBy({ it.actionId }, { it.revision }, { it.kind.name })))
    }
    fun validateReceipt(ref: ActionEvidenceReceipt) {
        AnalysisRunCodec.validId(ref.actionId)
        require(ref.revision in 1 until Int.MAX_VALUE && ref.sha256.matches(Regex("[0-9a-f]{64}")))
        require(ref.id == AnalysisRunCodec.sha256("trace-life:action-evidence:v1:${ref.actionId}:${ref.revision}:${ref.kind.name}:${ref.sha256}"))
    }
    fun validateSources(plan: ActionEvidencePlan?, refs: List<ActionEvidenceReceipt>, revisions: List<StoredActionRevision>, scope: AnalysisQuery? = null) {
        plan?.let(::validatePlan)
        require(refs.isEmpty() || plan != null && refs.all { it in plan.entries })
        val sources = revisions.associateBy { it.id to it.revision }
        (plan?.entries.orEmpty() + refs).distinct().forEach { ref ->
            val value = requireNotNull(sources[ref.actionId to ref.revision]) { "行动证据缺少原始修订" }
            require(receipt(value, ref.kind) == ref) { "行动证据与原始修订不匹配" }
            scope?.let {
                val date = if (ref.kind == ActionEvidenceKind.USER_REVIEW) requireNotNull(value.review).reviewedOn
                    else value.updatedAt.atZone(ZoneId.of(it.zoneId)).toLocalDate()
                require(if (ref.kind == ActionEvidenceKind.OPENING_PLAN) date < it.from else date >= it.from && date <= it.through) {
                    "行动证据不在声明周期内"
                }
            }
        }
    }
    fun receiptMap(ref: ActionEvidenceReceipt): Map<String, Any?> = mapOf("id" to ref.id, "actionId" to ref.actionId,
        "revision" to ref.revision, "kind" to ref.kind.name, "sha256" to ref.sha256)
    fun planMap(plan: ActionEvidencePlan): Map<String, Any?> = mapOf("entries" to plan.entries.map(::receiptMap))
    fun parseReceipt(raw: Any?): ActionEvidenceReceipt {
        val value = obj(raw, setOf("id", "actionId", "revision", "kind", "sha256"))
        return ActionEvidenceReceipt(string(value, "id"), string(value, "actionId"),
            (value["revision"] as? Number)?.toLong()?.also { require(it in 1 until Int.MAX_VALUE) }?.toInt() ?: error("行动证据修订无效"),
            ActionEvidenceKind.valueOf(string(value, "kind")), string(value, "sha256")).also(::validateReceipt)
    }
    fun parsePlan(raw: Any?): ActionEvidencePlan {
        val value = obj(raw, setOf("entries"))
        return ActionEvidencePlan((value["entries"] as? List<*>)?.map(::parseReceipt) ?: error("行动证据目录无效")).also(::validatePlan)
    }
    private fun body(value: StoredActionRevision, kind: ActionEvidenceKind): Map<String, Any?> {
        ActionValidation.validate(value)
        require(kind != ActionEvidenceKind.USER_REVIEW || value.review != null)
                require(kind != ActionEvidenceKind.OPENING_PLAN || value.status == ActionStatus.OPEN)
        return mapOf("sourceNature" to "USER_AUTHORED", "savedAt" to value.updatedAt.toString(), "planStatus" to value.status.name,
            "plan" to if (kind == ActionEvidenceKind.USER_REVIEW) null else draftMap(value.draft),
            "reviewedPlan" to if (kind == ActionEvidenceKind.USER_REVIEW) draftMap(requireNotNull(value.reviewedDraft)) else null,
            "nextPlan" to if (kind == ActionEvidenceKind.USER_REVIEW && value.status == ActionStatus.OPEN) draftMap(value.draft) else null,
            "review" to if (kind == ActionEvidenceKind.USER_REVIEW) requireNotNull(value.review).let {
                mapOf("execution" to it.execution.name, "observedText" to it.observedText,
                    "decision" to it.decision.name, "reviewedOn" to it.reviewedOn.toString()) } else null)
    }
    private fun draftMap(value: ActionDraft): Map<String, Any?> = mapOf("title" to value.title,
        "trigger" to value.trigger, "behavior" to value.behavior, "expectedSignal" to value.expectedSignal,
        "reviewOn" to value.reviewOn.toString(), "zoneId" to value.zoneId)
    @Suppress("UNCHECKED_CAST")
    private fun obj(value: Any?, keys: Set<String>): Map<String, Any?> = (value as? Map<String, Any?>)
        ?.also { require(it.keys == keys) { "行动凭据字段缺失或多余" } } ?: error("行动凭据需要对象")
    private fun string(value: Map<String, Any?>, key: String) = value[key] as? String ?: error("行动凭据需要文字")
}
