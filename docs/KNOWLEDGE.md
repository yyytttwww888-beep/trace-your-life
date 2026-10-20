# 正式知识与记录指标接口

本阶段把已核对的陈述保存为独立知识修订，提供分页目录、完整备份与分层阅读导出。设计与验收见[第四阶段计划](plans/2026-10-17-knowledge-publication.md)，前置调用见[证据核对](CLAIM_REVIEW.md)。这些接口均在后台线程使用，知识页已接入保存状态、正文摘要、经验阅读入口、已有主题、具体原文及关联行动／回看；主动分析保存见[闭环接口](REFLECTION.md)。

后续已增加[自动整理与周期复盘材料](PERIOD_REFLECTION.md)，复用本页发布规则和指标口径，提供稳定指标身份及材料／CSV 导出。

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
| `STALE` | 来源变更影响原分析范围，或迁移／恢复后无法证明旧来源仍有效；不自动重算 |
| `EXPIRED` | 适用期限已到，无需等待新记录 |
| `FEEDBACK_CHANGED` | 自身或祖先反馈版本／决定不再允许采用该副本 |
| `REVIEW_CHANGED` | 最新核对、归并完整根或必要任务证明已变化 |

目录只枚举当前指针，默认排除无效条目并返回 `omittedInvalid`；`includeInvalid = true` 显示已失效的当前修订，旧修订须用 `history` 或 `get(id, revision)`。分页状态表示 `evaluatedAt` 时刻，需要当前状态时重新 `get` 或从目录首页开始。

条目独立保存陈述、核对意见、原文片段引用、范围和期限，无需重新运行模型恢复。直接原文知识只复制本次核对的 `unknowns`，整批提取中其他记录的未知项留在任务；归并洞察另外保留完整来源链的扫描理由与未知项。原文不复制；引用仍定位记录修订、字段、Unicode 片段及哈希。知识不能充当新的原始证据；[对话式回看](CONVERSATION.md)可按有界目录读取固定知识修订作为已有理解背景，当前原文与行动证据仍独立登记，没有向模型开放任意知识正文读取工具。

知识保留原来源戳，按[受控分析的范围台账](CONTROLLED_ANALYSIS.md#恢复失效与备份)判断后续变更。范围外的无关资料变化不使已有知识失效；范围内补记、日期迁移、未知日期或归档变化仍检查完整候选范围，不能只核验模型曾引用的记录。范围不变不代表反馈、核对、期限及画像维护依赖一定有效，这些检查继续分别执行。

永久删除记录沿真实输入回执、完整统计来源、归并证明与跨任务画像维护依赖清理受影响的机器内容和知识修订链；只因范围重叠但未读取被删资料的任务进入待更新，无关知识保留。删除未使用模板不清空知识库，已被记录使用的模板仍不能删除。删除任务清理其派生知识，删除反馈清理该陈述及归并／维护后代。用户独立反馈、行动与回看、画像用户文字保留，关联状态可能变化。`app.knowledge.delete(id, expectedRevision)` 永久删除整个条目与修订链并保留删除标记，同 ID 不可复用；日常撤回使用已有反馈机制。

最多 10,000 个知识修订，单修订 JSON 256 KiB，合计 16 MiB，删除标记 100,000 个；超限明确失败。用户可通过[经验线索](EXPERIENCE_CONTINUITY.md)明确串联跨任务的补充、例外、收窄和替代；不会合并或改写原知识身份。自动同义关系建议与自动重算仍待实现。

## 手机主题阅读与后续尝试

`KnowledgeJourney` 是受信任的本地阅读服务，不是新增模型工具。手机默认包含已保存但待更新的当前修订，并分别显示已保存总数、当前有效数及待更新数；底层 `KnowledgeLibrary.index` 默认排除无效条目的契约不变。已保存身份不等于当前有效，过期、否定或来源变化不会仅因失效就变回“未保存”。旧修订仍从版本历史读取。

```kotlin
val journey = KnowledgeJourney(repository)
val topics = journey.topics()
val page = journey.page(
    KnowledgeFilter(topics = setOf("学习"), includeInvalid = true),
    KnowledgeShelf.EXPERIENCE,
)
val records = journey.records("学习", offset = 0, limit = 10)
val actions = journey.relatedActions(saved.id, offset = 0, limit = 10)
```

`EXPERIENCE` 阅读入口包含 `KNOWLEDGE_NOTE`，以及正文严格以「值得留下：」开头的 `INSIGHT`；这是导航分类，可能解释的证据类型和发布协议不变。`FINDINGS`、`OBSERVATIONS`、`ALL` 分别提供发现、记录观察与全部条目，未填写主题的内容仍可找到。知识分页绑定来源、分析代数及固定评估时刻，条目详情重新检查实际状态。

主题目录汇集原始记录、已保存知识及经验线索的已有标签，数量由程序计算；同条多主题资料仍定位同一身份。选择主题后可并列阅读保存内容和全部相关经历，经历按最近补充或修改分页，不冒充发生顺序；日期不明或区间记录如实显示。知识详情可展开核对原文，或打开分析时的具体记录修订，版本变化不以当前正文替换旧依据。[主题概况](KNOWLEDGE_DISCOVERY.md)进一步汇集已有理解、关注点、行动和经验变化，反例与关系仍须由有依据的知识和用户确认支持。

当前有效知识可进入「据此尝试一个行动」。页面展示来源参考，由用户独立填写计划、触发、做法、观察信号及回看日期；保存事务验证固定知识 ID／修订仍有效，未保存或失效提案不能伪造行动来源。知识详情显示关联行动及当前修订的回看结果，可进入行动详情与历史；行动可回到采用时的知识修订。来源失效不删除用户行动与回看。对话式回看自动定位范围内的计划及回看，沿受控[行动周期分析](ACTION_PERIOD_REFLECTION.md)区分计划与执行；独立行动复盘入口继续保留。

## 分层阅读与恢复

知识包从 `notes/index.md` 进入主题、分析月份／区间、分页列表和唯一正文，独立 ID 索引按知识 ID 哈希分片。原始记录与知识相互链接，原始证据目录不混入派生知识。

只纳入采集时有效的当前知识；本地页面可见的待更新历史不因此进入当前阅读包。采集时程序用范围台账确定来源仍有效的知识 ID／修订集合，该临时集合不进入备份或导出文件，不能由备份声明自己有效。导出另外检查反馈、核对及期限，并要求**全部核对及扫描涉及的原始记录修订**均在选定包内。设置主题筛选时，知识自身的主题也须命中。范围外、旧修订、失效或缺少原文的条目按原因计数并省略，不扩大导出范围。`EVIDENCE` 包只包含原始资料，明确省略所有知识。包内状态是 `capturedAt` 时的固定历史快照，离线文件不会随修改或时间自动更新。

完整备份包含全部知识修订、当前指针和删除台账。恢复保留目标库同身份知识链，防止旧备份回滚；替换后无法保留其原始依据时清理整链并记录永久删除。目标已知记录删除标记按实际回执、指标来源及跨任务维护依赖传播；无关知识不再因存在删除标记一并清理。已删除模板仍被待恢复记录引用时拒绝冲突备份。恢复知识保留原来源身份作为历史；重建台账可信下界，不重新认证为当前。版本与限制见[数据转移接口](DATA_TRANSFER.md)。

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

报告保留口径、范围、生成时刻、来源身份和代数；下钻绑定同一快照，来源变化后要求重新计算。当前不从自由文字提取健康数值，已可通过[受控指标分析](METRIC_ANALYSIS.md)向模型提供类型化记录覆盖凭据；[看板](DASHBOARD.md)提供记录覆盖图表，健康数据来源及健康趋势解释仍待接入验证。

## 周期归并洞察

分层草稿使用独立 `publishReduction` 入口，要求当前用户确认、完整来源扫描与最终相关原文核对。知识载荷 v2 的 `reductionProof` 保留全部扫描清单与祖先反馈；最终调用实际重读的证据单独保存。任一祖先反馈变化会影响当前知识、目录、导出及行动来源；永久删除沿所有历史依赖清理整条知识链。完整用法与限制见[周期原文核对与发布](PERIOD_REVIEW.md)。直接原文知识 v1 继续兼容；归并知识的父任务缺失时保留历史证明，但不认定为当前有效。

## 指标洞察

知识载荷 v3 增加独立 `metricEvidence`，兼容 v1／v2。指标洞察只接收已确认、最新核对支持的可能解释；发布事务重新计算完整当前范围。凭据保留数值与全部来源修订，允许零记录期间，不伪造原文引用。阅读包须完整包含指标期间和来源，恢复后的旧来源保持失效；完整用法见[受控指标分析](METRIC_ANALYSIS.md)。
