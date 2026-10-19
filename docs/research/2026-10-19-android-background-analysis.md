# Android 后台增量整理调研

核验日期：2026-10-19。范围为 Android 持久后台调度、唯一工作、执行条件和测试 API；不讨论 DeepSeek 接口变化。实现接口见[后台增量整理](../BACKGROUND_ANALYSIS.md)。

## 官方事实

- Android 推荐 WorkManager 承担离开界面、进程退出或设备重启后仍应保留的工作；工作由其数据库保存，可声明网络、电量和充电条件。[持久任务说明](https://developer.android.com/develop/background-work/background-tasks/persistent)
- 唯一工作支持避免重复登记；一次工作可用 `KEEP` 保留未完成项，周期工作另有更新策略。取消工作与应用自己的业务幂等和消费限制是不同机制。[工作管理](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)
- 周期最短 15 分钟，实际执行受约束和系统优化影响。网络、低电量等条件不等于精确执行时间；因此不应把周期任务描述为固定时刻的保证。[工作请求与约束](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- 当前官方稳定版本包含 2026-09-23 发布的 2.12.0；本实现固定 2026-03-25 发布的 2.11.2。2.11 系列最低 SDK 为 23，低于项目最低 API 26；本次使用的 Worker、唯一工作和约束能力不需要升级到 2.12。[WorkManager 发布记录](https://developer.android.com/jetpack/androidx/releases/work)
- `work-testing` 提供 `WorkManagerTestInitHelper`、同步执行器和控制约束的 TestDriver；`TestWorkerBuilder` 可测试具体 Worker。官方示例中的旧版本号是示例内容，本项目依赖版本与运行库一致。[集成测试](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing)、[Worker 测试](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/worker-impl)

## 产品推导

使用唯一周期工作补扫主库可靠变更登记，保存后和应用启动时额外唤醒。WorkManager 负责设备调度，应用操作库负责明确授权、消费预留、作业进度和失败状态；周期唤醒不能成为失败请求自动付费重试的通道。

策略默认关闭，首次启用仅处理之后事件。派发前重新检查策略、凭据、来源和本机恢复代数；不通过备份迁移自动发送授权。持久 RUNNING 与跨进程文件锁结合，使意外中断变成待人工恢复。上述是本产品的保守工程选择，官方调度库不会自动提供这些产品约束。

## 验证限制

依赖兼容性以实际 Gradle 编译为准，行为验收以阶段计划记录的自动测试为准。模拟 Android 框架中的测试不能证明目标手机在厂商节电、网络切换、系统重启和后台限制下的实际执行时机。界面仍后置，也尚未进行新的真实模型调用质量评估。
