package dev.tracelife.templates

import dev.tracelife.model.Cadence
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion

/** Product adaptations of AAR, blameless review and PDSA; these are not official scales. */
object BuiltInTemplates {
    private const val METHODS = """方法说明：以下问题是 Trace Life 对 AAR、无责复盘和 PDSA 的个人使用适配，不是各机构的官方个人成长模板，也不是人格或健康量表。
来源：
- WHO 行动后回顾（AAR）：https://www.who.int/emergencies/operations/emergency-response-reviews/after-action-review
- Google SRE 无责复盘：https://sre.google/sre-book/postmortem-culture/
- IHI PDSA 改进循环：https://www.ihi.org/library/model-for-improvement/testing-changes
所有问题均可跳过。区分事实、感受和解释；不因缺少记录而补写事实，不把一次观察当作确定因果。"""

    val daily: TemplateDraft = build(
        name = "每日复盘",
        cadence = Cadence.DAILY,
        description = "围绕一个值得理解的事件，建议约 5–10 分钟，也可分次填写。没有预设目标时可选择一次决定、意外或体验；休息与维持状态也是合理结果，没有新行动时可以结束。所有问题均可跳过。",
        questions = listOf(
            question("daily.follow_up", "回看昨天的行动", "原定调整是否发生？实际结果是什么？未发生时，缺少了什么条件？", "action_follow_up"),
            question("daily.expectation", "期望", "今天最重要的事情是什么？原本希望出现什么结果，为什么值得做？没有预设目标时不必事后编造。", "expectation"),
            question("daily.evidence", "实际与证据", "实际发生了什么？有哪些具体产出、行为或感受支持这个判断？哪些没有记录？", "actual_and_evidence"),
            question("daily.explanation", "解释差距", "哪些做法起了作用，哪些因素造成偏差？区分已知事实、自己的假设、可控因素和外部条件；有没有其他合理解释？", "contributing_factors"),
            question("daily.learning", "提炼经验", "今天形成了什么可复用的认识？它适用于什么情境，哪些情境不适用？有什么值得保持？", "reusable_learning"),
            question("daily.experiment", "下一步实验", "明天或下个合适场景只改变什么？什么时候触发，怎么做，用什么迹象判断有效，何时回看？没有新行动时可以留空。", "next_experiment"),
            question("daily.state", "个人状态", "这件事对精力、身体、情绪或关系有什么影响？有没有值得保留的愉悦、恢复或连接？", "personal_state"),
        ),
    )

    val weekly: TemplateDraft = build(
        name = "每周复盘",
        cadence = Cadence.WEEKLY,
        description = "检查本周的模式与方向，建议约 20–30 分钟，可拆分完成。判断范围限于已有资料，所有问题均可跳过。",
        questions = listOf(
            question("weekly.direction", "目标与投入", "本周真正重视的目标是什么？投入时间与精力的方向和它一致吗？有没有必要调整目标本身？", "direction_and_alignment"),
            question("weekly.evidence", "成果与变化的证据", "有哪些成果、实践、认知变化或恢复迹象？分别有哪些证据，哪些只是主观感觉？", "actual_and_evidence"),
            question("weekly.follow_up", "回看行动实验", "上周行动实验的执行与结果如何？应该继续、调整、停止，还是因资料不足暂缓判断？", "action_follow_up"),
            question("weekly.patterns", "反复出现的顺利与卡点", "哪些顺利与卡点反复出现？工作安排、环境、睡眠和饮食是否出现共同变化？相关性不能直接当作因果。", "contextual_patterns"),
            question("weekly.application", "学习是否得到应用", "学到的内容中，哪些真正应用过或形成产出？哪些仍只是收藏或理解？", "learning_application"),
            question("weekly.experiment", "下周的行动", "下周保持什么、减少什么、尝试什么？优先选择一到两项可检查的行动，说明实施场景、预期迹象和回看时机。", "next_experiment"),
        ),
    )

    val event: TemplateDraft = build(
        name = "重要事件复盘",
        cadence = Cadence.EVENT,
        description = "项目、决策、成功和失误都可以复盘。还原当时的信息与情境，允许多个促成因素，不强制寻找唯一根因，不用人格标签代替解释。所有问题均可跳过。",
        questions = listOf(
            question("event.expectation", "目标与预期", "这个项目、决定或事件原本希望达成什么？当时以什么判断结果是否符合预期？", "expectation"),
            question("event.timeline", "关键时间线", "关键节点发生了什么？当时掌握哪些信息、有哪些选择？无法确定的时间或事实请保留为未知。", "event_timeline"),
            question("event.impact", "结果与影响", "实际结果是什么，对自己或他人产生了什么影响？有哪些产出、行为或感受作为依据，哪些尚未记录？", "actual_and_evidence"),
            question("event.preparation", "已有预案与条件", "当时有哪些预案、资源、约束或保护措施？它们是否被采用，发挥了什么作用？", "preparation_and_context"),
            question("event.effective", "起作用的因素", "哪些做法、环境或协作帮助事情顺利推进？这些判断的依据是什么？", "effective_factors"),
            question("event.explanation", "促成问题的条件", "有哪些共同促成偏差的条件？区分事实和假设、可控与外部因素，考虑其他合理解释。", "contributing_factors"),
            question("event.learning", "可复用的经验", "有什么值得保持或改变？经验适用于什么情境，在什么情况下可能不成立？", "reusable_learning"),
            question("event.experiment", "后续措施与回看", "下一次在哪种情境尝试什么改变？需要什么条件，用什么迹象判断有效，何时回看？", "next_experiment"),
        ),
    )

    private fun question(id: String, prompt: String, hint: String, intent: String) =
        TemplateQuestion(id = id, prompt = prompt, hint = hint, intent = intent)

    private fun build(
        name: String,
        cadence: Cadence,
        description: String,
        questions: List<TemplateQuestion>,
    ) = TemplateDraft(
        name = name,
        cadence = cadence,
        description = description,
        questions = questions,
        sourceMarkdown = buildString {
            append("# ").append(name).append("\n\n")
            append(description).append("\n\n")
            append(METHODS).append("\n")
            questions.forEach { question ->
                append("\n## ").append(question.prompt).append("\n")
                append(question.hint).append("\n")
            }
        },
    )
}
