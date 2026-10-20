# 纵向画像与变化摘要

本接口从多个明确比较周期的原文提出可修订的长期假设，再经既有[画像维护](PROFILE_MAINTENANCE.md)检查全范围原文和历史反馈。画像变化摘要由已保存的修订确定性生成。设计依据见[画像设计](superpowers/specs/2026-10-16-long-term-personal-profile-design.md)与[纵向证据研究](research/2026-10-16-longitudinal-profile-evidence.md)；实现与验证见[后端闭环计划](plans/2026-10-19-backend-completion.md)。

## 明确建立纵向任务

应用入口 `longitudinalProfiles` 为 `LongitudinalProfileGenerator(repository, analysis, clock)`，在后台线程调用。指定一个维度及 2–12 个连续、不重叠的比较周期；周期边界必须覆盖任务全部日期范围。

```kotlin
val task = app.longitudinalProfiles.startMonthly(
    dimension = ProfileDimension.ABILITIES_AND_STRATEGIES,
    question = "在已记录的多个阶段里，哪些做法可以作为待确认的长期理解？",
    zoneId = "Asia/Shanghai",
    completedMonths = 3,
    id = stableRunId,
)
```

`startMonthly` 默认取当前月份之前三个完整日历月。调用方也可通过 `start(AnalysisRequest(...))` 指定 `ProfileAnalysisTarget(dimension, LONG_TERM, periods)`，其中每个 `ProfileComparisonPeriod` 保存起止日期。月份及至少两个周期属于比较结构和使用约定，不是人格测量阈值。两天、两个月或用户认可都不能证明某种特征永久稳定。

任务范围、周期身份和预算保存在分析载荷中，重启后保持不变。已有任务 ID 不能更换计划；资料修订或到期后须明确建立新任务。

## 有界跨期生成

```kotlin
val report = app.longitudinalProfiles.process(
    task.stored.id,
    generator = app.deepSeekProvider,
    reviewer = app.deepSeekProvider,
    maxModelRequests = 5,
    fragmentsPerBatch = 4,
)
val page = app.longitudinalProfiles.inspect(task.stored.id, limit = 25)
val detail = app.longitudinalProfiles.candidate(page, selectedClaim)
```

模型仍仅经受控分析读取本批登记原文。程序逐页读取元数据、按字段分片，并在不同周期之间选取待处理片段；每批 3–32 段，默认 4 段，生成及独立局部核对共享本轮 1–100 次请求上限和任务累计预算。预算不足时缩小批次，但保留跨期对照；最小批次仍放不下则明确停止。

后续尾部只剩一个周期或未知日期时，可以再次读取其他周期的少量固定锚点，保证请求具有跨期背景。重复锚点按原引用去重计算覆盖，不能当成新事件或新的独立支持。未知日期与跨周期记录仍处理为上下文，不能满足跨期资格。

程序要求每次纵向输入至少有两个周期中日期完全落入的来源，候选引用及 `SUPPORTED` 核对的支持引用也须覆盖至少两个周期。程序只核对日期和引用关系，不能自动证明这些记录描述独立事件，或判断语义上的长期稳定性。提示词要求保留实际日期、情境、反例、替代解释和不确定性；资料不足允许空候选。

`periods.datedRecordsWithText` 是有明确日期且有可读文字的记录数，不是独立事件数、行为频率或成长指标。没有两个有资料周期时返回 `INSUFFICIENT_PERIODS`，不发送请求。未读附件、未知日期、跨范围和不可读资料通过共享覆盖字段分别保留。

`candidates.integration` 只描述原文分批层。全部可读文字已生成并完成局部核对后，`process` 在剩余请求额度内自动进入整体整理：复用受控归并图，每次读取 2–4 个完整派生批次，逐层对照所有来源，保留空输出批次、未知项、重复锚点和反例。范围内仍有未处理文字时不能把局部根当作整体候选。

`integration` 返回纯身份的归并进度；`INTEGRATING` 表示仍在跨批整理，`INTEGRATED` 表示完整根已生成。`integratedCandidates` 返回根陈述的固定 `ClaimRef`，通过 `integratedCandidate(progress, claim)` 按需展开正文、全部原文凭据和未知项。这里的整体结果仍是待核对假设，不是正式画像。单批原文已经完整时保留原先候选，不为制造“整体”标签而额外调用模型。

归并与原文生成、局部核对共享本轮请求上限，复用成功节点，失败不自动付费重试。默认长期任务的单次输入上限为 32,000；实际供应商完整输入仍严格计量。原文、批次或祖先意见改变后旧根不能继续维护或发布。大范围不会因超过 32 段就截断生成；但最终核对的容量是独立且更严格的门槛。

`inspect` 只返回覆盖、身份和状态，`next` 继续分页，`candidate` 展开本页登记的单条提案。读取检查来源、任务版本、分析代数和时间。取消阻止后续派发，不能撤回已发请求；失败保留台账，不自动重试付费调用。中断恢复仍用 `analysis.recoverInterrupted`。

## 全范围检查后明确保存

局部核对只处理生成批次。**长期候选即使没有历史画像，也必须完成完整维护流程**，不能经普通局部核对直接发布或物化。

先使用 `profileMaintenance.begin/process`，逐项比较可用历史反馈，再沿同一固定任务范围分批扫描全部可读原文。整体候选沿用相同接口；维护计划额外固定根陈述、全部叶批次、去重原文清单和每个祖先反馈版本。最终核对重读整个生成来源链及全部被扫描为相关的片段，原文超过 32 段时返回 `RELATED_EVIDENCE_LIMIT`，预算不足时保留进度，不删除反例凑预算。不能对整体候选调用普通 `reviewClaim` 绕过这套流程。当前扫描只覆盖指定任务资料，仍不保证语义判断无漏判，也不声称穷尽所有生活反例。

用户经共享反馈明确确认后，须取得匹配确认版本的最终维护核对；随后明确选择 `NEW`、`NARROW` 或 `REPLACE`，历史约束仍适用。保存只创建或更新 `CANDIDATE`，采用通过 `personalProfile.adopt` 单独执行。个人自述、模型解释、日期覆盖、用户确认和采用状态保持独立。

## 画像变化摘要

应用入口 `profileChanges` 为 `ProfileChangeSummary(repository, clock)`。它不调用模型，也不生成新的个人判断。

```kotlin
val changes = app.profileChanges.open(
    ProfileChangesQuery(from, through, "Asia/Shanghai"),
)
val first = app.profileChanges.changes(changes, limit = 25)
val selected = first.items.first()
val detail = app.profileChanges.detail(first, selected.id, selected.revision)
```

日期按画像修订时间和查询时区筛选。目录区分创建、共享知识引用变化、维度／层次变化、采用变化及独立文字变化，同时保留明确维护操作类型。`counts` 统计操作事实，不能作为个人成长、画像准确度或行为改变的评分。同一修订可以同时改变多个字段，因此分类数量不能直接相加当作总修订数。

目录每页 1–100 项，只包含身份、固定引用和变化类型；`detail` 才读取登记修订的前后内容及共享知识有效状态。跨维度移动在原维度与新维度查询中都可查到。未改变内容的显式操作仍保留历史，字段集合可以为空。

快照绑定来源、分析代数和时间；记录、画像、反馈变化、午夜、关联有效期限或时钟回拨后须重新打开。永久删除的画像不再进入新的变化摘要，旧页失效；摘要不保存正文副本，也不重建已永久删除的内容。来源修订后仍可查看保留的历史，但其共享知识状态明确显示失效。

## 持久化边界

比较周期随分析载荷 v10 保存；旧 v1–v9 不得夹带长期目标。带长期归并的任务使用 v16；旧 v1–v15 不得夹带这一能力。维护计划的可选 `integration` 字段保留整个根的身份与依赖；没有该字段的旧计划摘要保持一致。整体画像知识使用载荷 v7，旧知识格式拒绝这一凭据。发布、查看、删除和恢复共同检查祖先反馈，永久删除祖先意见会清除依赖的机器任务及知识，旧备份不能把它们重新公开，独立用户文字按原规则保留。

变化摘要是可重建视图，不新增第二份画像主存或机器正文。完整备份仍保存已有画像修订；查看摘要无需把全画像历史传给模型。手机入口已接入，自动测试、真实模型验证和真机验证分别报告。

## 手机跨阶段整理

「关于我 → 从不同阶段认识自己」进入长期整理表单，可选择八个维度之一、最近 2–12 个完整月份和本次问题。开始前展示实际日期范围和本轮最多 5 次请求的上限；请求累计仍受固定任务预算限制。生成、核对、范围和各月份有日期的文字记录数均保存在既有分析台账中；只有一个有资料月份时显示资料不足，不发送请求。

「整理历史与进度」按任务元数据分页查找近期、情境和长期任务，可恢复此前进度。打开页面、旋转及进程重建均只读；继续在线、恢复中断和恢复归档为分开的明确操作。表单问题、选择、任务身份及页码保留，成功步骤复用。历史页每次最多检查 20 个任务，其他任务类型会略过，仍可继续下一页。

页面分别显示“整体候选”和“分批形成的线索”。完整归并后优先展示整体候选，点开进入[完整维护流程](PROFILE_MAINTENANCE.md#手机核对流程)，检查历史关系、全范围原文及反例，确认并最终核对后才提供保存或更新建议。尚未完成跨批整理时保留明确提示；长期候选不能从局部核对直接跳过维护门槛，也不会自动采用。
