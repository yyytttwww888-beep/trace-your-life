# 受控指标分析

本接口让 DeepSeek 引用程序计算的 `record-coverage` v1；统计口径见[知识与指标](KNOWLEDGE.md#确定性记录指标)，实现和验收见[第九阶段计划](plans/2026-10-19-metric-evidence.md)。接口在后台线程调用，页面尚未接入。

## 解释、核对与发布

```kotlin
val task = app.analysis.start(AnalysisRequest(
    question = "说明这个期间的记录覆盖与资料缺口，不据此判断成长或健康。",
    scope = AnalysisQuery(from, through, "Asia/Shanghai"),
))
val proposal = app.analysis.analyzeMetrics(task.stored.id, "metric-1", app.deepSeekProvider)
// 用户实际查看并决定采纳某条陈述后才执行确认；空 claims 不进入以下步骤。
val target = app.analysis.claimRef(task.stored.id, "metric-1", claimIndex = 0)
app.analysisFeedback.respond(task.stored.id, target,
    app.analysis.task(task.stored.id).stored.version, ClaimFeedbackDecision.CONFIRMED)
val review = app.analysis.reviewClaim(task.stored.id, "metric-review-1", target,
    provider = app.deepSeekProvider)
// 只有最新核对为 SUPPORTED 且确认仍有效时，发布事务才允许保存。
val saved = app.knowledge.publish(task.stored.id, target, "metric-review-1",
    KnowledgeCategory.INSIGHT, app.analysis.task(task.stored.id).stored.version)
val sources = app.metricEvidence.sources(saved.metricEvidence.single(), offset = 0, limit = 25)
```

`METRIC_ANALYSIS` 每次自动计算任务的整个固定范围，最多 366 天。调用方不传数值或来源子集；主题只作为任务关注信息，不改变统计分母。它与原文提取、原文综合和周期归并分别登记，既不增加原文处理覆盖，也不成为归并叶节点。

所有输出均为 `POSSIBLE_INTERPRETATION`。核对自动重新计算相同来源和范围，并要求类型化凭据完全相同；此路径不接收附加原文片段。模型只能引用本次实际提供的指标 ID。用户确认、模型核对和证据当前性分别保存；确认改变反馈版本，须在确认后重新核对再发布。纠正、否定、撤回或删除反馈仍按[既有规则](CLAIM_REVIEW.md)处理。

## 数据与预算

`RecordMetricReceipt` 独立于原文 `EvidenceReceipt`，包含来源版本、完整范围、数值、每日序列和排序去重的来源 `(recordId, revision)` 清单。身份同时绑定口径、数值及清单；调用台账保存时间，计算时间不改变凭据身份或重试请求。

模型输入只包含类型化数值、口径、单位、缺失规则及来源清单摘要／计数。来源清单保存在本地，不整批发给模型，原文及附件也不会随指标请求发送。显式 ID 范围及缺失 ID 属于查询描述，仍随范围提供。程序和普通图表查询共用统计实现。

所有指标调用与核对共用既有完整供应商请求预算、累计字节、次数、输出预留、幂等键和中断恢复。清单最多 10,000 项；分析载荷仍限 2 MiB，单知识修订仍限 256 KiB，节点数也有上限，因此大范围可能在达到记录上限前因保存容量被拒绝。不会截断清单或抽样后冒称全范围。缩小范围需明确建立新的任务。

数值只能解释为记录覆盖：未知时间不强行归入某天，多日区间不摊到每天；零记录不是零活动。当前没有比较周期的差值、比例、改善率等计算字段，提示词要求模型不自行生成这些统计。程序机械保证数值、引用、范围及来源一致，**自然语言是否正确表达数值和含义仍由语义核对及用户复核检查**，不能据此宣称模型解释完全正确。

## 保存、恢复及导出

分析载荷 v7 和知识载荷 v3 保存独立 `metricEvidence`，旧版本仍可读取；SQLite 与外层备份不变。发布事务按当前完整范围重新计算，拒绝替换数值、缺源或混合快照。来源新增、修改、归档、删除及任务过期使旧说明失效；永久删除规则保留用户独立文字。

备份验证会依据实际来源修订重算指标；若凭据来源恰好等于备份来源，还核验当时快照的完整候选集合。历史来源代数不同的凭据只能验证登记成员及算术一致，不能由当前记录目录认证历史枚举完整性。恢复推进来源代数，历史说明不会重新成为当前结果。

知识阅读包、周期材料包只有完整包含指标日期范围和全部来源修订时才纳入该说明；空记录指标也检查日期、归档和精确 ID 等范围条件。指标文件、原文引用各自导航；指标凭据不能传给原文分片接口。普通材料的 `RecordMetricEvidence` 仍为应用统计身份，与本协议的模型凭据分别管理。导出包是固定快照，重新进入产品模型时仍须走受控接口。

本轮尚不支持健康指标、自由文字数值提取、原文与指标混合综合、指标驱动的自动行动或长期画像。
