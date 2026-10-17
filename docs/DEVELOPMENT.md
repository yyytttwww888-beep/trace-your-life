# 开发与验证

当前代码包含 [M1 记录功能](plans/2026-10-16-local-records.md)、[M2 数据转移后端](plans/2026-10-16-backup-knowledge-export.md)、[M3 受控分析](plans/2026-10-16-controlled-analysis.md)、[DeepSeek 与分批整理](plans/2026-10-17-deepseek-analysis.md)、[核对与纠正](plans/2026-10-17-evidence-review-and-feedback.md)、[正式知识与记录指标](plans/2026-10-17-knowledge-publication.md)、[自动整理与周期材料](plans/2026-10-17-period-reflection.md)及[有界综合与行动实验](plans/2026-10-17-synthesis-actions.md)。按用户要求，新增能力先实现独立接口与验证，前端后续统一接入；已有 M1 使用系统控件提供基本入口。

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
| `app/src/main/java/dev/tracelife/providers/` | DeepSeek HTTPS、严格响应解析、凭据加密；配置和显式测试见[接入说明](DEEPSEEK.md) |
| `app/src/main/java/dev/tracelife/knowledge/`、`metrics/` | 正式知识发布／分页查阅、严格审核副本编码与确定性记录指标；见[接口](KNOWLEDGE.md) |
| `app/src/main/java/dev/tracelife/reports/` | 固定快照的周期复盘材料、原模板答案和引用下钻；自动编排与指标证据见[接口](PERIOD_REFLECTION.md) |
| `app/src/main/java/dev/tracelife/actions/` | 用户选定行动、版本与回看、到期列表；SQL 由 `storage/ActionStore.kt` 承担，见[接口](SYNTHESIS_ACTIONS.md) |
| `app/src/main/java/dev/tracelife/MainActivity.kt` | 标准控件操作入口、后台存储调用与界面状态 |
| `app/src/test/java/dev/tracelife/` | 合成数据的功能与失败场景测试 |

新数据库格式通过明确的版本迁移升级；当前为 v6，可从 v1–v5 升级，失败不能删除数据库重新建表。模型执行统一经过 `ControlledAnalysis`；不能把提供给本地界面的知识正文、指标查询、行动写入或备份快照直接开放给模型。DeepSeek 真实提取、核对和综合使用各自的显式开关，结果与边界见[接入说明](DEEPSEEK.md#实际验证)；普通测试与 CI 自动跳过三项，不读取真实 KEY。

## 设备验证与阶段边界

Robolectric 使用 Android 框架测试环境与原生 SQLite；它不等同于实体手机测试。安装、系统文件选择器、厂商备份／迁移行为，以及华为授权仍需在目标手机验证。

M2 后端已提供备份恢复接口，但调试 APK 尚无调用它们的页面和系统文件选择入口。手机上卸载或清除数据仍会失去未另行备份的资料；前端接入及真实恢复链路验收前，继续使用合成资料验证。真实个人资料始终不进入 Git。

M3 任务创建、分批执行、核对、反馈、正式知识发布、自动整理、记录指标、周期材料、有界综合及行动实验同样只有后端接口。自动调度、跨批全周期整合、健康指标与画像尚未实现。语义核对和正式保存都不是事实认证，模型质量和真机行为需分别验证。当前 `File` 导出需要目标文件系统支持同目录硬链接创建，详情见[发布限制](DATA_TRANSFER.md#一致性与中断)；系统文档 provider 的发布机制仍待接入。
