# 行动与周期复盘联动接口

本接口将用户已保存的行动计划、实际回看与同周期原始记录放在同一次受控请求中对照，提出下一周期可关注的做法。行动采用与执行仍由[行动服务](SYNTHESIS_ACTIONS.md)保存；模型只能提出待核对解释。页面、通知和自动采纳未接入。实施范围见[后端完善计划](plans/2026-10-19-backend-completion.md)。

## 创建与处理

```kotlin
val task = app.actionPeriodReflection.start(AnalysisRequest(
    question = "对照本周的行动计划、执行回看和日常记录，哪些做法值得继续、调整或暂缓？下周关注什么？",
    scope = AnalysisQuery(from, through, "Asia/Shanghai"),
))
val result = app.actionPeriodReflection.process(
    task.stored.id, app.deepSeekProvider,
    maxBatches = 3, inputsPerBatch = 16,
)
val progress = app.actionPeriodReflection.inspect(task.stored.id)
```

`ActionPeriodReflection(analysis)` 为独立编排入口，`start` 固定 `actionPeriod=true`。普通提取、指标、画像与行动复盘使用不同任务，不能通过把行动文字拼入自由指令来绕过取证。日期范围可以是周、月或明确指定的其他区间；默认每轮至多 3 批、每批至多 16 个输入，可设置 1–100 批与 1–32 个输入。

记录仍经过时间目录、字段和 Unicode 分片取得。行动先按范围建立纯身份目录，`analysis.actionPage(runId, offset)` 每页不超过任务的 `pageSize`，只返回行动 ID、修订、来源种类和哈希。模型只读取当前批明确选中的叶内容；不会收到全部行动历史或整个知识库。

需要显式选择时调用：

```kotlin
analysis.synthesizeActions(runId, batchKey, recordSelections, actionReceipts, provider)
```

`recordSelections + actionReceipts` 总计 1–32 项。一个行动来源是保存完整计划／回看的有界修订，上限沿用行动存储限制；不会在旧计划与观察之间任意截断。完整供应商请求仍受 UTF-8 字节、输入 token、输出预留和任务累计预算限制。批次装不下时减少输入项；一项仍装不下则返回 `BUDGET_EXHAUSTED`，该项不算已处理。

## 周期与证据含义

| 来源种类 | 选择口径 | 模型可读取的内容 |
| --- | --- | --- |
| `OPENING_PLAN` | 周期开始前最后保存、当时仍 `OPEN` 的计划 | 当时计划；不附带该修订上周期的回看文字 |
| `PLAN_CHANGE` | 修订保存时刻按分析时区落在周期内，而其回看日期不在周期内或没有回看 | 本次用户明确创建／修改／延期／停止的计划和状态；省略周期外回看文字 |
| `USER_REVIEW` | 用户填写的 `reviewedOn` 落在周期内，包括后来补记 | 被回看的旧计划、执行自述、观察、决定，以及有下一轮时的新计划 |

回看按保存的日期标签归属，保存时刻另行提供；不把补记日冒充执行日。同一修订的回看日期也在周期内时仅生成 `USER_REVIEW`，不重复纳入回看正文。`planStatus` 区分仍开放与停止，不能推断是否执行。行动本身没有独立的实际执行时间序列，执行情况仅是用户回看自述。行动关联知识的机器正文不进入原始行动证据。

计划只表明用户选择了一种做法；`EXECUTED`、`NOT_EXECUTED`、`UNKNOWN` 均保留其用户报告性质。没有回看与回看后仍未知分别呈现，均不能推断失败。旧计划 `reviewedPlan` 与下一轮 `nextPlan` 分开；模型不能用调整后的做法解释调整前的效果。

模型可比较原计划与报告结果、提出保持／调整／停止／暂缓的建议，但不计算成长分、完成分或未经程序定义的改善率，不把相关性变成因果。记录数量不代表成长。

## 覆盖与核对

`ActionPeriodProgress` 分开返回 `recordCoverage`、行动来源总数、成功处理的行动来源数和每批提案身份。一个行动的多个修订不等于多个独立实验，输入项数量只用于处理覆盖。

| `integration` | 含义 |
| --- | --- |
| `INCOMPLETE` | 有输入未处理，或来源／预算／任务状态阻止继续 |
| `SINGLE_CALL_ALL_AVAILABLE_INPUTS` | 某一次成功调用包含本范围全部可读原文和全部已登记行动来源 |
| `MULTIPLE_BATCHES_NOT_INTEGRATED` | 已分批读取全部输入，但这些批次尚未合成统一判断 |
| `NO_INPUTS` | 当前范围没有可读取的原文和行动来源 |

任何状态都不等于完整生活资料或模型理解无误。日期未知、跨期记录、附件和不可读资料继续单独报告。自动反例搜索完整性固定为 `false`；不能将多个局部提案包装成已经完整核对的整月结论。

成功提案均为 `POSSIBLE_INTERPRETATION`。通过已有 `analysis.reviewClaim` 核对时重新读取**该次综合全部原文和全部行动来源**，包括提案未选为支持的部分。用户确认后必须进行对应反馈版本的最新核对；只有 `SUPPORTED` 才能用 `KnowledgeLibrary.publish(..., INSIGHT, ...)` 明确发布。核对意见、用户确认和实际行动选择三者独立。

## 恢复、修改、删除与迁移

成功调用和失败尝试保存在原分析任务中，重启可继续。稳定批次身份保证成功输入不重复调用；失败返回 `RETRY_NEEDED` 并结束本轮，下次显式 `process` 才重试；在途任务使用已有显式恢复接口。取消在每次派发前检查，已消耗的请求不退款。

开始任务时固定行动范围的全部纯身份凭据。注册请求、写回成功结果与发布知识均检查同一目录；周期内新增计划、补记回看或删除使旧任务失效。未来周期的新计划不自动改变历史周期的目录。原始记录仍使用现有来源快照；修改任意记录可能保守地使旧任务失效。

永久删除行动会清理引用该行动目录的分析任务、正式知识，以及经画像维护引用它们的后代机器内容；独立用户反馈、其他行动和画像独立文字保留。完整备份保存原行动修订与分析／知识中的纯引用，不复制行动原文为额外证据副本。恢复时本地行动完整链与双方删除决定优先；若导入机器结果所依据的行动修订已缺失或正文不同，清理该结果并保留删除标记。

任务内部载荷写入 v10、兼容 v1–v9，新增 `actionPeriod`、`actionPlan`、`ACTION_SYNTHESIS` 与 `actionEvidence`；知识载荷写入 v5、兼容 v1–v4，保存固定行动目录和全部核对输入。恢复校验每个行动身份、修订、种类、范围和内容哈希。

通用知识阅读包目前不携带行动完整回看原文，因此明确省略行动复盘知识，原因计数为 `action_evidence_not_in_reading_package`；周期材料同样返回 `omittedActionEvidenceKnowledge` 并在包内登记。完整恢复备份保存这类知识及其行动证据，应用内仍可查阅。该省略规则不应被误解为通用阅读包已经提供完整行动复盘报告。
