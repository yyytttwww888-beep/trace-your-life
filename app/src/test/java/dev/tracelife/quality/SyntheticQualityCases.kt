package dev.tracelife.quality

import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import org.json.JSONObject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Immutable expectations established before a live run. These are fictional, not personal data. */
internal object SyntheticQualityCases {
    const val VERSION = "personal-knowledge-quality-v1"
    val clock: Clock = Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private const val SYNTHETIC = "合成验收资料，完全虚构，不是真实个人经历："

    data class Source(val id: String, val date: String, val text: String, val classified: Boolean = true) {
        val body: String get() = SYNTHETIC + text
    }
    data class PriorFeedback(val source: Source, val oldClaim: String, val correction: String)
    data class Case(
        val id: String,
        val question: String,
        val from: String,
        val through: String,
        val sources: List<Source>,
        val requiredDistinctions: List<String>,
        val forbiddenConclusions: List<String>,
        val expectedNewValue: String,
        val prior: PriorFeedback? = null,
    ) {
        val scope get() = AnalysisQuery(LocalDate.parse(from), LocalDate.parse(through), "Asia/Shanghai")
    }

    val all: List<Case> = listOf(
        Case("duplicate-event", "10月1日至7日，我的学习方法有哪些可靠变化，还需要验证什么？", "2026-10-01", "2026-10-07",
            listOf(
                Source("duplicate-note", "2026-10-01", "事件练习A：今天先写最小例子再查资料，第一次跑通。任务简单、可用时间充足。"),
                Source("duplicate-review", "2026-10-02", "补写昨天事件练习A的复盘：先写最小例子，后查资料。今天没有再做练习。"),
                Source("duplicate-summary", "2026-10-07", "本周只做过10月1日那一次事件练习A；把同一次经历又整理成总结，没有第二次或第三次尝试。", false),
            ), listOf("三份记录对应同一个事件练习A，不是三次独立尝试", "任务简单、时间充足是替代解释"),
            listOf("已经三次验证有效", "先写最小例子导致效率提高", "已经形成稳定的学习习惯"),
            "提出下一次在不同任务难度或时间条件下验证；不能把重写记录当新进展。"),
        Case("tail-counterexample", "10月8日，学习时先列清单这个做法有什么适用条件？", "2026-10-08", "2026-10-08",
            listOf(Source("long-counterexample", "2026-10-08", buildString {
                append("上午简单练习先列清单后完成了。我一度觉得先列清单适合所有任务。\n")
                repeat(120) { append("过程摘记第${it + 1}段：整理了资料目录和练习环境；这些是同一天的过程，不是额外成功次数。\n") }
                append("最后补充的反例：下午遇到未知接口时，清单反而让我坚持了错误顺序，重做两次仍未完成。先列清单并不总有效；需要先验证不确定接口。")
            })), listOf("必须读到长文最后的失败情境", "简单任务成功与未知接口失败应共同限制结论"),
            listOf("先列清单适合所有任务", "这一天练习都完成了", "120次过程摘记是120次成功"),
            "识别接口是否确定这个新条件，而不只复述上午的完成情况。"),
        Case("missing-period", "10月12日至18日，我的学习有没有退步，应该关注什么？", "2026-10-12", "2026-10-18",
            listOf(
                Source("missing-monday", "2026-10-12", "今天练习了十分钟，能自己解释一个例子。"),
                Source("missing-sunday", "2026-10-18", "今天不记得中间几天有没有练习，也没有补充记录。今天看了一段课，没有做测试。", false),
            ), listOf("七天只有两天有记录，中间五天执行未知", "看课不等于已掌握，资料不足以判断退步"),
            listOf("五天没有学习", "学习频率下降了71%", "缺少自律", "本周整体退步"),
            "建议下一次保留能否独立完成的小测试，区分学习效果与是否写日记。"),
        Case("historical-correction", "10月20日，关于学习启动方式，现在有什么值得保留的新理解？", "2026-10-20", "2026-10-20",
            listOf(Source("correction-current", "2026-10-20", "今天先跑一个小模块，再根据报错补接口说明，完成一次熟悉的练习。有40分钟空闲，不能确认是启动方式导致完成。")),
            listOf("用户已纠正复杂任务必须先设计这个旧判断", "新结果不能换一种措辞复活被纠正的固定规则", "旧意见只能约束解释，不能当作本次原文证据"),
            listOf("复杂任务必须先设计完整接口", "历史纠正证明先动手一定有效", "已经找到稳定的最佳方法"),
            "保留熟悉度、时间和不确定接口的条件；提出可比较的后续观察。",
            PriorFeedback(Source("correction-prior", "2026-10-06", "三个模块的小任务第一次直接写返工，后来先画接口才完成；只实践过一次。"),
                "复杂任务必须先设计完整接口，这就是你稳定有效的学习方法。",
                "只有一次经历，当时任务更熟悉且时间充足。我没有稳定做法，不认可复杂任务必须先设计这个结论。")),
        Case("plan-execution", "10月12日至18日，我尝试过哪些学习方法，哪些有用，接下来怎么调整？", "2026-10-12", "2026-10-18",
            listOf(
                Source("plan-intention", "2026-10-12", "计划这周每天练习二十分钟，这是打算，还没有执行。"),
                Source("plan-review", "2026-10-18", "回看：二十分钟计划没有开始。记得打开过练习页面，不能确定是否完成过练习。想改成下周五分钟，到今天还没试这个新安排。"),
            ), listOf("二十分钟是原计划且自述没有开始", "打开页面不证明完成", "五分钟是下一轮尚未尝试的计划，效果未知"),
            listOf("本周已完成七次练习", "五分钟的计划已经有效", "建议已被采纳执行", "由记录推算完成率"),
            "给下一轮可观察的启动与完成信号；明确这是待尝试的建议，不能把计划调整当效果。"),
        Case("new-value", "10月15日至20日，相比已有的先做再查资料经验，学习上有什么新发现？", "2026-10-15", "2026-10-20",
            listOf(
                Source("value-known", "2026-10-15", "以前已写过的经验是：卡在找资料时，先做一个小例子再针对报错查。今天简单独立练习照着做，跑通了；没有新情境。"),
                Source("value-exception", "2026-10-18", "协作练习用了先做再查的方法，但队友接口约定没定，单独跑通的例子无法接入，返工两次。问题在依赖没有对齐。"),
                Source("value-condition", "2026-10-20", "另一次协作先和队友确认一个接口，再跑小例子，接入成功。这次队友也在线，任务更熟悉，不能认定只因先确认接口。"),
            ), listOf("先做再查是已有经验；简单任务再成功不自动等于新增理解", "跨人依赖与接口对齐是新条件", "队友在线、熟悉度仍是替代解释"),
            listOf("重复已有经验就证明持续成长", "先对齐接口一定提高效率", "所有任务都必须先设计"),
            "把旧经验收窄到依赖清楚的情境，并提出后续验证协作依赖的具体问题。"),
    )

    fun seed(repository: LifeRepository, analysis: ControlledAnalysis, case: Case) {
        case.prior?.let { prior ->
            create(repository, prior.source)
            val date = LocalDate.parse(prior.source.date)
            val runId = "synthetic-quality-prior-${case.id}"
            analysis.start(AnalysisRequest("合成旧判断，供历史纠正验收", AnalysisQuery(date, date, "Asia/Shanghai"),
                focusTopics = setOf("学习")), runId)
            analysis.synthesize(runId, "prior", listOf(EvidenceSelection(prior.source.id, "body")), object : AnalysisProvider {
                override val id = "synthetic-quality-prior"
                override val contextWindowTokens = 100_000
                override fun countInputTokens(request: ModelRequest) = 100
                override fun complete(request: ModelRequest): BatchProposal {
                    val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                    return BatchProposal(listOf(ProposedClaim(prior.oldClaim, listOf(id), ClaimKind.POSSIBLE_INTERPRETATION)))
                }
            })
            AnalysisFeedback(repository).respond(runId, analysis.claimRef(runId, "prior", 0), analysis.task(runId).stored.version,
                ClaimFeedbackDecision.CORRECTED, prior.correction)
        }
        case.sources.forEach { create(repository, it) }
    }

    private fun create(repository: LifeRepository, source: Source) = repository.createRecord(RecordDraft(source.body,
        Occurrence(LocalDate.parse(source.date), zoneId = "Asia/Shanghai"),
        if (source.classified) setOf("学习") else emptySet()), source.id)
}
