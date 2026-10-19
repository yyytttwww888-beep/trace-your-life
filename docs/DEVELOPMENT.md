# 开发与验证

当前代码包含 M1 记录、M2 数据转移、M3 受控整理与核对，以及 M4 [看板数据与行动回看](plans/2026-10-19-dashboard-data.md)、[画像采用与维护](plans/2026-10-19-personal-profile.md)、[受控画像候选生成](plans/2026-10-19-profile-candidates.md)、[跨任务画像维护](plans/2026-10-19-profile-maintenance.md)。新增[四项后端闭环](plans/2026-10-19-backend-completion.md)，使用固定 WorkManager 2.11.2。各阶段实现与验证见[路线进度](ROADMAP.md#本轮进度)。按用户要求，新增能力先实现独立接口与验证，前端后续统一接入；已有 M1 使用系统控件提供基本入口。

## 环境

- JDK 21（构建及 Robolectric API 36 测试）；编译字节码目标为 Java 17。
- Android SDK：`platforms;android-36`、`build-tools;35.0.0`、`platform-tools`。
- 通过 Android Studio 的 SDK Manager 或[命令行 SDK Manager](https://developer.android.com/tools/sdkmanager)安装所需包，完成许可接受。
- 在未提交的 `local.properties` 设置 `sdk.dir`，或配置标准 `ANDROID_HOME`。SDK、JDK 和机器代理地址不写入仓库。
- 使用仓库的 Gradle Wrapper，不依赖系统安装的 Gradle。首次构建需下载已固定版本的工具及依赖。

## 命令

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。连接已开启 USB 调试并完成授权的手机后，可运行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.tracelife.app/dev.tracelife.MainActivity
```

只运行模板或存储测试时使用 Gradle 的测试筛选，例如：

```bash
./gradlew testDebugUnitTest --tests 'dev.tracelife.templates.*'
./gradlew testDebugUnitTest --tests 'dev.tracelife.storage.*'
./gradlew testDebugUnitTest --tests 'dev.tracelife.analysis.*'
```

测试报告在 `app/build/reports/tests/testDebugUnitTest/`，lint 报告在 `app/build/reports/lint-results-debug.html`。GitHub 工作流通过 [setup-android](https://github.com/android-actions/setup-android) 显式安装 SDK 命令行工具，再执行同样的测试、lint 与 APK 构建，并保存报告及调试 APK；远程运行状态需以实际 Actions 结果为准。

受限网络下，可对本次命令传入 Java 的 `http.proxyHost`、`http.proxyPort`、`https.proxyHost`、`https.proxyPort` 参数，或配置自己的 Gradle 用户设置。测试进程继承明确传入的这些代理参数；仓库不固定开发者代理地址。

## 代码入口

| 路径 | 职责 |
| --- | --- |
| `app/src/main/java/dev/tracelife/model/` | 记录、发生时间、模板、附件与变更的公共类型 |
| `app/src/main/java/dev/tracelife/storage/` | 数据库迁移、事务、修订、模板版本、附件与变更队列 |
| `app/src/main/java/dev/tracelife/templates/` | 内置复盘模板、导入预览及输入限制 |
| `app/src/main/java/dev/tracelife/transfer/` | 备份加密、预览恢复、阅读包与证据包；用法见[接口文档](DATA_TRANSFER.md) |
| `app/src/main/java/dev/tracelife/analysis/` | 受控取证、完整请求预算、调用证据台账与待核对提案；见[分析接口](CONTROLLED_ANALYSIS.md)和[核对／反馈接口](CLAIM_REVIEW.md) |
| `app/src/main/java/dev/tracelife/background/` | 默认关闭的增量任务、持久配额、调度及失败恢复；见[后台分析](BACKGROUND_ANALYSIS.md) |
| `app/src/main/java/dev/tracelife/providers/` | DeepSeek HTTPS、严格响应解析、凭据加密；配置和显式测试见[接入说明](DEEPSEEK.md) |
| `app/src/main/java/dev/tracelife/knowledge/`、`metrics/` | 正式知识发布／分页查阅、严格审核副本编码与确定性记录指标；见[接口](KNOWLEDGE.md) |
| `app/src/main/java/dev/tracelife/reports/` | 固定快照的周期复盘材料、原模板答案和引用下钻；分层草稿正文与原文查看见[草稿接口](PERIOD_REDUCTION.md)；自动编排与指标证据见[接口](PERIOD_REFLECTION.md) |
| `app/src/main/java/dev/tracelife/actions/` | 用户选定行动、版本与回看、到期列表；SQL 由 `storage/ActionStore.kt` 承担，见[接口](SYNTHESIS_ACTIONS.md) |
| `app/src/main/java/dev/tracelife/dashboard/` | 固定快照的趋势、比较、来源下钻、已发布说明与全局行动；见[看板接口](DASHBOARD.md) |
| `app/src/main/java/dev/tracelife/profile/` | 画像分类、明确采用、历史、独立文字及证据定位；跨任务比较与更新见[画像维护](PROFILE_MAINTENANCE.md)，生成编排见[画像候选](PROFILE_CANDIDATES.md)；SQL 由 `storage/ProfileStore.kt` 承担，见[画像接口](PERSONAL_PROFILE.md) |
| `app/src/main/java/dev/tracelife/MainActivity.kt` | 标准控件操作入口、后台存储调用与界面状态 |
| `app/src/test/java/dev/tracelife/` | 合成数据的功能与失败场景测试 |

新数据库格式通过明确的版本迁移升级；当前为 v8，可从 v1–v7 升级，失败不能删除数据库重新建表。模型执行统一经过 `ControlledAnalysis`；不能把提供给本地界面的知识正文、指标查询、行动／画像写入或备份快照直接开放给模型。DeepSeek 真实提取、核对、综合、归并、周期原文核对、指标分析、画像候选、画像维护和行动周期联动使用各自的显式开关，结果与边界见[接入说明](DEEPSEEK.md#实际验证)；普通测试与 CI 自动跳过九项，不读取真实 KEY。

## 设备验证与阶段边界

Robolectric 使用 Android 框架测试环境与原生 SQLite；它不等同于实体手机测试。安装、系统文件选择器、厂商备份／迁移行为，以及华为授权仍需在目标手机验证。

M2 后端已提供备份恢复接口，但调试 APK 尚无调用它们的页面和系统文件选择入口。手机上卸载或清除数据仍会失去未另行备份的资料；前端接入及真实恢复链路验收前，继续使用合成资料验证。真实个人资料始终不进入 Git。

M3 任务创建、分批执行、核对、反馈、正式知识发布、自动整理、记录指标、周期材料、有界综合、分层周期草稿、周期原文核对与发布、受控指标分析及行动实验同样只有后端接口。[后台调度](BACKGROUND_ANALYSIS.md)、[跨周期长期候选](LONGITUDINAL_PROFILE.md)已实现，健康指标仍待数据源；模型指标引用见[受控指标分析](METRIC_ANALYSIS.md)。语义核对和正式保存都不是事实认证，模型质量和真机行为需分别验证。当前 `File` 导出需要目标文件系统支持同目录硬链接创建，详情见[发布限制](DATA_TRANSFER.md#一致性与中断)；系统文档 provider 的发布机制仍待接入。

M4 已有看板数据、独立导出、画像采用／维护、受控候选生成及跨任务维护接口，图表渲染与页面导航后置，关注项偏好已持久化。健康卡片不返回推测数值；可立即验证的是记录覆盖趋势、已有指标洞察、行动回看，以及固定知识版本的画像组织、失效传播和迁移。画像生成复用受控原文综合与核对；普通自动化验证不需要 DeepSeek KEY，显式真实测试有独立开关。餐食照片分析按用户要求暂缓。
