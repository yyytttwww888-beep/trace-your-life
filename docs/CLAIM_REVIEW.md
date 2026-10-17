# 陈述核对与用户反馈接口

本阶段在保存的提案上增加语义核对和独立用户反馈，沿用[受控分析接口](CONTROLLED_ANALYSIS.md)的范围、快照、预算与取证台账。实现计划见[第三阶段](plans/2026-10-17-evidence-review-and-feedback.md)。核对、用户认可、证据有效性分别管理；结果不会自动成为正式知识，须经[独立条件发布](KNOWLEDGE.md)。后续已支持[有界综合提案](SYNTHESIS_ACTIONS.md)，核对综合目标时重读该批全部原文上下文；跨批统一周报和画像仍待实现。

## 受控核对

以下接口在后台线程执行。`extractBatchKey` 必须指向当前任务已经成功保存且含陈述的提取批次；空提案没有可核对目标。

```kotlin
val claim = app.analysis.claimRef(runId, extractBatchKey, claimIndex = 0)
val review = app.analysis.reviewClaim(
    runId = runId,
    reviewKey = "review-1",
    claim = claim,
    additionalEvidence = listOf(EvidenceSelection(otherRecordId, "body", start = 0)),
    provider = app.deepSeekProvider,
)
val current = app.analysis.currentReview(runId, claim)
```

`ClaimRef` 绑定提取批次、陈述序号、文字／类别／引用的摘要。每次核对自动重新读取该陈述全部原始引用，并验证修订、字段、片段位置与哈希一致。附加上下文或反例由调用方沿已有索引选择，仍受任务范围限制；必要证据与额外片段合计最多 32 段，不能为了通过预算而丢掉原引用。当前没有自动穷尽反例的搜索器。

核对请求分别携带待核对陈述、用户反馈和本次原文；前两者不是独立原始证据。完整供应商请求在发送前计量，核对与提取共用任务调用次数和累计预算。所有实际原文重新登记到核对调用；核对读取的尾部或额外记录不会增加“提取处理覆盖”。

| 判定 | 必须保留的依据或限制 |
| --- | --- |
| `SUPPORTED` | 有本次支持引用、无反例引用及已识别问题；仍只是有限证据下的模型意见 |
| `UNSUPPORTED` | 所给证据不能支撑待核对陈述 |
| `CONTRADICTED` | 至少一个本次原文反例引用；提示词要求该证据直接否定同一对象、时段与情境下的事实 |
| `INSUFFICIENT_CONTEXT` | 保存缺口说明或上下文不足的问题类型 |

问题类型区分自述变事实、局部扩大为整体、相关变因果、遗漏反例、上下文不足及其他。支持和反例引用不能重复、重叠或指向其他调用的证据。核对接口不把用户自述升级为设备测量，不以模型自报分数表达准确率。

提示词明确区分“不能证明”与“已被反证”：缺少日期、未记录、样本少，以及不同日期的因素与结果组合，不能自动作为直接反证。程序能强制检查引用归属和结构，是否构成语义上的直接矛盾仍依赖模型与用户核对，少量案例通过不构成准确率承诺。

调用台账的 `purpose` 区分 `EXTRACTION` 和 `REVIEW`，核对另存目标、用户反馈版本和结构化意见。来源变化、任务到期或反馈版本变化后，旧核对不能作为当前结果；`currentReview` 对失效任务抛出失效错误，对当前任务下没有匹配反馈版本的意见返回 `null`。历史意见仍可通过任务台账查阅，并按当前状态显示。

同一 `reviewKey` 只能重试同一目标、请求、反馈版本及模型；成功调用可直接复用。反馈变化后使用新键，存在遗留在途记录时先显式 `recoverInterrupted`。失败不会自动重复付费请求，模型确认不会覆盖用户的否定或纠正。

## 用户确认、修改、否定和撤回

`TraceLifeApplication.analysisFeedback` 是用户操作入口，不向模型暴露。保存使用用户查看时的任务版本和反馈版本，阻止旧页面覆盖新决定。

```kotlin
val taskVersion = app.analysis.task(runId).stored.version
val saved = app.analysisFeedback.respond(
    runId = runId,
    claim = claim,
    expectedRunVersion = taskVersion,
    decision = ClaimFeedbackDecision.CORRECTED,
    userText = independentUserText,
)
val updated = app.analysisFeedback.update(
    id = saved.feedback.id,
    expectedVersion = saved.feedback.version,
    expectedRunVersion = saved.currentRunVersion,
    decision = ClaimFeedbackDecision.REJECTED,
    userText = independentUserText,
)
```

决策为 `CONFIRMED`、`CORRECTED`、`REJECTED` 或 `WITHDRAWN`。修改必须有用户独立输入的文字；确认本身不自动复制 AI 正文，也不改变来源性质。反馈目前保存最新状态与版本，不提供完整逐版正文历史。读取使用 `forClaim`、`get` 或分页 `list`，每页最多 100 条。

反馈写入和关联任务版本递增在同一事务中完成。模型在派发中遇到新反馈时，其迟到结果无法条件写回；反馈操作不改变原始记录，也不使原始资料变更代数递增。已有模型核对需按新反馈重新执行。

返回的 `evidenceState` 与用户决策相互独立：`CURRENT`、`STALE`、`EXPIRED` 或 `TASK_UNAVAILABLE`。来源修改、任务到期或删除后，独立用户文字保留但证据不再有效；允许维护原决定下的独立文字、否定或撤回，不能把失效的旧证据重新确认。重新分析建立当前来源的新任务，当前不会自动联网重算。

永久删除使用 `delete(id, expectedVersion)`，保留删除标记且同 ID 不可复用；日常撤回使用 `WITHDRAWN`。永久删除同时清理该反馈影响的模型核对正文，保留原调用的预算和证据台账并标记 `FEEDBACK_DELETED`；旧备份恢复也执行清理，避免重新引入用户已删除文字的派生摘录。其他独立用户文字继续保留。

反馈身份目前绑定同一任务的具体陈述。跨任务重新生成的同义陈述尚未自动合并或抑制，不能据此恢复到当前画像。核对用户改写后的新陈述、自动重新分析及跨任务语义身份均是后续范围。已发布知识会随反馈改变失效，永久删除反馈同时清理相关知识全修订链。

## 保存、迁移和验证

从 SQLite v4 起独立保存反馈及删除标记，反馈没有对任务的级联删除。完整备份从 v3 起包含反馈和核对台账，后续格式见[数据转移接口](DATA_TRANSFER.md)。恢复同 ID 反馈时保留目标库现有决定，双方删除标记优先，恢复后的证据标为历史。阅读包省略反馈正文和原任务；已发布知识保留其核对状态与反馈版本。

最多 10,000 条反馈，每条独立用户文字最多 2,000 字符，反馈 JSON 合计最多 4 MiB；反馈删除标记最多 100,000 个。超限整笔拒绝。反馈变更会使尚未发布的导出和旧恢复预览失效。

离线测试覆盖预算、伪造引用、长记录尾部、重试恢复、反馈竞争、来源变更／删除、永久删除反馈、迁移和旧备份防回滚。真实核对评估使用独立的显式开关，最多 5 次合成资料请求，见[DeepSeek 接入](DEEPSEEK.md)。具体执行结果在本阶段计划和开发路线记录，不把模拟模型测试视为语义质量验证。
