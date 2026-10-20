# 陈述核对与用户反馈接口

本阶段在保存的提案上增加语义核对和独立用户反馈，沿用[受控分析接口](CONTROLLED_ANALYSIS.md)的范围、快照、预算与取证台账。实现计划见[第三阶段](plans/2026-10-17-evidence-review-and-feedback.md)。核对、用户认可、证据有效性分别管理；结果不会自动成为正式知识，须经[独立条件发布](KNOWLEDGE.md)。后续已支持[有界综合提案](SYNTHESIS_ACTIONS.md)，核对综合目标时重读该批全部原文上下文；跨批归并由[分层周期草稿](PERIOD_REDUCTION.md)提供；归并陈述的[原文核对与发布](PERIOD_REVIEW.md)另有受控流程，画像提案沿用[受控候选流程](PROFILE_CANDIDATES.md)，同样重读整批原文并保留独立确认。

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

`ClaimRef` 绑定提取批次、陈述序号、文字／类别／引用的摘要。每次核对自动重新读取该陈述全部原始引用，并验证修订、字段、片段位置与哈希一致。附加上下文或反例由调用方沿已有索引选择，仍受任务范围限制；必要证据与额外片段合计最多 32 段，不能为了通过预算而丢掉原引用。[画像维护](PROFILE_MAINTENANCE.md)另提供固定范围内的完整文字扫描，仍不承诺穷尽全部生活反例。

核对请求分别携带待核对陈述、用户反馈和本次原文；前两者不是独立原始证据。完整供应商请求在发送前计量，核对与提取共用任务调用次数和累计预算。所有实际原文重新登记到核对调用；核对读取的尾部或额外记录不会增加“提取处理覆盖”。

| 判定 | 必须保留的依据或限制 |
| --- | --- |
| `SUPPORTED` | 有本次支持引用、无反例引用及已识别问题；仍只是有限证据下的模型意见 |
| `UNSUPPORTED` | 所给证据不能支撑待核对陈述 |
| `CONTRADICTED` | 至少一个本次原文反例引用；提示词要求该证据直接否定同一对象、时段与情境下的事实 |
| `INSUFFICIENT_CONTEXT` | 保存缺口说明或上下文不足的问题类型 |

问题类型区分自述变事实、局部扩大为整体、相关变因果、遗漏反例、上下文不足及其他。支持和反例引用不能重复、重叠或指向其他调用的证据。核对接口不把用户自述升级为设备测量，不以模型自报分数表达准确率。

提示词明确区分“不能证明”与“已被反证”：缺少日期、未记录、样本少，以及不同日期的因素与结果组合，不能自动作为直接反证。程序能强制检查引用归属和结构，是否构成语义上的直接矛盾仍依赖模型与用户核对，少量案例通过不构成准确率承诺。

DeepSeek 新核对协议进一步检查意见自身是否一致：程序将原目标无损分成最多 24 个连续片段，在已计量的发送包中以 `target.parts` 提供稳定 `partId` 和原文；模型的 `checks` 须按顺序引用每个 ID 恰好一次，不复制或改写目标文字。缺项、重复、顺序错误和伪造 ID 均拒绝，引用仍须属于本次输入。新核对另给出陈述性质和具体原文字段锚点，程序拒绝不存在的引文、截短的状态／数值及控制元数据；支持项不附加未核对的生活未知。未来建议按针对性和边界判断，不以尚未执行当作拒绝理由。片段只是有界文本划分，每段中的事实、条件和顺序仍须由模型逐项判断，不保证切分即得到语义原子。

记录锚点可以引用本次提供的 `/text`，以及发生日期 `/startDate`、`/endDate`、时区 `/zoneId` 的完整值；这些字段保留原始时间含义与不确定性，不把未知日期补成确定日期，也不把记录者的自述变成已独立证实的行为。正文跨片段的引文，仅在本次已提供的同记录、同修订、同字段、同原文总码点数且位置首尾连续的片段内匹配；不允许重叠、缺口、拼接省略或未提供的邻接文字，引文须与指定凭据相交。程序把实际匹配覆盖的所有片段ID纳入支持／反证依赖，原调用台账继续保存全部已发送凭据。此操作不新增读取、不改变范围和预算，也不会增加提取处理覆盖。

提示词要求逐项先给引文与对照理由、再给片段判定，完成全部 `checks` 后再给整体 `verdict`。这个输出安排不证明模型确实按该顺序推理；解析器不把JSON键顺序当作真值门槛。日期全值、连续引文和逐段核对解决的是来源及契约问题，时间量、因果和观点归属仍可能被模型误解，不能视为语义保证。

任一非支持片段、阻断当前结论的未知或已识别问题，都不能与整体 `SUPPORTED` 同时保存；程序保留非支持理由，且不把模型的非支持意见升级为支持。该规则适用于普通、画像、指标及行动核对，原任务目标、归一后的 `ClaimReview` 和历史载荷格式不变。逐项判断本身仍可能误判，完整读取、模型支持与客观正确继续分别说明。具体供应商格式、兼容与实际复评见[DeepSeek 接入](DEEPSEEK.md)、[原质量评审](reviews/2026-10-20-complete-daily-knowledge-quality-review.md)及[语义修复复评](reviews/2026-10-20-semantic-evidence-repair-review.md)。

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

反馈身份绑定同一任务的具体陈述。[跨任务画像维护](PROFILE_MAINTENANCE.md)会逐项比较适用历史反馈，包括未物化候选的纠正、否定与撤回；关系意见不自动合并权威身份，重新考虑须明确选择并保留理由。自动重新分析及永久语义族管理仍是后续范围。已发布知识会随反馈改变失效，永久删除反馈同时清理相关知识全修订链。

## 保存、迁移和验证

从 SQLite v4 起独立保存反馈及删除标记，反馈没有对任务的级联删除。完整备份从 v3 起包含反馈和核对台账，后续格式见[数据转移接口](DATA_TRANSFER.md)。恢复同 ID 反馈时保留目标库现有决定，双方删除标记优先，恢复后的证据标为历史。知识阅读包可显式选择独立用户说明与历史，按自身保存日期及可证明主题筛选；原任务机器正文不随独立说明复制。未选择时清单说明省略数量，已发布知识仍保留其核对状态与反馈版本。

最多 10,000 条反馈，每条独立用户文字最多 2,000 字符，反馈 JSON 合计最多 4 MiB；反馈删除标记最多 100,000 个。超限整笔拒绝。反馈变更会使尚未发布的导出和旧恢复预览失效。

离线测试覆盖预算、伪造引用、长记录尾部、重试恢复、反馈竞争、来源变更／删除、永久删除反馈、迁移和旧备份防回滚。真实核对评估使用独立的显式开关，最多 5 次合成资料请求，见[DeepSeek 接入](DEEPSEEK.md)。具体执行结果在本阶段计划和开发路线记录，不把模拟模型测试视为语义质量验证。

## 归并陈述反馈

`analysisFeedback.respondReduction` 接受固定的归并陈述身份，独立保存用户决定及文字；当前有效性同时检查全部祖先反馈。父正文擦除后仍保留必要纯身份，已有用户文字可维护，但失效内容不能重新确认。归并草稿的原文扫描与最终核对使用[独立协议](PERIOD_REVIEW.md)，不进入普通 `reviewClaim`。

指标陈述仍使用 `reviewClaim`，但自动重新计算同范围的类型化凭据并拒绝附加原文；详见[受控指标分析](METRIC_ANALYSIS.md)。原文核对规则不因此放宽，指标凭据与原文片段分别管理。
