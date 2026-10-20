# 看板数据、趋势下钻与行动回看

M4 第一阶段提供看板后端和独立导出，沿用[主设计](superpowers/specs/2026-10-16-personal-knowledge-and-reflection-design.md#8-数据看板关键趋势与行动回看)。看板是现有主存的可重建视图，不产生模型调用。[偏好持久化](DASHBOARD_PREFERENCES.md)现已提供；手机页面与原生趋势图已接入，实体手机验收尚未完成；实现与验证记录见[阶段计划](plans/2026-10-19-dashboard-data.md)。工作／学习的明确输入与趋势见下方「投入、实践与成果」，其数值不由自由文本猜测。

## 建立看板

应用入口为 `TraceLifeApplication.dashboard`，也可构造 `DashboardService(repository, clock)`。这些是受信任的应用接口，须在后台线程调用；不能直接注册成模型工具。模型仍使用[受控指标分析](METRIC_ANALYSIS.md)的范围、预算和证据登记。

```kotlin
val snapshot = app.dashboard.open(
    DashboardRequest(
        window = DashboardWindow.DAYS_30,
        zoneId = "Asia/Shanghai",
        cards = listOf(DashboardMetric.RECORD_COVERAGE, DashboardMetric.SLEEP_DURATION),
    ),
)
val points = snapshot.current.points
val previousPoints = snapshot.previous.points
val comparison = snapshot.comparison
```

请求的 `cards` 为有序、不可重复的 1–3 项，默认睡眠、运动、饮食。`through` 默认取评估时刻在请求时区的当天，也可指定历史截止日；未来日期拒绝。`includeArchived` 默认为 `false`。快照冻结请求集合，修改原来的列表不会改变已经建立的看板。

| 指标 | 当前返回状态 | 数值含义 |
| --- | --- | --- |
| `SLEEP_DURATION` | `SOURCE_NOT_CONNECTED` | 尚未接通睡眠来源，`current=null` |
| `EXERCISE_MINUTES` | `SOURCE_NOT_CONNECTED` | 尚未接通运动来源，`current=null` |
| `DIET_MEALS` | `STRUCTURED_INPUT_UNAVAILABLE` | 尚无可用结构化餐次输入，`current=null` |
| `RECORD_COVERAGE` | `AVAILABLE` | 已保存记录的日期分布，复用 `record-coverage` v1 |

看板始终提供 `snapshot.current`／`previous` 资料覆盖辅助区；只有显式选中 `RECORD_COVERAGE` 时才把它加入主要卡片。该辅助区与健康卡片分开，不把主题笔记数量解释成餐次、运动天数、健康或成长分。

## 日期、口径与比较

7／30 天返回每日点，90 天按周一至周日的自然周汇总；首尾不足整周时，`from`、`through`、`days` 保存实际范围。包含当天的点标记 `inProgress=true`。原记录按自身发生日期标签归属，不按看板时区重新移动日期。

每期 `metric` 包含口径 ID／版本、来源版本、范围、去重及缺失说明，以及全部候选数、精确日期记录数、区间数、跨边界数、未知日期数、期间天数和有记录的天数。区间和未知日期不分摊到每日值；每日零值只表示没有已保存的精确日期记录，不表示当天没有活动。`intervalRecords` 包含跨边界区间，不能再加一次 `overlappingRecords`。

前期取紧邻本期之前的等长窗口，两期使用同一来源版本和口径。全部本期日期已经结束时，`comparison.state=COMPLETE_RECORDED_WINDOWS`，提供 `exactDateRecordsDelta` 与 `recordedDaysDelta`；差值仅描述已保存记录。含当天时为 `CURRENT_WINDOW_IN_PROGRESS`，两项差值为空，仅并列展示各期范围和覆盖。90 天图表的周边界可能不同，不按点序号逐周相减。未知时间记录可能同时出现在两期候选中，不能把两个总量相加当作独立事件数。

## 来源与已有说明

```kotlin
// 第一个图表点的实际贡献记录；page.nextOffset 用于继续读取。
val page = app.dashboard.sources(snapshot, pointIndex = 0, limit = 25)
val unknown = app.dashboard.sources(snapshot, category = DashboardSourceCategory.UNKNOWN_TIME)
val intervals = app.dashboard.sources(snapshot, category = DashboardSourceCategory.INTERVAL)

val record = page.items.firstOrNull()
if (record != null) {
    val fields = app.dashboard.sourceFields(snapshot, record.id)
    // 选择字段后，以 Unicode 码点分片读取；不默认加载全文。
    val textField = fields.firstOrNull { it.kind == SourceFieldKind.TEXT }
    val fragment = textField?.let {
        app.dashboard.sourceText(snapshot, record.id, it.key, start = 0, maxCodePoints = 1_024)
    }
}
val explanations = app.dashboard.insights(snapshot, limit = 25)
```

所有来源、说明、行动和标记分页的 `limit` 为 1–100，`offset` 不得为负；返回 `total` 与 `nextOffset`。`period=PREVIOUS` 可读取前期来源或说明，也适用于 `sourceFields`／`sourceText`。每期最多 10,000 条候选来源，超限明确失败，不能静默截断图表。

带 `pointIndex` 时只返回发生起止日期相同且位于该点范围内的记录，不能同时请求未知或区间类别。`ALL` 返回该期全部候选；`EXACT_DATE`、`INTERVAL`、`OVERLAPPING_INTERVAL`、`UNKNOWN_TIME` 提供独立导航。正文只允许读取该期已登记来源，单次文本最多 4,096 个 Unicode 码点。附件只有字段元数据，本模块不读取或分析图片。

`insights` 仅返回已发布、当前有效的指标洞察及其知识 ID／修订、文字和指标身份。其指标凭据必须与整期的来源、范围、归档选项、定义、来源成员和计算值完全一致；同日期的 ID 子集、部分期间或其他口径的说明不混入。没有匹配说明时为空，不触发 DeepSeek 请求。

## 全局行动与历史标记

```kotlin
val due = app.dashboard.actions(snapshot, section = DashboardActionSection.DUE)
val timeline = app.dashboard.markers(snapshot)
val item = due.items.firstOrNull()
if (item != null) {
    val plan = app.dashboard.action(snapshot, item.id)
    val review = item.latestReview?.let { app.dashboard.action(snapshot, item.id, it.revision) }
}
```

行动区始终展示当前全局行动，不随趋势日期隐藏过期计划。`DUE` 按行动当前计划自己的时区判断；`UPCOMING` 为尚未到期的开放计划，`STOPPED` 为停止计划。摘要展示当前标题、回看日期、来源状态及最近实际回看身份；完整做法、预期信号和回看观察文字通过已登记修订叶子读取，修改仍使用[行动服务](SYNTHESIS_ACTIONS.md)。

普通编辑不会抹掉历史回看：`latestExecution` 来自最新一个带回看的修订，`NOT_REVIEWED` 和用户回看后填写的 `UNKNOWN` 分别表示。`latestReview` 保留对应修订及操作身份，即使那次回看在图表期间之外仍可查阅。关联知识失效或删除时，独立行动文字保留，`originState` 如实变化。

时间线只包含本期日期内的 `ADOPTED`／`REVIEWED`：采纳按创建时刻转换到看板时区；回看保留当时被回看计划的 `reviewedOn` 日期和 `reviewedDraft.zoneId`。回看后调整计划时区不改变旧回看的归属。采纳不表示实际开始或执行；回看标记也不构成行动造成某项变化的因果证据。叶子只能定位当前修订、最近回看或当前时间线已登记的修订。

## 有效期与维护

快照固定 `source`、`analysisGeneration`、`evaluatedAt` 与 `validUntil`。每次分页及叶子读取前后校验；原始资料、知识、反馈、任务或行动发生写入后，旧看板保守失效，调用者重新 `open`。

`validUntil` 取看板时区下一午夜、各行动当前时区下一午夜、纳入说明及当前有效行动来源最早到期时刻的最小值。没有数据库写入也会因跨日或到期失效；时钟回拨至 `evaluatedAt` 之前同样拒绝。调用者展示长期停留的页面前应执行 `assertCurrent(snapshot)`，不能把保存于内存的数字当作持续有效的当前值。

## 独立导出

```kotlin
val saved = app.dataTransfer.exportDashboard(
    File(chosenDirectory, "trace-life-dashboard.zip"), snapshot,
)
// 也可传入 DashboardRequest，在导出时建立新快照。
```

`trace-life-dashboard` v2 ZIP 提供 Markdown 概览、JSON 口径与元数据、两期 CSV，以及分页来源、已发布洞察身份、全局行动摘要和时间线。v2 新增 `activities/current.json`、`previous.json` 及对应 CSV 和分页来源目录，记录工作／学习分钟、实践次数、覆盖、输入问题和成果原文字段定位。JSON `null` 与 CSV 空值均表示未填写；明确零值单独保留。成果正文仍通过完整阅读包或备份交付，本看板包不复制。原 `metrics/` 与 `sources/` 继续属于资料覆盖辅助区，不作为未接通健康指标的依据。记录正文、照片、洞察正文、行动做法、回看观察文字、独立反馈、分析任务和凭据均不在包内。

导出是固定评估时刻的历史视图，标明 `restorable=false` 和 `modelCitableReceipt=false`，不生成新的 AI 判断。格式、校验、容量与原子发布规则见[数据转移接口](DATA_TRANSFER.md#看板导出)；完整保存和换机恢复继续使用加密备份。

## 手机趋势图

「趋势」页直接展示记录数量折线图，并提供 7／30／90 天切换。7／30 天为每日点，90 天沿用自然周汇总，首尾不足一周时不补齐；图中数值完全来自 `DashboardSnapshot.current.points`，不另行计算、不把未知日期或区间记录分摊进曲线。空心点表示仍包含今天的未结束时段，零值只表示没有保存该时段的精确日期记录。

点按某个位置可打开该点的实际贡献来源，继续分页查看记录字段和原文。键盘左右键可选择时段，屏幕阅读器用户也可通过「按日期查看记录」访问同一套逐项入口。日期未知和跨多天记录另列数量与下钻入口；华为健康与餐食数据未接入时仍保持不可用，不绘制估计曲线。图表使用现有浅色／深色单色资源，不引入图表依赖。

窗口切换保留于页面重建状态；「调整时间范围」中的保存仍使用持久看板偏好。图表、原文和导出遵守同一快照有效性检查，记录或日期变化后需要刷新。新增行为验证位于 `DashboardChartActivityTest`，具体运行结果统一见[开发验证](DEVELOPMENT.md)。

## 投入、实践与成果

「趋势 → 工作与学习 → 记一次投入或实践」打开可选结构化表单。每条表示一次主要领域为工作或学习的活动，包含发生日期、自由正文、投入分钟、亲自实践次数、具体成果和方法／条件。除领域外均可选，但至少留下一项实际内容；日期可未知或跨多天。分钟为 0–1440 的整数，实践次数为 0–10000 的整数，不将计划、阅读或收藏自动算作实践。同一活动只填一次，不能在工作和学习分别重复计入相同时间。

数据仍是 `RecordDraft` 与 `record_answers`：`ActivityLogTemplate` 固定 v1 问题身份和含义，按需复用兼容模板或创建新模板，不覆盖用户改过、归档或删除的模板。无主库或完整备份格式迁移；修订、原文字段取证、归档、永久删除、加密备份和知识阅读包继承普通记录边界。旧记录继续绑定其原模板版本。完整问题清单（身份、顺序、提问、提示和意图）发生改变后，新版本不再按旧指标解释；原版本仍可计算，来源页明确列出不兼容输入。

`DashboardSnapshot.activityCurrent`／`activityPrevious` 提供独立的 `explicit-activity-v1` 口径视图，绝不复用 `record-coverage` 数字。每个领域有分钟和实践次数的按日／自然周序列、精确日期总量和各字段的填写天数。7／30 天逐日，90 天沿用当前看板自然周边界；各点保留实际天数和是否包含今天。只读取有限标量答案和成果存在标记，计算时不扫描自由正文或成果正文。

- 未填写为 `null`／断点；明确填 0 才为零。空白日不推断为没有工作、学习或实践。
- 总量仅为精确日期的已填写值之和，覆盖按该字段有填写的日期去重。一个字段缺失不会补成另一个字段的零。
- 日期未知和跨多天条目另列，不分摊到图表或本期／前期精确日期总量。未知时间条目可能同时出现在两期导航，不可合并成独立事件数。
- 每个记录 ID 只使用当前修订。不同记录重复描述同一次活动不会自动合并，页面提醒用户编辑原记录修正；不声称已经完成行为级去重。
- 非整数、超限或不兼容模板显示待修正，受影响数值不加入曲线。有效领域的另一项有效字段仍可计入；领域不明则整条不加入领域统计。
- 前期并列展示同长度窗口及各自覆盖，不计算成长分或宣称投入上升意味着能力提高。成果时间线只展示明确填写的成果文字，不统计“成果数”作为成长指标。

```kotlin
val board = app.dashboard.open(DashboardRequest(DashboardWindow.DAYS_30, "Asia/Shanghai"))
val learning = board.activityCurrent.series.single { it.domain == ActivityDomain.LEARNING }
val contributors = app.dashboard.activitySources(board,
    domain = ActivityDomain.LEARNING, measure = ActivityMeasure.MINUTES,
    pointIndex = 0, offset = 0, limit = 25)
val outcomes = app.dashboard.activitySources(board, measure = ActivityMeasure.OUTCOMES)
```

来源入口按领域、字段及图表点过滤，返回固定记录 ID／修订、发生日期和填写值；成果页按日期分页读取明确成果字段的有限预览，继续点开查看完整原文。原文走同一看板快照的 `sourceFields`／`sourceText`，可在专用表单修改兼容活动记录。记录修改、删除、归档和恢复后的旧看板不能继续读取。窗口、领域和所选指标在页面重建后保留，表单未保存输入及稳定记录身份同样保留；保存期间重建后可幂等重试。

专项行为测试位于 `ActivityMetricsTest`、`ActivityEntryActivityTest`、`ActivityTrendActivityTest`，覆盖明确零与缺失、自由文本隔离、自然周边界、固定版本、非法输入、修改／归档／删除失效、加密换库恢复、导出定位和实际页面路径。测试执行结果由统一[开发验证](DEVELOPMENT.md)记录。
