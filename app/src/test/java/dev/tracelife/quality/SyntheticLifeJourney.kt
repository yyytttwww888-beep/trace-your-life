package dev.tracelife.quality

import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One fictional person's ordinary, incomplete notes, fixed before the live journey runs.
 * Review references below are evaluator material; only Source.body enters the user's records.
 * These are not authored false claims or instructions hidden in source evidence.
 */
internal object SyntheticLifeJourney {
    const val VERSION = "synthetic-life-journey-v1"
    const val ZONE = "Asia/Shanghai"
    private const val SYNTHETIC = "合成验收资料，完全虚构，不是真实个人经历：\n"
    val clock: Clock = Clock.fixed(Instant.parse("2026-10-21T04:00:00Z"), ZoneId.of(ZONE))

    data class Source(
        val id: String,
        val date: LocalDate,
        val body: String,
        val topics: Set<String> = setOf("学习"),
    ) {
        constructor(id: String, date: String, text: String, topics: Set<String> = setOf("学习")) :
            this(id, LocalDate.parse(date), SYNTHETIC + text, topics)

        val draft: RecordDraft get() = RecordDraft(body, Occurrence(date, zoneId = ZONE), topics)
    }

    enum class ReferenceKind {
        OBSERVATION,
        CHANGE,
        RECURRING_PROBLEM,
        EXCEPTION,
        USER_EXPLANATION,
        UNCERTAINTY,
    }

    /** Source IDs identify where an evaluator can check a meaning, not mandatory output wording. */
    data class ReferenceFact(
        val id: String,
        val kind: ReferenceKind,
        val description: String,
        val sourceIds: List<String>,
    )

    data class Stage(
        val id: String,
        val title: String,
        val from: LocalDate,
        val through: LocalDate,
        val question: String,
        val references: List<ReferenceFact>,
        val usefulOutcome: String,
        val followUpQuestion: String? = null,
    ) {
        constructor(
            id: String, title: String, from: String, through: String, question: String,
            references: List<ReferenceFact>, usefulOutcome: String, followUpQuestion: String? = null,
        ) : this(id, title, LocalDate.parse(from), LocalDate.parse(through), question,
            references, usefulOutcome, followUpQuestion)

        val scope: AnalysisQuery get() = AnalysisQuery(from, through, ZONE)
        val sources: List<Source> get() = all.filter { it.date >= from && it.date <= through }
        val asOfClock: Clock get() = Clock.fixed(
            through.atTime(12, 0).atZone(ZoneId.of(ZONE)).toInstant(),
            ZoneId.of(ZONE),
        )
    }

    val all: List<Source> = listOf(
        Source("journey-0722-tabs", "2026-07-22",
            "下班想用 Python 整理一下自己的记账表。看了五十分钟教程，标签页越开越多，文件还没建。" +
                "一直想先把 pandas 看明白，又不知道今晚到底先做哪一步。晚饭煮面，外面下雨。"),
        Source("journey-0724-course", "2026-07-24",
            "又找了个评价不错的入门课，看完一节觉得听懂了。合上视频对着自己的表还是没动笔，" +
                "又去搜别人怎么做。我好像一直在换资料，没把第一步写出来。", emptySet()),
        Source("journey-0726-three-rows", "2026-07-26",
            "今天不想继续换教程了，手敲三行支出，先写一个求和的小文件，终于输出了合计。" +
                "遇到读文件报错才去查路径。只处理了这个很小的例子，分类还没做。" +
                "下次可以先写出想看到的一个结果，再决定查什么。"),
        Source("journey-0802-walk", "2026-08-02",
            "傍晚绕公园走了一圈，和朋友聊了最近的工作。回来买了水果，收拾房间。", setOf("生活")),
        Source("journey-0805-category", "2026-08-05",
            "做记账分类，先拿五行数据写映射，没有从头看课。餐饮类别对不上，找到字典取值的说明后改好了。" +
                "二十分钟跑出了这五行的分类。这次目标很小，能直接看到结果。"),
        Source("journey-0812-trip", "2026-08-12",
            "这几天出差，日记断了，路上看过哪些东西也记不清了。今天把电脑文件夹清理了一下，" +
                "周末再接着弄记账脚本。", emptySet()),
        Source("journey-0818-delimiter", "2026-08-18",
            "今天给支出脚本加分隔符处理。先写两行带逗号和引号的数据，跑出错误才查 CSV 的用法，" +
                "改完这两行都能读。用了十五分钟。感觉有个具体错要解决，就没那么想继续翻教程。"),
        Source("journey-0825-two-formats", "2026-08-25",
            "想同时兼容两种账单，第一步又没想清楚。看了四十五分钟别人的项目，来回比较哪种结构好，" +
                "自己的脚本没往前走。已经会做小例子了，遇到目标大一点还是容易卡回找资料。"),
        Source("journey-0902-import", "2026-09-02",
            "和小周做一个账单导入练习，我先按自己的字段名写了个能跑的例子。接他的页面时才发现金额单位" +
                "和日期格式没约定，改了两轮，今晚没接完。不是例子跑不了，是两边各写各的。", setOf("学习", "协作")),
        Source("journey-0904-interface", "2026-09-04",
            "接着做前天的导入，先和小周把字段、金额单位、错误返回写在一张纸上，再用五行数据接页面，接上了。" +
                "小周今天一直在线，我也熟悉了前天的代码。感觉先对齐这些事少了很多猜测。", setOf("学习", "协作")),
        Source("journey-0909-evening", "2026-09-09",
            "今天开会开得晚，回家只想歇着。听歌洗了衣服，给家里打了电话。明天要早起。", setOf("生活", "工作")),
        Source("journey-0913-chart", "2026-09-13",
            "给记账结果画图，先造三条数据画出一个柱子，再换成自己的数据。图例挤在一起，查到参数后改好了。" +
                "这种输入输出都清楚的小功能，先跑一版挺顺手。"),
        Source("journey-0918-merge", "2026-09-18",
            "做多份账单合并，没有直接开写。先列了哪些算同一笔、日期缺了怎么办，再拿两份很小的数据试。" +
                "发现商户名相同也可能是两笔，把规则改了才继续。今天下午时间比较完整，没急着赶完。"),
        Source("journey-0923-family", "2026-09-23",
            "周末回家，陪爸妈去买东西，几天没记。手机里多了好多猫的照片，回来再整理。", emptySet()),
        Source("journey-0928-new-library", "2026-09-28",
            "试一个不熟悉的异步库，照着旧办法直接拼小例子，一直不明白为什么没有结果。" +
                "后来读了官方最短的入门，才知道运行方式和以前用的不同。改完跑起来了。" +
                "碰到概念都不熟的东西，硬写也会原地转。"),
        Source("journey-1005-format", "2026-10-05",
            "把报告金额的显示格式改了。先拿一个正数和一个负数试输出，再放回脚本，不到一会儿就弄好了。" +
                "下午还去买了菜，晚上做番茄炒蛋。", emptySet()),
        Source("journey-1012-multiple-imports", "2026-10-12",
            "给导入工具加另一家账单格式，先列两边字段怎么对应、空值怎么处理，再各拿两行数据跑。" +
                "中间发现退款符号相反，先确认含义才继续。最后两种格式能输出同一张表了，" +
                "大文件还没试。以前我可能会直接抄一个导入函数开始改。"),
        Source("journey-1021-today", "2026-10-21",
            "今天想加个看起来很大的统计功能，打开浏览器差点又开始找整套教程。先停下来写了今晚只想看到的" +
                "一张分类表，拿旧数据做出了第一版。后面的月份对比还没做。感觉先把这一次要回答的问题缩小，" +
                "比要求自己立刻懂完整套东西轻松一些。"),
    )

    val daily = Stage(
        id = "daily", title = "随手记后的当日整理", from = "2026-07-22", through = "2026-07-22",
        question = "帮我整理今天的记录，学习上有什么值得留下，下一次可以怎么开始？",
        references = listOf(
            ReferenceFact("daily-starting-point", ReferenceKind.OBSERVATION,
                "想做自己的记账表，实际在看教程和开标签页，尚未建立文件；当晚没有写清可执行的第一步。",
                listOf("journey-0722-tabs")),
            ReferenceFact("daily-limited-evidence", ReferenceKind.UNCERTAINTY,
                "只有一天的自述，可以给下一次尝试的具体起步建议，尚不能判断稳定习惯、建议效果或学习退步。",
                listOf("journey-0722-tabs")),
        ),
        usefulOutcome = "把学习目标、当晚卡点及一个可以试的小步骤分清，能回到这条随记；不用把吃饭下雨也变成问题。",
    )

    val weekly = Stage(
        id = "weekly", title = "一周后的反复问题", from = "2026-07-20", through = "2026-07-26",
        question = "这周学习上有什么反复卡住的地方，有没有值得继续试的变化？",
        references = listOf(
            ReferenceFact("week-repeat", ReferenceKind.RECURRING_PROBLEM,
                "7月22日和24日两段自述都在换资料、看教程，而没有开始自己的例子；卡点与第一步不明确有关。",
                listOf("journey-0722-tabs", "journey-0724-course")),
            ReferenceFact("week-first-change", ReferenceKind.CHANGE,
                "7月26日改为手写三行数据、先做求和，再查具体报错，出现第一个可运行的小结果。",
                listOf("journey-0726-three-rows")),
            ReferenceFact("week-not-a-habit", ReferenceKind.UNCERTAINTY,
                "本周只有三天有资料；一次小例子成功可保留为尝试，不足以判断每天行为或已经形成稳定方法。",
                listOf("journey-0722-tabs", "journey-0724-course", "journey-0726-three-rows")),
        ),
        usefulOutcome = "连接至少两次起步困难和一次新的尝试，给下周可以验证的做法；不只是逐条复述日记。",
    )

    val threeMonths = Stage(
        id = "three-months", title = "三个月的方法变化、原因与例外", from = "2026-07-22", through = "2026-10-21",
        question = "这三个月，我在学习上的方法发生了哪些变化，哪些问题反复出现？这些变化可能和什么有关，" +
            "又有哪些例外？帮我串起来，能回看具体经历。",
        references = listOf(
            ReferenceFact("early-search-loop", ReferenceKind.CHANGE,
                "早期以找教程、想先理解完整工具为主，没有明确自己的第一步；7月26日开始尝试先做很小的可运行结果。",
                listOf("journey-0722-tabs", "journey-0724-course", "journey-0726-three-rows")),
            ReferenceFact("small-example-method", ReferenceKind.CHANGE,
                "8月及之后几次输入输出清楚的独立功能采用先做小例子，再针对报错或显示问题查资料。",
                listOf("journey-0805-category", "journey-0818-delimiter", "journey-0913-chart", "journey-1005-format")),
            ReferenceFact("boundaries-before-examples", ReferenceKind.CHANGE,
                "协作字段不一致造成返工后，出现先对齐接口或列数据边界、再做小样本的做法；10月12日仍保留这一顺序。",
                listOf("journey-0902-import", "journey-0904-interface", "journey-0918-merge", "journey-1012-multiple-imports")),
            ReferenceFact("large-goals-repeat", ReferenceKind.RECURRING_PROBLEM,
                "目标扩大、第一步不清楚时，8月25日再次陷入资料比较；10月21日仍有这个苗头，但这次先缩小问题后做出第一版。",
                listOf("journey-0722-tabs", "journey-0724-course", "journey-0825-two-formats", "journey-1021-today")),
            ReferenceFact("coordination-exception", ReferenceKind.EXCEPTION,
                "9月2日例子本身能运行仍无法接入协作页面；先动手并不替代字段、单位和日期格式的约定。",
                listOf("journey-0902-import", "journey-0904-interface")),
            ReferenceFact("new-concept-exception", ReferenceKind.EXCEPTION,
                "9月28日面对陌生异步概念，直接拼例子没有进展；读最短入门后才跑通，不能概括为任何任务都先写再读。",
                listOf("journey-0928-new-library")),
            ReferenceFact("possible-reasons", ReferenceKind.USER_EXPLANATION,
                "用户自述具体错误减少了继续翻教程的冲动、对齐约定减少猜测、缩小本次问题更轻松；这些是用户感受及可能机制。",
                listOf("journey-0818-delimiter", "journey-0904-interface", "journey-1021-today")),
            ReferenceFact("conditions-not-causality", ReferenceKind.UNCERTAINTY,
                "任务难度、熟悉程度、队友在线及完整可用时间也在变化，记录不能单独证明某个步骤导致效率提升。",
                listOf("journey-0805-category", "journey-0904-interface", "journey-0918-merge")),
            ReferenceFact("incomplete-life-record", ReferenceKind.UNCERTAINTY,
                "记录是间歇的，夹有生活杂记与明确漏记；未记录日期的学习情况未知，没有完成率、掌握度或固定人格结论的依据。",
                listOf("journey-0802-walk", "journey-0812-trip", "journey-0909-evening", "journey-0923-family")),
        ),
        usefulOutcome = "把起步方式从找完整资料、到小例子、到按任务条件先澄清边界的变化串起来，保留仍会反复的卡点、例外和原因不确定性；主要判断都有可读原文。",
        followUpQuestion = "你说的先动手不适合所有情况，具体是哪几次？当时哪里不同，后来我是怎么调整的？",
    )

    val stages: List<Stage> = listOf(daily, weekly, threeMonths)

    /** Add only the notes available at this checkpoint, retaining previously saved knowledge. */
    fun seedUntil(repository: LifeRepository, through: LocalDate): List<String> {
        val sources = all.filter { it.date <= through }
        val added = mutableListOf<String>()
        sources.forEach { source ->
            val existing = repository.record(source.id)
            if (existing == null) {
                repository.createRecord(source.draft, source.id)
                added += source.id
            } else {
                check(existing.draft == source.draft) { "Journey source changed: ${source.id}" }
            }
        }
        return added
    }
}
