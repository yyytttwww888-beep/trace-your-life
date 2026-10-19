# 自动整理与周期复盘材料接口

本阶段将提取、核对、发布串成可继续执行的流程，并提供按周期组织的原始复盘答案、当前知识和记录覆盖。对应[阶段计划](plans/2026-10-17-period-reflection.md)。接口在后台线程调用，尚未接入页面或 Android 定时调度。

## 自动整理

应用入口为 `TraceLifeApplication.automaticOrganizer`。任务仍由[受控分析接口](CONTROLLED_ANALYSIS.md)创建，固定范围、来源、期限和累计预算；DeepSeek 使用已配置的加密凭据。

```kotlin
val task = app.analysis.start(AnalysisRequest(
    question = "整理本周记录中的自述观察与可复用经验，保留未知项",
    scope = AnalysisQuery(from, through, "Asia/Shanghai"),
))
val result = app.automaticOrganizer.process(
    task.stored.id,
    extractor = app.deepSeekProvider,
    reviewer = app.deepSeekProvider,
    maxModelRequests = 5,
)
val progress = app.automaticOrganizer.inspect(task.stored.id)
```

每轮先处理已有提案，再读取下一段未处理文字。提取和核对合计默认最多 5 次请求，可设置 1–100；失败请求也计数。受控分析的累计预算另行生效，本轮上限不重置任务预算。并发幂等复用可能保守消耗一个操作名额，`modelRequestsThisPass` 只计实际派发。每轮还限制最多 100 次本地发布，避免无界循环。

核对仅使用陈述的原始引用及已有用户反馈，不自动搜索全范围反例，`counterevidenceSearchExhaustive=false`。当前反馈版本下最新成功核对为不支持、矛盾或上下文不足时，自动流程保留该结果，不反复调用直到模型同意。可通过独立 `reviewClaim` 接口选择附加原文后重新核对；这仍不等同于穷尽反例搜索。

已通过核对的自述默认发布为 `OBSERVATION`，主题为空；解释先等待用户确认，再重新核对并发布 `INSIGHT`。已有知识的分类和主题保留。纠正、否定、撤回、删除反馈或永久删除知识都会阻止自动重发。用户的独立反馈文字继续按[反馈接口](CLAIM_REVIEW.md)保存。

进度来自持久调用、证据覆盖与知识修订，重启后可以用同一任务 ID 继续，无需保存第二套队列。失败时返回 `RETRY_NEEDED`，下一次显式执行可以重试；在途请求返回 `NEEDS_RECOVERY`，不会自动判定另一执行者已崩溃。需要恢复时调用已有 `recoverInterrupted`，外部调用可能已经计费。来源改变返回 `STALE`，须重新建立任务。

`inspect` 不调用模型、不写资料。`READY` 表示仍有自动步骤；`PASS_FINISHED` 表示本任务文字片段已处理且现有提案已发布，不能解释为发现了全部事实或得到了正确结论。未读附件、时间不明、跨期、待确认和负面核对保留为 `NEEDS_ATTENTION`。调用返回的 `PASS_LIMIT_REACHED` 表示这一轮停止，进度本身仍可能为 `READY`。

## 周期复盘材料

```kotlin
val report = app.periodReports.open(AnalysisQuery(from, through, "Asia/Shanghai"))
val actions = app.periodReports.answers(report, ReportSection.ACTION, limit = 25)
val fragment = actions.items.firstOrNull()?.let { app.periodReports.answer(report, it) }
val knowledge = app.periodReports.knowledge(report, limit = 25)
val sources = app.periodReports.records(report, limit = 25)
```

本报告接口只组织已有材料，`synthesisStatus=NOT_GENERATED`，不新生成“本周为什么没有成长”“未来几周应关注什么”等综合判断，也不推断行动已执行。另有[有界综合与行动实验接口](SYNTHESIS_ACTIONS.md)生成原文对照提案、保存用户选择及回看；它与此材料视图分别调用。材料按模板问题的稳定 `intent` 分组，原问题、提示、意图与历史模板版本全部保留；分组不代表不同模板的语义完全相同。

| 分组 | 原问题意图 |
| --- | --- |
| 期望 `EXPECTATION` | expectation、direction_and_alignment |
| 实际 `ACTUAL` | actual_and_evidence、event_timeline |
| 因素 `FACTORS` | contributing_factors、effective_factors、preparation_and_context、contextual_patterns |
| 经验 `LEARNING` | reusable_learning、learning_application |
| 行动 `ACTION` | next_experiment |
| 回看 `FOLLOW_UP` | action_follow_up |
| 状态 `STATE` | personal_state |
| 其他 `OTHER` | 自定义及未识别的意图 |

未填写的答案不补全文字；分组计数为零仅表示没有对应答案。随记保留在来源目录，已发布提取结果可进入知识部分，不自动将随记正文硬分到复盘模板。未知时间和跨期记录仍列为候选，答案标注 `timeClass`，知识标注全部证据的 `evidenceTimeClasses`；知识的分析范围不是事件发生日期。

报告按来源元数据分页定位，原始答案只在叶子读取。分页默认 25、最大 100；文字分片默认 1,024、最大 4,096 个 Unicode 码点。报告支持 1–366 天，最多 10,000 条候选记录、20,000 个非空答案和 10,000 个候选知识，超限明确失败。无法安全分片的记录单列 `unreadableRecords`，它们的附件数量也未知；`unreadAttachments` 只统计可枚举记录的附件，不代表识别覆盖。

知识先按分析日期范围交叠选候选，再要求当前修订、来源、期限、最新核对和反馈有效，并且**每一份核对证据**都指向本报告候选记录的当前修订；附加核对引用也必须在范围内。指定 `recordIds` 时不扩大到任务其余记录。无效与越界候选分别计数，日期不交叠的知识不属于候选计数。

`evidence(report, knowledgeId, evidenceId, start, maxCodePoints)` 只读取这份知识已登记的引用范围，`start` 相对于引用开头，返回的坐标仍相对于原文字段；不能借此扩展读取整条随记。分页及叶子读取均在前后检查来源与分析代数。报告绑定最早的已纳入知识期限，即使没有数据库写入，跨过期限也会失效；旧报告须重新 `open`，不会悄悄混入新版本。

报告是应用可重建视图，不新建数据库表，完整备份仍保存其权威来源、模板、分析台账、反馈和知识。知识数量、答案数量不表示 AI 已读完周期资料；实际提取进度只能查看相应任务的片段覆盖。报告接口不能直接作为模型工具暴露。

## 指标证据与导出

```kotlin
val metric = app.metricEvidence.calculate(report.scope)
val metricSources = app.metricEvidence.sources(metric, limit = 25)
val exported = app.dataTransfer.exportPeriodReport(
    File(chosenDirectory, "period-materials.zip"), report,
)
```

`RecordMetricEvidenceService` 为程序计算的记录指标提供稳定身份、版本、单位、缺失／区间／去重口径及来源下钻。同一来源、范围、定义和结果的身份不随计算时间变化；来源、范围或结果改变会改变身份。`describe(report.metrics)` 先按当前资料重新计算并核对所有值，不为调用者提供的任意数字签发身份。来源变化后拒绝旧值与旧下钻。

指标仍为[记录覆盖口径](KNOWLEDGE.md)：当前修订按记录 ID 去重；只有精确到单日的记录计入每日数，区间不摊到每天，未知时间单列；零表示没有单日记录，不表示没有睡眠、运动、学习或成长。此材料指标身份供应用与导出使用；模型引用另走[受控指标协议](METRIC_ANALYSIS.md)及独立类型化凭据，不能把材料对象直接放进指令或聊天历史绕过预算。

周期 ZIP 的文件结构、一致性和限制见[数据转移接口](DATA_TRANSFER.md#周期复盘材料导出)。它是生成时的历史材料，包含 Markdown、分页 JSON 和指标 CSV，不是完整恢复备份。导出不调用模型。

分层跨批草稿通过[独立接口](PERIOD_REDUCTION.md)生成和查阅；本材料视图仍为 `NOT_GENERATED`，其 ZIP 不包含草稿，也不将归并完成当作原文核对通过。
