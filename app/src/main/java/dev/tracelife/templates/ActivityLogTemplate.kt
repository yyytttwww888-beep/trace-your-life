package dev.tracelife.templates

import dev.tracelife.model.Cadence
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.model.TemplateRef
import dev.tracelife.storage.LifeRepository

/** A versioned input contract stored in ordinary record answers, never inferred from prose. */
object ActivityLogTemplate {
    const val DOMAIN = "activity.v1.domain"
    const val MINUTES = "activity.v1.minutes"
    const val PRACTICES = "activity.v1.practices"
    const val OUTCOME = "activity.v1.outcome"
    const val CONTEXT = "activity.v1.context"

    val draft = TemplateDraft(
        name = "投入与实践", cadence = Cadence.EVENT,
        description = "记录一次工作或学习活动。只填已知内容，不重复计入同一次活动；留空表示没记录，0 表示明确没有。数值是自己的报告，不代表成长评分。",
        questions = listOf(
            TemplateQuestion(DOMAIN, "领域", "填写“工作”或“学习”，选择这次活动的主要领域。", "activity_domain_v1"),
            TemplateQuestion(MINUTES, "投入分钟", "可选，填写 0–1440 的整数；只填本次活动的实际分钟，不把计划时长算进去。", "activity_minutes_v1"),
            TemplateQuestion(PRACTICES, "亲自实践次数", "可选，填写 0–10000 的整数；一次独立尝试算一次，阅读、收藏或计划不自动算实践。", "activity_practices_v1"),
            TemplateQuestion(OUTCOME, "留下的成果", "可选，具体完成了什么、产出在哪里；没有明确成果时留空。", "activity_outcome_v1"),
            TemplateQuestion(CONTEXT, "方法与条件", "可选，怎样做的，哪些条件影响了这次过程？", "activity_context_v1"),
        ),
    )

    /** Changes to a question's meaning stop numeric aggregation; old bound versions stay usable. */
    fun supports(questions: List<TemplateQuestion>): Boolean = questions == draft.questions

    fun number(text: String, maximum: Int, label: String): Int? {
        val value = text.trim()
        if (value.isEmpty()) return null
        require(value.matches(Regex("[0-9]{1,5}"))) { "${label}须为整数，未记录时请留空" }
        return requireNotNull(value.toIntOrNull()).also { require(it in 0..maximum) { "${label}须在 0–$maximum 之间" } }
    }

    /** Reuse a compatible current template; a custom/archived/deleted template is never overwritten. */
    fun obtain(repository: LifeRepository): TemplateRef {
        val template = repository.listTemplates().firstOrNull { supports(it.draft.questions) }
            ?: repository.saveTemplate(draft)
        return TemplateRef(template.id, template.version)
    }
}
