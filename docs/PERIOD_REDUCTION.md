# 分层周期草稿接口

本模块把多个[原文综合批次](SYNTHESIS_ACTIONS.md)逐层归并为有来源的周期草稿。草稿保留局部解释、不同情况和未知项，可以回查原文；它不是已经通过原文核对的正式洞察。依据[检索协议](superpowers/specs/2026-10-16-knowledge-routing-and-reliability-design.md)，实施和验证记录见[第七阶段计划](plans/2026-10-19-period-reduction.md)。新增接口在后台线程使用，页面后置。

## 从原文批次形成周期草稿

```kotlin
// 使用既有 AnalysisRequest 固定问题、日期范围、来源和预算。
val task = app.analysis.start(request)
val originals = app.periodSynthesis.process(
    task.stored.id, app.deepSeekProvider, maxBatches = 3, fragmentsPerBatch = 4,
)
// 同一任务显式继续，直至可读文字处理完成；每次仍有独立请求上限。
val reduced = app.periodReduction.process(
    task.stored.id, app.deepSeekProvider, maxRequests = 3, fanIn = 4,
)
val progress = app.periodReduction.inspect(task.stored.id)
```

`PeriodReductionProcessor` 通过原文元数据目录核对综合覆盖；尚有未处理文字时返回 `NEEDS_INPUT_PROCESSING`，不把局部结果包装为完整周期。附件、不可读资料、时间未知和跨期记录等缺口继续保留。提取覆盖与综合覆盖仍各自计算。

成功 `SYNTHESIS` 批次按台账顺序成为叶节点，包括空陈述及其未知项。每次归并 2–4 个子节点，逐层继续；同层末尾只有一个节点时携带到下一层。成功子树可复用，修改每组数量不强制重做已成功的小组。根节点必须覆盖完整成功叶清单；之后新增叶批次，旧根不会被认为已包含新增内容。

每轮默认最多 3 次请求，可配置 1–100 次；每组默认 4 个子节点，可配置 2–4 个。完整供应商请求超预算时先减少子节点数，两个仍不能容纳则停止。没有截断摘要、自动扩大预算或付费压缩重试。所有原文综合、归并、提取和核对共享同一任务的累计调用及字节额度。

失败结束本轮，下一次显式 `process` 才重试；成功输入重用已保存结果。中断后须显式 `analysis.recoverInterrupted`，不会猜测请求是否已发送。取消在下一次派发前生效。`inspect` 只返回身份、计数、依赖状态和覆盖，不调用模型，也不返回全量草稿正文。

| 状态 | 含义 |
| --- | --- |
| `NEEDS_INPUT_PROCESSING` | 还有原文文字未完成综合 |
| `READY` / `PASS_LIMIT_REACHED` | 有待归并节点；可以按当前预算显式继续 |
| `TREE_INTEGRATED` | 所有成功叶批次已进入同一个当前有效根；不表示原文语义已核对 |
| `SINGLE_BATCH` | 只有一个原文批次，直接查原提案，不增加一次归并调用 |
| `NO_TEXT` | 没有可归并的文字成果，不表示没有生活经历 |
| `DEPENDENCY_BLOCKED` | 祖先陈述已被纠正、否定、撤回或删除；不会跳过后宣称完整 |
| `BUDGET_EXHAUSTED` | 单次或任务累计预算不足，已有进度保留 |
| `RETRY_NEEDED` / `NEEDS_RECOVERY` | 调用失败或存在在途调用，需分别显式继续或恢复 |
| `STALE` / `EXPIRED` | 来源变化或任务期限已到，旧任务不能继续 |
| `CANCELLED` / `CONFLICT` / `TASK_UNAVAILABLE` | 已取消、并发变化或任务已删除 |

`leafCount` 是成功原文批次数，`originalEvidenceFragments` 按片段身份去重；重复原文不会因为多次总结而增加独立证据。完整处理与生活记录完整分别说明，`originalEvidenceVerified` 和 `counterevidenceSearchExhaustive` 始终为 `false`。

## 派生输入与依赖

低层入口为 `analysis.reduce(runId, batchKey, children, provider)`，其中 `children` 是 2–4 个 `ReductionNodeRef`。低层允许归并一个明确子集，不替调用方宣称全周期完成；完整范围判断由上述处理器完成。

新增调用目的 `REDUCTION`。每个输入固定父批次键、尝试号、完整输出摘要、请求摘要及全部祖先综合与归并陈述的反馈版本（含无反馈的版本 0）。父节点只能来自同一任务中此前成功的 `SYNTHESIS` 或 `REDUCTION` 调用；拒绝缺父、失败父节点、循环、前向引用、同组重复节点及祖先重叠。

模型每次只接收所选子节点的完整成果和未知项、依赖身份及有限来源计数／清单摘要。完整原文引用及反馈 token 留在本地台账，通过依赖图回查；高层不会再次携带全部祖先正文或引用清单。所有父节点都是依赖，不仅是最终文字选作支持的节点。范围计数由程序计算，仅说明材料覆盖，不作为成长或健康指标。

本次 `AnalysisCall.evidence` 为空，因为没有读取原文；`proposal` 也为空。响应保存为独立 `ReductionDigest(statements, unknowns)`，每条 `ReductionStatement` 的 `inputIds` 只能引用本次派生输入。DeepSeek 传输复用有限 JSON 结构，传输字段 `evidenceIds` 在此目的下只表示派生输入 ID，执行层转换后保存为 `inputIds`；不能填写祖先原文 ID。所有响应仍须通过结构、引用和输出预算校验。

无反馈或当前确认的父陈述可以参与草稿归并；确认不代表事实成立。父反馈 token 改变时旧子树失效，确认状态下可建立新节点；纠正、否定、撤回和删除会阻止该分支继续参与。生成前、成功复用、派发、写回和当前正文读取均检查依赖。同任务的反馈写入递增任务版本，阻止在途旧结果写回。反馈文字不发送到归并模型。

永久删除反馈后，相关后代草稿正文和未知项会被清理；保留依赖元数据、已消费输入额度及安全原因码。来源删除沿既有规则清理分析任务，用户独立反馈和行动仍分别保存。恢复合并当前删除台账后执行同样清理；备份若自身声明了删除却仍包含应清理草稿，会被拒绝。

## 阅读草稿和原文下钻

```kotlin
if (progress.status == ReductionProcessingStatus.TREE_INTEGRATED) {
    val draft = app.periodDrafts.open(task.stored.id, requireNotNull(progress.root))
    val body = app.periodDrafts.body(draft)
    val sources = app.periodDrafts.evidence(draft, offset = 0, limit = 25)
    val excerpt = app.periodDrafts.original(draft, sources.items.first().id)
}
```

`PeriodDraft` 是带任务版本的元数据句柄，正文经 `body` 单独读取。来源目录默认每页 25，最大 100；原文默认每次 1,024 个 Unicode 码点，最大 4,096。`start` 相对于登记片段，不能越界读取整条记录；目录包含所有祖先上下文，不只保留模型选中的支持引用。原文读取检查来源版本和片段边界，完整片段同时验证哈希。

追加任务进度、来源或反馈变化后，旧句柄需要重新打开；每次正文、目录与原文读取都执行前后检查。已经交给调用者的字符串无法撤回，调用者刷新显示时应重新调用服务。此服务供可信应用界面使用，不暴露为模型的任意读取工具。

也可使用 `analysis.reductionNode(runId, batchKey)` 查看一个当前节点的正文和直接输入身份，再按父引用逐层导航；`reductionDependencies` 只返回来源链元数据。历史任务台账仍可通过既有 `task` 查阅，其内容不能被当作当前有效草稿。

## 保存、导出与后续边界

分析任务载荷写入 v6，兼容 v1–v5；数据库仍为 v6，完整加密备份仍为 v5。完整备份保存依赖图与草稿，恢复后按原来源身份作为失效历史保存。资源上限见[数据转移接口](DATA_TRANSFER.md)，达到上限明确失败，不丢弃批次。

普通知识阅读包、原文证据包和已有周期材料 ZIP 标记 `reductionDraftsIncluded=false`。本阶段通过草稿接口读取，尚无专用草稿文件导出；不能把已有材料包误认为包含归并草稿。

草稿不进入 `claimRef`、普通 `reviewClaim`、`AutomaticOrganizer` 或 `KnowledgeLibrary.publish`。现有正式知识路径继续核对原文综合陈述；归并陈述通过专用目标身份、原文扫描、最终独立核对和明确确认进入 `publishReduction`，见[周期原文核对与发布](PERIOD_REVIEW.md)。不能把多个局部 `SUPPORTED` 直接合并为整体支持。程序指标引用使用[独立受控接口](METRIC_ANALYSIS.md)，当前归并图仍只处理原文综合叶；自动检索来源链外反例、跨任务反馈合并、自动重建和前端仍待实现。
