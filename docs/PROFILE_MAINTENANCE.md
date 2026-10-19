# 跨任务画像维护接口

本阶段对一个已生成的画像候选（包括[纵向长期候选](LONGITUDINAL_PROFILE.md)），检查历史判断和用户反馈，扫描固定范围内的原文，再提出关联与更新建议。产品目标见[画像设计](superpowers/specs/2026-10-16-long-term-personal-profile-design.md)，实施与验证见[M4 第四阶段计划](plans/2026-10-19-profile-maintenance.md)。新候选先通过[生成接口](PROFILE_CANDIDATES.md)取得；本接口不生成长期人格判断，也不会自动采用建议。

## 建立维护计划

应用入口为 `TraceLifeApplication.profileMaintenance`，类型为 `ProfileMaintenance(repository, analysis, clock)`。在后台线程调用。`runId` 须为带 `profileTarget` 的当前任务，`claim` 须属于该任务成功的原文综合。

```kotlin
val progress = app.profileMaintenance.begin(runId, claim)
val report = app.profileMaintenance.process(
    runId, claim,
    relationProvider = app.deepSeekProvider,
    scanProvider = app.deepSeekProvider,
    reviewProvider = app.deepSeekProvider,
    maxModelRequests = 5,
    fragmentsPerBatch = 1,
)
```

`begin` 保存目标陈述及适用历史目录的纯身份、版本、摘要和建立时间。目录包含同维度画像的候选、采用、不采用、被替代及失效条目，也包含尚未成为画像的纠正、否定和撤回反馈；无法确定维度的反馈保留为待检查范围。目录不复制机器正文或独立用户文字。

比较时按叶子读取一条历史描述与反馈，受每次完整输入预算限制。历史来源失效不表示旧判断或用户意见不存在；但它们仅作为控制背景，不是本次原始证据。旧正文已永久清理时明确上下文缺失，不能猜测原判断或默认为不相关。

计划在任务内按目标陈述分别保存。目录改变后旧计划失效；需要在当前范围重新建立分析任务，不能沿用部分比较结果宣称完整检查。

## 分批比较、扫描与核对

每轮按以下顺序进行，共用本轮请求上限和原任务累计预算：

1. 逐项比较历史目录，记录 `SAME`、`SUPPLEMENT`、`CONFLICT`、`UNRELATED` 或 `UNCERTAIN`。
2. 沿原任务时间／ID 范围枚举原文，按字段分片扫描支持、反例、情境及缺失上下文。
3. 全部文字扫描完成后，最终核对重读全部非无关片段及生成批次的完整原文，同时取得相关历史约束。

模型提示要求区分直接矛盾、缺少支持和不同情境；关系和扫描分类仍可能出错。不能以两次模型同意来证明结论成立。

范围内未分类资料不会被主题标签排除；时间未知、跨期、未读附件及不可读来源在覆盖中单独报告。`allAvailableTextScanned` 只说明本范围可读文字扫描完毕；`counterevidenceSearchExhaustive=false`，不表示搜索了全部生活资料或模型没有漏判。

默认每批一个片段，可显式设置 `fragmentsPerBatch=1..32`；预算不足时缩小扫描批次。最终相关原文超过 32 段、必要约束过长或完整输入超预算时明确停止，不删除反例来凑输入。原任务预算不可原地扩大，范围和预算改变需要新任务。

比较、扫描不使用候选自身的确认文字作为原始依据；用户确认后可以复用仍有效的这些步骤。最终核对必须匹配当前反馈版本。出现已识别直接反例、上下文不足或不确定关系时，不能判为支持。

`process` 是调用方显式启动的有限执行，失败不自动重试付费请求；再次调用复用已成功步骤。取消阻止后续派发，不能撤回已经发送的请求。遗留在途调用沿用 `analysis.recoverInterrupted(runId)`。此接口不被后台自动调度；新增的[后台整理](BACKGROUND_ANALYSIS.md)仅在明确启用后执行记录提取与核对。

## 分页与建议

```kotlin
val page = app.profileMaintenance.inspect(runId, claim, limit = 25)
val next = app.profileMaintenance.next(page)
val comparison = app.profileMaintenance.relation(page, page.references.first().reference)
if (page.allReferencesCompared) {
    val suggestions = app.profileMaintenance.suggestions(page)
}
```

调用 `next` 前检查 `nextOffset`；读取单条关系前确认页面非空。目录每页最多 100 项，只返回身份和分类；`relation` 展开本页登记条目的比较理由，`suggestions` 返回本页建议及是否需要明确重新考虑历史约束。

页面绑定任务版本、来源、分析变更代数及时间，变更或到期后重新读取。状态区分待建立计划、待比较、待扫描、待最终核对、可应用、需要复核、预算不足、范围或依赖失效；`READY_TO_APPLY` 仍不是用户确认或采用。

`KEEP`、`NARROW`、`REPLACE`、`REVIEW` 是模型建议。新建只在没有需要复用的已有画像时提供；不能因新候选换了措辞就创建同一判断的第二个画像。语义识别仍有误差，用户查看和纠正入口继续保留。

## 明确应用更新

用户先通过[共享反馈接口](CLAIM_REVIEW.md)确认新陈述，并取得匹配当前反馈版本的维护核对。随后明确选择操作：

```kotlin
val result = app.profileMaintenance.apply(
    runId = runId,
    claim = claim,
    expectedRunVersion = app.analysis.task(runId).stored.version,
    planId = requireNotNull(currentProgress.planId),
    action = ProfileMaintenanceAction.NARROW,
    targetProfileId = selectedProfileId,
    expectedProfileRevision = selectedProfileRevision,
    operationId = stableOperationId,
)
```

| 操作 | 行为 |
| --- | --- |
| `KEEP` | 保留指定旧画像的引用及采用决定，不把失效知识重新标为有效 |
| `NEW` | 在没有需要复用的画像时创建未采用候选；历史反馈仍需检查 |
| `NARROW` / `REPLACE` | 发布新共享洞察，在指定原画像 ID 上追加组织修订，保留历史及独立文字，回到候选 |

存在相关纠正、否定、撤回或不采用限制时，以 `NEW`、`NARROW` 或 `REPLACE` 重新发布／改绑必须明确提供 `overrideReason`；`KEEP` 保留原决定，不要求覆盖理由。这是用户操作说明，不是新独立证据，也不能绕过原文核对、范围和版本检查。操作说明与原 `userText` 分开保存，永久删除画像时一起清理；采用仍通过 `personalProfile.adopt` 单独执行。

发布共享洞察和修改画像在同一事务中完成。事务校验完整目录、来源、任务、参照、反馈和时间；容量失败不会留下部分更新。稳定操作身份支持重试，不能换身份绕过已有决定。

成功更新会改变历史目录，旧维护进度随后可能显示依赖失效；通过 `personalProfile` 读取新画像。重复应用使用原固定任务、计划及操作身份，由仓储专用幂等路径返回实际当前头，不能复用旧进度继续分析。

已有候选 `materialize` 及画像定向任务的新知识发布遇到适用历史时，也要求维护核对，不能从普通局部核对入口跳过比较和反馈约束。手工画像管理仍是明确用户操作，不向模型开放写入。

## 保存、删除与导出

分析载荷 v10 保存维护计划、关系、扫描与最终核对凭据，并严格兼容旧 v1–v9。知识内部载荷 v5 严格读取 v1–v4，画像载荷 v2 严格读取 v1；外层备份为逻辑 v7，数据库为 schema v8。长期目标及跨周期支持的额外校验见[纵向画像](LONGITUDINAL_PROFILE.md)。

执行期间历史目录或反馈变化使旧计划不可用；正式知识继续检查原始来源、共享反馈与删除依赖。自身画像改绑和采用不会让刚发布的知识立即失效。画像注释和采用状态与共享陈述真伪分别管理。

永久删除按依赖清理关联维护任务及机器派生知识，用户独立文字按既有规则保留。恢复合并本地反馈、删除状态和历史引用，不复活已清理的控制背景；缺失参照不伪装成完整目录。细节见[数据转移接口](DATA_TRANSFER.md)。

普通阅读包仍省略维护提案和独立用户文字；已确认发布的有效知识及明确采用的画像按原分层目录导出。外部旧导出不会随之后的删除自动变化。
