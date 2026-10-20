# 有界综合与行动实验接口

本阶段对多条原文生成新的对照、解释和改进假设，并保存用户明确选定的行动及后续回看。它建立在[周期复盘材料](PERIOD_REFLECTION.md)、[受控分析](CONTROLLED_ANALYSIS.md)和[核对反馈](CLAIM_REVIEW.md)之上。实施与实际验证见[第六阶段计划](plans/2026-10-17-synthesis-actions.md)。所有接口在后台线程调用；文字综合、跨批归并和确认后保存已由[主动分析页面](REFLECTION.md)接入，行动编辑和回看有独立页面。

## 综合原始资料

```kotlin
val task = app.analysis.start(AnalysisRequest(
    question = "对照本周记录的期望与实际，指出可能的影响因素和可尝试的下一步，保留反例与未知项",
    scope = AnalysisQuery(from, through, "Asia/Shanghai"),
))
val result = app.periodSynthesis.process(
    task.stored.id, app.deepSeekProvider,
    maxBatches = 3, fragmentsPerBatch = 4,
)
val progress = app.periodSynthesis.inspect(task.stored.id)
```

`PeriodSynthesisProcessor` 先枚举固定范围内的记录元数据和字段，再按稳定顺序选择未综合的原文片段。默认每轮最多 3 批、每批最多 4 个片段；可设置每轮 1–100 批、每批 1–32 个片段，单片长度由任务预算决定。[对话式回看](CONVERSATION.md)显式使用每批最多 16 项的配置。每个实际请求仍由 `ControlledAnalysis` 核算完整供应商输入、输出预留和累计调用预算。输入装不下时在派发前减少本批片段数，被省略片段仍留在未处理清单；单片仍装不下则停止并返回预算状态。

也可显式选择片段调用 `analysis.synthesize(runId, batchKey, selections, provider)`，同样固定范围、来源和实际请求预算。接口不接收摘要历史、任意附加指令或未经登记的指标。综合目前以原始文字为依据，不能用这条路径产生健康统计、改善率或成长分；程序计算的指标继续在应用材料视图并列展示。

调用目的为 `SYNTHESIS`，与 `EXTRACTION`、`REVIEW` 分别登记。只有成功综合的原文区间计入综合输入覆盖，提取成功不能替代综合；重叠引用按区间合并，不重复增加覆盖。通过结构校验只说明保存了合规输出，不证明模型理解完整或结论正确。

| `integration` | 可以据此说明什么 |
| --- | --- |
| `INCOMPLETE` | 仍有文字片段未进入成功综合调用，或任务不可继续 |
| `SINGLE_CALL_ALL_AVAILABLE_TEXT` | 某一次成功调用包含本范围全部可读取文字；仍须单列附件、不可读资料和时间缺口 |
| `MULTIPLE_BATCHES_NOT_INTEGRATED` | 所有可读文字分别进入过综合，但多个批次之间尚未统一对照 |
| `NO_TEXT` | 没有可读取文字；不代表没有生活经历或没有附件 |

这轮新增的是**有范围的新解释和对照提案**，不是对任意大小知识库的一次完整周报。跨批摘要归并已由[独立草稿接口](PERIOD_REDUCTION.md)提供；草稿整体原文核对见[周期核对接口](PERIOD_REVIEW.md)，程序指标走[独立受控接口](METRIC_ANALYSIS.md)，自动反例检索仍待实施。提取和综合都允许没有可成立的结论；资料数量不能被解释为成长程度。未知时间和跨期内容保留独立覆盖计数。

成功批次和失败尝试保存在原分析任务台账；应用重启后可继续，重复成功输入不重复请求。失败返回 `RETRY_NEEDED` 后结束本轮，下一次显式调用才重试；在途调用返回 `NEEDS_RECOVERY`，使用已有显式恢复接口处理。来源变化或期限到达后旧任务不能继续。`inspect` 不调用模型，不修改资料。

## 核对、确认与发布

所有综合提案必须为 `POSSIBLE_INTERPRETATION`；模型若返回自述类型，执行层拒绝保存该响应。使用现有 `claimRef` 定位提案，用户反馈与核对仍分开保存。

```kotlin
val batch = progress.batches.first()
val claim = batch.claims.first()
// 用户查看提案与原文后，明确通过 AnalysisFeedback 提交 CONFIRMED 或其他意见。
val reviewed = app.analysis.reviewClaim(
    task.stored.id, "review-selected-synthesis", claim,
    provider = app.deepSeekProvider,
)
// 当前反馈已确认，且最新核对 SUPPORTED 后，再按知识接口发布 INSIGHT。
```

综合提案的核对会重读**该次综合调用的全部原文片段**，不只读取模型选择为支持的引用。因此同批中未被结论选中的反例与上下文仍进入检查。运行时和任务载荷校验都要求这些引用齐全；另加上下文后总片段仍不超过 32。用户反馈版本改变后旧核对不能用于当前发布。

`AutomaticOrganizer` 能识别综合提案，未确认时保持 `AWAITING_CONFIRMATION`；确认后沿现有核对／发布规则处理。其普通 `process` 仍会继续提取任务中未提取的文字，提取与综合覆盖分开，调用者只想核对一条综合时应使用上述独立接口。已纠正、否定、撤回或删除的同身份陈述不会自动重发；跨任务语义相同的陈述合并尚未实现。

正式知识和导出继续使用原有证据与失效机制。分析载荷 v4 引入原文综合，v5 引入独立派生归并，v6 引入[周期原文核对与发布](PERIOD_REVIEW.md)，v7 引入独立指标凭据；后续版本与兼容范围见[受控分析](CONTROLLED_ANALYSIS.md)及[数据转移](DATA_TRANSFER.md)，旧载荷不能混入新调用目的。已发布洞察进入知识目录和材料报告，材料报告本身仍标 `NOT_GENERATED`，表示它只是组织已有成果，没有生成一份新的完整综合报告。

## 明确选择一个行动

```kotlin
val chosen = app.actions.create(ActionDraft(
    title = "先做一个最小练习",
    trigger = "工作日开始学习时",
    behavior = "先尝试最小练习，遇到具体问题再查资料",
    expectedSignal = "记录是否开始动手、遇到的问题和可用时间",
    reviewOn = LocalDate.of(2026, 10, 24),
    zoneId = "Asia/Shanghai",
    // 可选：关联用户采用时仍有效的知识修订，只存身份，不复制模型正文。
    origin = ActionOrigin(knowledgeId, knowledgeRevision),
), id = actionId)
```

`title`、`trigger`、`behavior` 和 `expectedSignal` 都由调用者明确提交。模型没有行动写入或自动采纳权限。`origin` 可省略；存在时，首次创建或变更关联在事务内检查该知识仍为当前有效修订。之后来源失效、被否定或删除只影响 `originState`，独立行动文字和回看结果保留。行动不是原建议正确或用户已执行的证明，现可通过[行动周期分析](ACTION_PERIOD_REFLECTION.md)作为独立类型登记取证，不混入原始记录。

行动默认 `OPEN`，保存稳定 ID 与不可变修订。相同 ID 和初始计划重复创建不追加版本，永久删除 ID 不可复用。`update(id, expectedRevision, draft)` 修改计划并保留旧修订，当前版本已变时拒绝覆盖。`get`、`history` 提供当前和历史查阅。

## 回看与下一轮

```kotlin
val next = app.actions.review(
    id = actionId,
    expectedRevision = chosen.value.revision,
    operationId = reviewOperationId,
    review = ActionReview(
        execution = ActionExecution.UNKNOWN,
        observedText = "这次没有留下足够的执行记录，暂时无法判断效果。",
        decision = ActionDecision.DEFER,
        reviewedOn = LocalDate.of(2026, 10, 24),
    ),
    nextReviewOn = LocalDate.of(2026, 10, 31),
)
val due = app.actions.list(ActionFilter(dueOnly = true))
val history = app.actions.history(actionId)
```

执行情况区分 `EXECUTED`、`NOT_EXECUTED`、`UNKNOWN`；未提交过回看也与“回看后仍未知”不同。回看日期不能在未来，观察结果必须明确填写，但可以表达无法判断。

| 决定 | 状态和下一步 |
| --- | --- |
| `CONTINUE` | 保留原做法，明确选择下一次回看日期 |
| `ADJUST` | 提交完整 `adjustedDraft`，本次回看仍绑定旧计划 |
| `DEFER` | 保留原做法，延期回看，允许资料不足 |
| `STOP` | 进入 `STOPPED`，不再列为到期行动；保留历史 |

继续、调整和暂缓的下一次回看日期须晚于本次回看及当前当地日期。每次回看提供稳定 `operationId`；相同操作重复提交返回原结果，即使之后又有修订也不会重复添加。相同操作 ID 换内容会被拒绝。停用也可调用 `stop`，仍保存独立结果和执行状态。`delete(id, expectedRevision)` 是永久删除整个行动历史并登记删除标记。

到期按每个行动自己的时区计算；列表绑定 `evaluatedAt` 和来源／分析代数，分页期间资料变化须重开列表。午夜后要查看最新到期情况重新调用 `list`，不能把旧页视为实时。分页默认 25、最大 100。

## 持久化和数据转移

数据库 v6 增加行动修订、当前指针和删除标记。修订最多 10,000 个、单载荷 128 KiB、总量 16 MiB；删除标记最多 100,000 个，超限失败，不静默截断。行动变化使分析代数递增，已建立报告、导出和恢复预览据此失效；原始记录来源代数不因行动写入变化。

完整备份 v5 保存计划、回看、原计划、全部修订和删除记录，兼容 v1–v4。恢复中双方删除标记优先，同身份目标库完整行动历史优先，旧备份不覆盖本地调整或停止状态。知识来源已不存在的行动仍可恢复其用户独立文字。阅读包单独提供当前计划，完整历史需恢复备份；筛选与省略规则见[数据转移接口](DATA_TRANSFER.md#行动保存与阅读导出)。
