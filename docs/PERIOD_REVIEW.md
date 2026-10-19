# 周期草稿原文核对与发布

本接口将[分层周期草稿](PERIOD_REDUCTION.md)中的固定陈述接到现有知识库。先扫描完整来源链，再独立核对全部相关原文，最后由用户明确确认后的陈述条件发布。供可信应用代码使用，尚未接入页面，不开放为模型任意读写工具。

## 一条陈述的处理流程

```kotlin
val target = app.analysis.reductionStatementRef(runId, root, index = 0)
// 用户已实际查看原陈述后，提交明确决定；不得自动生成用户确认。
app.analysisFeedback.respondReduction(
    runId, target, app.analysis.task(runId).stored.version,
    ClaimFeedbackDecision.CONFIRMED,
)
val result = app.periodReview.process(
    runId, target, app.deepSeekProvider, app.deepSeekProvider,
    maxRequests = 3, batchSize = 8,
)
if (result.status == ReductionReviewStatus.SUPPORTED) {
    val final = requireNotNull(app.analysis.currentReductionReview(runId, target))
    val knowledge = app.knowledge.publishReduction(
        runId, target, final.batchKey, app.analysis.task(runId).stored.version,
        topics = setOf("学习"),
    )
}
```

`ReductionStatementRef` 绑定根批次、尝试号、完整输出摘要、陈述序号及陈述摘要。根必须覆盖当前任务对应的全部成功 `SYNTHESIS` 或 `ACTION_SYNTHESIS` 叶节点，且已知可读文字和行动固定目录全部经过综合；新增叶使旧目标不能继续作为全周期目标。不可读资料、未读附件、未知时间等仍是单独缺口，不因完成文字处理而消失。

用户确认与模型核对分开保存。允许先核对再确认，但确认改变自己的反馈版本，之前扫描及最终意见不能复用，须重新扫描。上例先确认可减少重复调用；确认本身不表示事实成立。原文扫描与最终核对不改写原陈述，也不自动选择行动。

## 两阶段原文读取

程序沿根的全部祖先取得分别去重的记录原文与行动来源清单，包括局部综合的全部上下文，不能只追踪模型选择的支持引用。每次扫描限定记录片段与行动叶合计 1–32 个登记输入，在实际输入字节、token 及任务累计预算内重新读取原文。`REDUCTION_SCAN` 要求每片恰好一个分类：

| 分类 | 意义 |
| --- | --- |
| `SUPPORT` | 为该陈述提供局部支持，不是整体支持 |
| `COUNTEREVIDENCE` | 对原有对象、时间与情境存在直接反例 |
| `CONTEXT` | 相关条件、限制或必要上下文 |
| `IRRELEVANT` | 本次模型判断与固定陈述无关 |
| `NEEDS_CONTEXT` | 缺少必要上下文或无法确定相关性 |

分类附简短依据、问题和未知项。当前目标与反馈条件下，同片段不允许通过另一个成功批次重复扫描来替换分类；同键成功重试可复用。确认变化会形成新的核对条件，必须完整重扫。

全清单成功扫描后，`REDUCTION_REVIEW` 必须重新读取全部非 `IRRELEVANT` 记录原文和行动叶，同时接收相关分类依据、扫描问题及未知项。调用方不能自行指定最终证据子集。存在 `NEEDS_CONTEXT`、零相关片段、两类合计超过 32 个相关输入或实际输入预算不足时停止，不抽样、不截断、不自动扩大预算。扫描存在 `COUNTEREVIDENCE` 时拒绝最终 `SUPPORTED`，不能用后一次意见覆盖已有反例。

最终意见沿用 `SUPPORTED`／`UNSUPPORTED`／`CONTRADICTED`／`INSUFFICIENT_CONTEXT`。它独立判断原陈述在原有范围内是否有依据，多个局部支持不直接等于整体成立。相关性扫描仍可能分类错误；已扫描原文数量、最终重读数量和被判无关数量分别报告，不能据此保证语义正确或反例穷尽。模型没有读取任务来源链之外的库内资料、附件或现实生活中未记录的事件。行动原文保留计划、用户报告、旧计划与下一轮计划的区别；计划不能证明执行，没有回看或 UNKNOWN 不能判为失败。即使相关性扫描将全部行动判断为无关，最终请求仍携带行动周期语义标记。

## 续跑与状态

`periodReview.inspect(runId, target)` 不调用模型，只返回元数据、计数和最终意见。`process` 默认每轮最多 3 次请求、每扫描批 8 个片段；可配置 1–100 次及 1–32 个片段。扫描预算不足时缩小批次，单片仍超限则停止；原文片段边界保持不变。最终核对不能缩小相关原文集合。

| 状态 | 后续动作 |
| --- | --- |
| `SCAN_READY`／`FINAL_READY` | 继续处理尚未完成的步骤 |
| `PASS_LIMIT_REACHED`／`CANCELLED` | 保留进度，显式继续 |
| `SUPPORTED` | 模型支持；仍须当前用户确认及发布条件检查 |
| `NOT_SUPPORTED` | 查看最终意见，不自动重试或发布 |
| `CONTEXT_REQUIRED`／`NO_RELEVANT_EVIDENCE` | 保留扫描结果，补足资料或调整任务／陈述 |
| `RELATED_EVIDENCE_LIMIT`／`BUDGET_EXHAUSTED` | 现有任务无法容纳必要材料，不能截断后发布 |
| `RETRY_NEEDED`／`NEEDS_RECOVERY` | 失败后显式继续；在途调用先显式恢复 |
| `DEPENDENCY_BLOCKED` | 根范围或自己／祖先反馈已变化，重新建立当前草稿 |
| `STALE`／`EXPIRED`／`TASK_UNAVAILABLE`／`CONFLICT` | 来源变化、到期、删除或并发冲突，按对应原因重新读取／建立任务 |

低层接口为 `scanReduction(runId, key, target, receiptIds, provider)`、`reviewReduction(runId, key, target, provider)`。前者仅接受根清单中的记录或行动凭据 ID；后者由程序确定完整证据。双方共用原任务调用次数、累计输入、输出、完整准备请求和 CAS 台账。失败保留消耗，进程中断沿用 `recoverInterrupted`；不会自动恢复或无限重试。

## 发布与维护

`knowledge.publishReduction` 只保存 `INSIGHT`，正文逐字保留原陈述。归并输入 ID 仍是派生导航引用，不成为原文证据；正式陈述的证据是最终核对实际重读的记录或行动来源，完整扫描清单另存在 `reductionProof`。该证明包含目标、叶清单、全部祖先反馈、扫描调用及分类副本；单次最终读取与全部扫描分别可审计。

发布事务从原任务重建证明并比较，要求来源、期限、任务版本、完整根、最新成功最终核对、自己确认及全部祖先反馈仍匹配。依赖包括所有祖先综合与归并陈述，未曾反馈也固定为版本 0；不是只登记最终陈述选中的支持分支。读取、目录、导出和行动来源共同使用这些有效性条件。归并证明的父任务缺失时保留历史副本，但不作为当前知识或导出条目；只凭根摘要与叶身份无法独立证明祖先依赖没有遗漏。

改变父反馈使旧草稿、核对和知识失效；永久删除反馈清理受影响后代的模型正文、核对结果及知识整条历史，并保存删除墓碑。必要的根输出与陈述哈希身份保留以维护用户独立反馈；用户自己写的文字与行动计划继续存在，不被模型结果删除。备份恢复先合并删除信息再清理，旧备份不能恢复已知应删除的派生正文。已擦除或失效目标不能重新确认。

合法历史任务接近保存上限时，新增清理身份可能超过单任务字节／JSON 节点或任务总容量。此时原子清理该关联任务及其全部机器知识，写入任务墓碑，保留原记录、用户独立反馈和行动。`analysisFeedback.delete` 的 `FeedbackDeletionResult.purgedAnalysisRunId` 明确说明是否发生整任务清理；恢复结果的 `capacityPurgedAnalysisRuns` 给出数量。整任务清理后不保留逐次调用台账，但旧任务 ID 不可复用、旧备份不能复活；常规清理继续保留逐次预算。保存硬上限不扩大，结构损坏不会被当作容量不足静默跳过。

分析载荷当前写入 v11、兼容 v1–v10；知识载荷写入 v6、兼容 v1–v5。旧归并图保留原身份计算；若旧图未登记的派生祖先后来出现反馈，旧图失效。数据库与完整备份外层格式以数据转移接口为准；资源上限及导出范围见[数据转移接口](DATA_TRANSFER.md)。通用阅读包包含当前正式洞察及完整扫描来源；周期材料包仍明确省略带行动证据的知识。草稿依旧不自动混入这些包。

真实调用及自动测试范围见[第八阶段计划](plans/2026-10-19-period-review-publication.md)。[模型指标](METRIC_ANALYSIS.md)、[画像范围反例扫描](PROFILE_MAINTENANCE.md)、[后台记录整理](BACKGROUND_ANALYSIS.md)和[跨周期画像](LONGITUDINAL_PROFILE.md)已有独立接口；本周期归并核对仍只承诺扫描已登记来源链。

行动任务的 `originalActionEvents`、`scannedActionEvents`、`finalRereadActionEvents` 与记录计数分别返回；`relatedFragments`、`irrelevantFragments`、`contextRequiredFragments`、`counterevidenceFragments` 表示两类输入合计。`allOriginalsScanned` 同时检查记录和行动。知识内的 `reductionProof.originalActionEvidence` 保留完整扫描链，顶层 `actionEvidence` 仅保存最终相关输入；删除、恢复及知识包导出使用完整链。
