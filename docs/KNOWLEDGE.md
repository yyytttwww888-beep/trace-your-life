# 正式知识与记录指标接口

本阶段把已核对的陈述保存为独立知识修订，提供分页目录、完整备份与分层阅读导出。设计与验收见[第四阶段计划](plans/2026-10-17-knowledge-publication.md)，前置调用见[证据核对](CLAIM_REVIEW.md)。这些接口均在后台线程使用，尚未接入页面。

## 条件发布

```kotlin
val claim = app.analysis.claimRef(runId, extractBatchKey, claimIndex = 0)
val review = requireNotNull(app.analysis.currentReview(runId, claim))
val saved = app.knowledge.publish(
    runId = runId,
    claim = claim,
    reviewKey = review.batchKey,
    category = KnowledgeCategory.KNOWLEDGE_NOTE,
    expectedRunVersion = app.analysis.task(runId).stored.version,
    topics = setOf("learning"),
)
```

发布必须引用同一陈述及当前反馈版本下**最新成功核对**，且判定为 `SUPPORTED`。较晚核对即使仍为支持，也会使旧发布进入待更新状态；不能跳过较晚的反对意见，挑选过去的支持意见。模型支持是有限证据下的质量检查，不代表事实认证。

分类 `OBSERVATION` 与 `KNOWLEDGE_NOTE` 只接收 `REPORTED_EXPERIENCE`；`INSIGHT` 只接收 `POSSIBLE_INTERPRETATION`，本阶段另外要求用户确认。确认改变反馈版本，因此确认后须重新核对再发布。主题由调用方明确选择，只用于组织；保存概念、方法或经验不代表已经掌握或实践。

`CORRECTED`、`REJECTED`、`WITHDRAWN` 和永久删除反馈均阻止原陈述发布。正文必须与已核对原陈述完全一致，不能替换成用户纠正文字后沿用原核对。独立用户文字仍由反馈接口维护。

存储事务重新检查来源快照、任务版本、期限、核对身份、反馈版本与预期知识修订，再保存不可变修订并切换指针。首次传 `expectedRevision = null`，后续传用户已查看的修订号。相同审核副本、分类和主题的重试复用已有修订，其他版本冲突明确失败。模型没有直接写正式知识的权限。

## 查阅与维护

```kotlin
val page = app.knowledge.index(KnowledgeFilter(topics = setOf("learning")), limit = 25)
val next = page.nextOffset?.let { app.knowledge.next(page) }
val entry = app.knowledge.get(saved.id)
val history = app.knowledge.history(saved.id, offset = 0, limit = 25)
```

目录只返回身份、修订、分类、主题、分析范围和有效状态，单页最多 100 条，不含正文。后续分页绑定同一范围、来源、分析代数和 `evaluatedAt`；内容变化须从首页重读。日期筛选定位分析适用范围，不冒充陈述的精确发生日期。`get` 定位正文与证据；历史的 `isCurrentRevision` 与证据状态分别表示。

| `evidenceState` | 含义 |
| --- | --- |
| `CURRENT` | 此次读取时刻的来源、期限、反馈和核对依赖有效 |
| `STALE` | 来源快照变化，包括补记或恢复；保守失效，不自动重算 |
| `EXPIRED` | 适用期限已到，无需等待新记录 |
| `FEEDBACK_CHANGED` | 反馈版本或决定不再允许采用该副本 |
| `REVIEW_CHANGED` | 同一陈述与反馈版本已有其他成功核对，需要重新发布 |

目录只枚举当前指针，默认排除无效条目并返回 `omittedInvalid`；`includeInvalid = true` 显示已失效的当前修订，旧修订须用 `history` 或 `get(id, revision)`。分页状态表示 `evaluatedAt` 时刻，需要当前状态时重新 `get` 或从目录首页开始。

条目独立保存陈述、核对意见、原文片段引用、范围和期限，无需重新运行模型恢复。只复制本次核对的 `unknowns`，整批提取中其他记录的未知项留在任务，防止范围外摘录进入条目。原文不复制；引用仍定位记录修订、字段、Unicode 片段及哈希。知识不是新原始证据，取证接口继续只读取原始记录，本轮没有直接向模型开放知识正文工具。

来源修订使旧知识失效。永久删除记录或模板沿用保守策略，清理全部机器知识历史；删除任务清理其派生知识，删除反馈清理该陈述的知识。用户独立文字不随知识清理。`app.knowledge.delete(id, expectedRevision)` 永久删除整个条目与修订链并保留删除标记，同 ID 不可复用；日常撤回使用已有反馈机制。

最多 10,000 个知识修订，单修订 JSON 256 KiB，合计 16 MiB，删除标记 100,000 个；超限明确失败。跨任务同义合并、范围级精确失效与自动重算仍待实现。

## 分层阅读与恢复

知识包从 `notes/index.md` 进入主题、分析月份／区间、分页列表和唯一正文，独立 ID 索引按知识 ID 哈希分片。原始记录与知识相互链接，原始证据目录不混入派生知识。

只纳入采集时有效的当前知识，且要求**本次核对涉及的全部原始记录修订**均在选定包内。设置主题筛选时，知识自身的主题也须命中。范围外、旧修订、失效或缺少原文的条目按原因计数并省略，不扩大导出范围。`EVIDENCE` 包只包含原始资料，明确省略所有知识。包内状态是 `capturedAt` 时的固定历史快照，离线文件不会随修改或时间自动更新。

完整备份包含全部知识修订、当前指针和删除台账。恢复保留目标库同身份知识链，防止旧备份回滚；替换后无法保留其原始依据时清理整链并记录永久删除。若目标存在备份未包含的记录或模板删除标记，当前保守策略会清理全部候选机器知识并登记删除，即使部分直接证据仍完整。恢复知识保留原来源身份作为历史，不能重新标为当前。版本与限制见[数据转移接口](DATA_TRANSFER.md)。

## 确定性记录指标

```kotlin
val report = app.recordMetrics.calculate(AnalysisQuery(from, through, "Asia/Shanghai"))
val sources = app.recordMetrics.sources(report, offset = 0, limit = 25)
```

口径为 `record-coverage` v1，每次 1–366 天。程序按固定来源枚举范围内全部最新记录，不使用模型结果或知识数量。输出可供后续图表：

| 字段 | 口径 |
| --- | --- |
| `totalRecords` | 候选范围内最新记录 ID 数，含未知时间与交叠区间 |
| `exactDateRecords`、`daily` | 起止日期相同且在期间内的记录数，修订不重复计数 |
| `recordedDays` / `daysInScope` | 有精确日期记录的去重天数／期间天数 |
| `intervalRecords`、`overlappingRecords` | 多日区间数及其中跨查询边界的数量，不展开为每日发生 |
| `unknownTimeRecords` | 日期未定资料，单列且不进入每日记录数 |
| `missingExactIds` | 显式指定但缺失、被归档排除或在范围外的 ID |

按记录原有日期标签统计；保留查询时区，但不虚构时刻进行跨时区换算。补记录入时间、主题标签、摘要次数不作为活动发生次数。每日零值仅表示零条精确日期记录，不能解释成零睡眠、零运动或没有成长。

报告保留口径、范围、生成时刻、来源身份和代数；下钻绑定同一快照，来源变化后要求重新计算。当前不从自由文字提取健康数值，不向模型提供这些指标，也没有健康趋势解释或图表页面。
