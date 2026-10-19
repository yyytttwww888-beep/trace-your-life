# 受控分析接口

本模块已经实现文字取证、请求预算、可恢复调用台账和待核对提案保存；后续已增加 [DeepSeek 适配器及自动分批处理](DEEPSEEK.md)、[陈述语义核对与用户反馈](CLAIM_REVIEW.md)。正式知识通过[独立条件发布接口](KNOWLEDGE.md)保存；可继续[自动整理与查阅周期材料](PERIOD_REFLECTION.md)，或执行[有界原文综合](SYNTHESIS_ACTIONS.md)。跨批归并见[分层周期草稿](PERIOD_REDUCTION.md)；归并陈述可继续通过[周期原文核对与发布](PERIOD_REVIEW.md)，新的[画像候选](PROFILE_CANDIDATES.md)复用原文综合与独立核对，仅生成近期／情境提案。依据是[检索与可靠性协议](superpowers/specs/2026-10-16-knowledge-routing-and-reliability-design.md)，最初实施范围见[M3 第一阶段计划](plans/2026-10-16-controlled-analysis.md)。

## 调用入口

在后台线程使用 `TraceLifeApplication.analysis`，或构造 `ControlledAnalysis(repository, clock)`。提取经 `runBatch`，核对经 `reviewClaim`，均由 `ControlledAnalysis` 统一执行预算与台账；`LifeRepository`、备份快照、任意文件读取均不向模型开放。

```kotlin
val request = AnalysisRequest(
    question = "这一周记录了哪些进展和问题？",
    scope = AnalysisQuery(
        from = LocalDate.parse("2026-10-12"),
        through = LocalDate.parse("2026-10-18"),
        zoneId = "Asia/Shanghai",
    ),
    expiresAt = Instant.parse("2026-10-19T00:00:00Z"),
)
val task = analysis.start(request)
val route = analysis.route(task.stored.id)
val page = analysis.evidencePage(task.stored.id)
val fields = analysis.fields(task.stored.id, page.page.items.first().id)
// 配置 DeepSeek KEY 后可调用 runBatch，或由 AnalysisBatchProcessor 自动选择下一批。
```

| 接口 | 返回与约束 |
| --- | --- |
| `start(request, id)` | 固定范围、源库身份及来源代数；同 ID 同计划幂等，不同计划拒绝 |
| `route(id)` | L0 任务入口、L1 关注主题、L2 范围计数和下一页大小；不含原文 |
| `evidencePage(id, offset)` | L3 有限元数据页；日期、版本、主题和时间归属，不含正文与答案 |
| `fields(id, recordId)` | L4 字段入口、码点长度与附件缺口；精确 ID 同样验证范围 |
| `runBatch(id, batchKey, selections, provider, instructions, history)` | 构建完整请求、检查预算、登记尝试、执行适配器、校验并保存提案 |
| `claimRef` / `reviewClaim` / `currentReview` | 绑定提取陈述、重新取证核对、读取与当前反馈版本匹配的意见；详见核对接口 |
| `coveragePage(id, offset)` | 分页返回文字处理覆盖、未处理附件及不可读取资料；不代表生活记录完整 |
| `task(id)` / `tasks(offset, limit)` | 查看任务、状态、调用、证据和历史提案；过期或失效状态明确显示 |
| `recoverInterrupted(id)` | 工作者重启后，把遗留在途调用转为可重试；不自动重复派发 |
| `delete(id, expectedVersion)` | 按版本删除任务与提案；保存删除标记，旧的受管备份不能恢复该任务 |

可空的 `AnalysisRequest.profileTarget` 固定画像维度与近期／情境层次；此类任务允许 `SYNTHESIS`、`REVIEW` 及维护专用 `PROFILE_RELATION`／`PROFILE_SCAN`，详见[画像候选](PROFILE_CANDIDATES.md)和[跨任务维护](PROFILE_MAINTENANCE.md)。维护计划只保存目录身份与摘要，模型比较按需读取单条参照；最终核对受完整扫描和当前历史依赖约束。普通任务保持原有行为。

计划日期均为包含端点的绝对发生日期，不用录入时间替代。完整周期的主题仅为关注提示，不排除误分类和未分类记录。`recordIds = null` 表示全部；非空 ID 集合是用户明确指定的子范围，缺失、归档排除或不在期间的 ID 由 `missingExactIds` 报告，空集合拒绝。

范围内全部区间归为 `WITHIN`，只部分重叠归为 `OVERLAP`，未填日期归为 `UNKNOWN`。后两者是候选及缺口，不能擅自计为确定发生在这个周期。记录保留其原时区，计划另有适用时区；日期粒度记录不虚构时刻或做跨时区瞬时换算。

## 片段与逐次证据登记

文字入口是 `body` 与 `answer:<questionId>`，附件入口为 `attachment:<id>`。片段范围采用 Unicode 码点，左闭右开；原文不做 trim 或归一化。SQLite 直接截取指定字段片段，避免为了读一段加载整条长记录。复盘答案携带所绑定模板版本的问题和提示，其大小同样计入输入。

每次调用保存记录 ID、修订、字段、起止位置、字段总长度、原文 SHA-256 和完整请求摘要。原文片段本身不再复制进任务表；问题、模型候选文字及未知项属于任务内容，纳入删除与备份管理。引用只能使用本次提供的证据 ID；过去其他批次读过的片段不能自动成为本次结论的依据。

只有通过结构、引用及输出大小校验的成功提取调用，其输入片段才计入“成功调用处理覆盖”。核对调用的额外上下文不增加提取覆盖。重叠片段合并计算，不重复计数；失败、在途、中断调用均不算完成。空提案也是合法输出，但不说明没有问题。覆盖完整不证明理解完整，更不代表该周所有生活事项都被记录。

附件当前不会提供给模型，始终保留独立缺口。含 NUL 的文字无法用 SQLite 文本函数安全分片，明确报告不可读取，原始记录仍保留。未读尾部、其他答案和附件不会因为记录的一个片段被读取而消失。

## 预算与适配器契约

默认每页 25 条，每片段 1,024 码点；每批 1–32 个片段。完整请求包括系统指令、额外指令、历史、范围与计数、模板问题、证据元数据和正文。默认单次输入 64 KiB / 12,000 tokens，输出预留 2,000 tokens；单任务最多 100 次尝试、累计输入 1 MiB，提案输出 32 KiB。具体可配置范围由 `AnalysisLimits` 拒绝越界值，不能省略预算后无限读取。

`AnalysisProvider.prepare` 生成实际发送的完整请求体及计量，执行器对该请求体计算字节预算和摘要；`completePrepared` 只能发送该请求，不能追加隐藏历史、工具或其他资料，并遵守 `maximumOutputTokens`。适配器 `id` 固定供应商、模型、封装及计量版本。计量明确区分 `EXACT` 和 `CONSERVATIVE_UTF8_BOUND`，后者不是实际 token 用量。DeepSeek 使用有公开 tokenizer 依据的保守上界，响应实际用量另行校验和保存，详见[预算与校验](DEEPSEEK.md)。默认接口保留用于合成测试的直接计量行为。

请求超过任一预算时拒绝派发，调用者应减小批次或留下未处理缺口；不会静默截断或扩展范围。异常文本不保存，防止供应商错误串带入完整敏感请求。输出仅允许有限数量的待核对自述或可能解释，每条都有本次引用；它没有直接修改原记录、统计或当前画像的权限。

## 恢复、失效与备份

发送前保存 `IN_FLIGHT`，成功后条件写回 `SUCCEEDED`；失败保存安全原因码。登记后崩溃无法证明外部是否收到请求，恢复为 `INTERRUPTED`，保守计入尝试和输入预算。相同批次只能以相同请求摘要和适配器重试；提供稳定幂等键，但外部服务是否支持幂等去重需适配器另行验证。成功批次重试直接读取已保存提案。

来源写入和分析进度使用不同代数。当前采用保守失效：任意来源变化都使旧任务不能继续取证或写回；新补记即使从未被引用也能触发失效。每次读取、派发前及结果写回均检查来源，写回版本检查与保存处于同一事务。已经发送给外部服务的请求无法撤回，期间发生修改或删除会阻止旧结果写回。

`expiresAt` 到达后，即使没有新记录，任务仍拒绝新取证、模型执行及当前核对结果读取；历史任务台账仍可查看。固定历史任务可不设置到期时间。滚动窗口自动重建、应用时区设置变更传播及范围级精确失效仍属于后续工作，当前不能将固定历史任务当作永久有效的“近期”分析。

删除任何记录或模板会在同一事务内清理全部分析任务及派生文字，属于首版保守策略。独立用户反馈另表保存，不随任务清理；其证据状态变为失效或缺失。永久删除反馈还会清理受该反馈影响的核对正文，保留预算台账。规则见[核对与反馈接口](CLAIM_REVIEW.md)。单独删除任务不改变来源代数，其删除标记参与恢复合并。

最多保存 100 个任务，单任务载荷 2 MiB，总载荷 16 MiB；在途任务的最大输出也预留保存容量，达到上限时可删除不再需要的任务。完整加密备份保存任务、台账、提案和任务删除标记；恢复后原来源身份和代数仍保留，任务作为失效历史，不自动重跑。阅读包暂不包含分析提案，并在清单注明。格式和并发检查见[数据转移接口](DATA_TRANSFER.md)。

## 后续验收

M3 已实现语义核对、独立纠正、条件发布和记录覆盖指标；后两者见[知识接口](KNOWLEDGE.md)。普通核对只检查明确提供的原文；画像维护已增加固定范围内逐片反例扫描及跨任务关系与反馈约束，范围外资料和语义漏判仍是明确限制。现有自动处理由调用方启动一轮，不是 Android 定时后台任务。提取和综合输出均保留候选状态，必须经独立发布检查才能进入正式知识；[自动整理器](PERIOD_REFLECTION.md)可串起提取、核对和发布。有界综合的独立输入覆盖与多批未整合状态见[综合接口](SYNTHESIS_ACTIONS.md)。[模型可引用的记录指标](METRIC_ANALYSIS.md)使用独立调用，健康指标和自动更新后置。当前接口没有新页面；Robolectric、APK 构建和真机验证分别报告。
