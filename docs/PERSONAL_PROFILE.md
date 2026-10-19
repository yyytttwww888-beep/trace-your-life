# 个人画像采用与维护接口

本阶段将已有观察／洞察组织为可维护的画像条目，提供分类、用户采用、独立反馈、历史、证据定位和迁移。完整目标见[画像设计](superpowers/specs/2026-10-16-long-term-personal-profile-design.md)，本轮范围与验证见[M4 第二阶段计划](plans/2026-10-19-personal-profile.md)。新提案通过[受控画像候选生成](PROFILE_CANDIDATES.md)处理；跨任务比较、反例扫描及原条目更新见[画像维护](PROFILE_MAINTENANCE.md)。长期倾向自动推断、画像发布摘要、后台调度和前端仍待实现。

## 保存和采用

应用入口为 `TraceLifeApplication.personalProfile`，对应 `PersonalProfile(repository, clock)`。在后台线程调用；这是受信任的应用接口，不直接向模型开放。创建只接受已经发布、当前有效的 `OBSERVATION` 或 `INSIGHT`，不把收藏的 `KNOWLEDGE_NOTE` 当成个人特征。

```kotlin
val candidate = app.personalProfile.create(
    ProfileDraft(
        knowledge = ProfileKnowledgeRef(knowledgeId, knowledgeRevision),
        dimension = ProfileDimension.BEHAVIOR_AND_DECISIONS,
        layer = ProfileLayer.CONTEXTUAL_PATTERN,
    ),
    id = stableEntryId,
    userText = "我希望在后续项目中继续观察这一点。",
)
val adopted = app.personalProfile.adopt(
    candidate.value.id, candidate.value.revision, operationId,
)
```

画像条目只存固定知识 ID／修订及组织信息，陈述、适用情境和支持／反例仍由共享知识维护。知识产生新修订后，画像不会自动改绑。用户把条目放到 `LONG_TERM` 只是组织选择，不表示系统已验证长期稳定性；本阶段也不新增身份推断或人格评分。

八个维度分别为价值与方向、角色与背景、兴趣与偏好、行为与决策、能力与策略、精力与恢复、关系与协作、人生叙事与变化。层次为 `LONG_TERM`、`CONTEXTUAL_PATTERN`、`RECENT_STATE`，各自的含义见[维度与层次](superpowers/specs/2026-10-16-long-term-personal-profile-design.md#3-每个维度都区分时间与情境)。

| 操作 | 行为 |
| --- | --- |
| `create(draft, id, userText)` | 创建 `CANDIDATE`；显式稳定 ID 的同内容重试复用创建结果 |
| `update(id, expectedRevision, operationId, draft)` | 修改知识引用或组织信息；内容变化时回到候选，保留独立用户文字 |
| `adopt(...)` | 用户明确选择 `ADOPTED`，再次检查知识当前有效性 |
| `decline(...)` | 设为 `DECLINED`，表示不用于画像；不否定或删除共享知识 |
| `supersede(...)` | 设为 `SUPERSEDED`，表示退出当前采用并保留历史；不自动选择替代条目 |
| `feedback(..., userText)` | 保存独立用户补充；不修改知识陈述、证据或采用决定 |
| `delete(id, expectedRevision)` | 永久删除该画像全部修订及文字，保留删除标记；原知识保留 |

除创建、删除外的写操作携带稳定 `operationId`。同操作、同内容重试复用原修订，不追加重复记录；不同内容复用操作 ID 或使用旧预期修订会失败。仅改变组织或清空当前文字也会记录修订。同一知识 ID 在同维度、同层次只允许一个当前条目，即使已不采用，也应维护原条目；该规则不等于跨任务语义去重。

新建、换知识引用和采用在保存事务内检查依赖。来源失效后仍可不采用、标记被替代、维护独立文字和删除。独立文字限 2,000 字符，空白字符串拒绝；`feedback(..., null)` 只清空新修订中的文字，旧文字仍在历史；永久清除整个画像条目使用 `delete`。

## 当前画像和历史

```kotlin
val current = app.personalProfile.open(ProfileQuery(zoneId = "Asia/Shanghai"))
val dimensions = current.dimensions
val firstPage = app.personalProfile.entries(current, limit = 25)

// 候选、拒绝或失效条目需要显式进入管理范围。
val management = app.personalProfile.open(
    ProfileQuery(zoneId = "Asia/Shanghai", currentOnly = false),
)
val entry = app.personalProfile.entry(management, stableEntryId)
val history = app.personalProfile.history(management, stableEntryId, limit = 25)
val historical = app.personalProfile.entry(management, stableEntryId, revision = 1)
```

默认 `currentOnly=true`：只有用户已采用、关联知识为当前有效修订，且近期状态仍在窗口内的条目进入当前区。`dimension`、`layer`、`adoption` 可进一步筛选；查候选或不采用状态时同时指定 `currentOnly=false`。`get(id, revision, query)` 的默认查询包含非当前状态，适合明确 ID 的管理读取，不能直接把返回结果当作当前画像。

列表只包含组织和状态元数据，不附用户反馈或知识正文；叶子 `ProfileEntryView` 返回固定画像修订及其 `KnowledgeView`。分别查看 `value.adoption`、`isCurrentRevision`、`knowledgeState`、`recentState` 与 `isCurrent`，不要只凭“曾经采用”判断当前有效。旧知识修订、来源过期、反馈变化、知识缺失均保留明确状态。

`dimensions` 返回八个维度在本次筛选下的总条目数、当前条目数与组织记录最近修改时间。`unknown=true` 仅表示该维度没有当前可用条目，不生成一句新的个人判断，也不表示已经检查全部生活资料。

近期窗口默认 30 天，可设置 `recentDays=1..366`。以查询时区的当天为截止日，关联知识的范围与窗口有交集时为 `IN_WINDOW`；这是查看约定，不是心理测量或长期人格判定阈值。超出窗口后采用决定仍保存，条目退出当前区。长期和情境条目不受近期窗口筛选，但仍受来源、反馈及期限约束。

分页 `limit=1..100`，返回总数和 `nextOffset`；查询、条目目录和响应集合保持固定。快照绑定来源、分析代数及评估时间，每次分页／叶子读取前后校验；任何资料、知识、任务、反馈、行动或画像修改都可能使旧快照保守失效。查询时区午夜、相关知识到期及时间回拨同样拒绝旧快照，需要重新 `open`。回拨后重新打开时，未来的知识发布时间和画像更新时间也作为状态变化边界校验。界面从后台恢复时应检查 `assertCurrent(snapshot)`。

## 证据和反馈边界

具有原文引用的条目使用：

```kotlin
val fragment = app.personalProfile.evidence(
    management, stableEntryId, registeredEvidenceId,
    start = 0, maxCodePoints = 1_024,
)
```

具有程序指标凭据的条目使用：

```kotlin
val metricSources = app.personalProfile.metricSources(
    management, stableEntryId, registeredMetricId, limit = 25,
)
```

原文入口只接受关联知识中已登记的引用，单次最多 4,096 个 Unicode 码点；起点相对于该引用，不能扩大成整条原文。指标来源入口只接受该知识已登记的指标，沿用现有完整范围重算及来源分页。原文与指标是两种证据类型，不能互相替代。近期查看窗口用于画像筛选，不截掉其合法的跨期依据。

有效来源的候选条目也可以查看依据。来源失效时拒绝继续从旧引用取证；历史知识若仍保留，可以显式查看其内容及失效状态。原记录、知识或共享反馈被永久删除后，画像不缓存或复刻机器正文；独立用户文字留在画像历史中。

“不用于画像”与“原判断错误”不同：前者调用 `decline`，后者通过[共享反馈](CLAIM_REVIEW.md)纠正／否定；缩小成立范围需要新的共享陈述修订，再明确改绑画像引用。画像注释不会改写原判断。所有新模型分析仍须经过[受控检索协议](superpowers/specs/2026-10-16-knowledge-routing-and-reliability-design.md)。

## 保存和迁移

画像纳入 schema v7／逻辑备份 v6；内部画像载荷 v2 保存维护操作及独立重新考虑理由，兼容旧 v1。完整备份保存全部修订、独立文字与删除标记，恢复以双方删除标记和本地完整画像链优先，缺失知识的引用可作为不可用身份保留。普通知识阅读包增加按维度分页的 `profile/` 入口，仅引用同包已纳入的当前有效知识，独立用户文字和历史不会夹带进去；证据包省略画像。

容量为最多 10,000 个画像修订、单修订 32 KiB、总计 16 MiB、删除标记 100,000 个；历史修订占用同一额度，超限整笔失败。格式、近期导出时区和原子发布规则见[画像数据转移](DATA_TRANSFER.md#画像保存与阅读导出)。
