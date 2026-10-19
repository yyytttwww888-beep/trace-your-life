# 看板偏好接口

`TraceLifeApplication.dashboardPreferences` 提供 `DashboardPreferencesService`。接口执行本地磁盘操作，须在后台线程调用。看板计算和图表数据仍由[看板接口](DASHBOARD.md)负责；设置不会修改统计口径或产生健康数值。

```kotlin
val current = preferences.read()
val saved = preferences.save(current.revision, current.value.copy(
    window = DashboardWindow.DAYS_30,
    cards = listOf(DashboardMetric.RECORD_COVERAGE, DashboardMetric.SLEEP_DURATION),
    zoneId = "Asia/Shanghai",
))
val dashboard = preferences.open() // 使用保存的设置和当前日期
preferences.reset(saved.revision) // 显式保存默认设置
```

设置包括 7／30／90 天窗口、时区、1–3 个有序且不重复的指标、非空且不重复的关注维度和归档选项。关注维度用于未来页面导航，不筛选全局记录统计；`includeArchived` 控制当前查询。截止日期不是持久设置，`open(through)` 可显式查看历史窗口。

没有设置时 `read()` 返回版本 0 和默认值，不写库。默认窗口为 7 天、时区为设备时区、指标为睡眠／运动／饮食、关注精力与恢复、排除已归档资料；未接通的来源仍显示不可用。首次保存使用预期版本 0，之后按返回版本更新。并发修改拒绝旧版本；完全相同的有效设置是幂等读取，不增加版本。输入集合复制冻结，非法枚举、重复选项、无效时区及写入时钟回拨都被拒绝。

设置保存递增分析代数，不修改原始资料的来源代数。打开看板前后检查设置一致性及看板快照；设置改变后旧看板需要重新打开。`reset` 保存明确的新决定，旧备份不会覆盖这次重置。

主库 schema v8 的 `dashboard_preferences` 只保存单份当前设置，内部载荷 v1 严格检查字段、整数和 8 KiB 上限。完整备份 v7 保存该载荷，旧备份没有设置。恢复到新库时可以带入备份设置；目标库已有设置时，整个本地设置优先，不根据备份版本号或时间戳覆盖。设置不进入普通知识阅读包，也不包含后台联网授权和 API 密钥。恢复规则见[数据转移接口](DATA_TRANSFER.md)。

自动化行为验证在 `DashboardPreferencesTest`，覆盖重启、冲突、输入冻结、旧库升级、加密备份往返、旧格式及目标重置优先。运行结果统一记录于[四项后端计划](plans/2026-10-19-backend-completion.md#验证记录)。本阶段没有新增设置页面。
