# 开发与验证

当前代码对应 [M1 实施计划](plans/2026-10-16-local-records.md)。使用原生 Kotlin 和系统控件提供功能入口，业务与 SQLite 存储可独立验证；后续视觉设计可替换界面，不改变记录身份与保存规则。

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
```

测试报告在 `app/build/reports/tests/testDebugUnitTest/`，lint 报告在 `app/build/reports/lint-results-debug.html`。GitHub 工作流通过 [setup-android](https://github.com/android-actions/setup-android) 显式安装 SDK 命令行工具，再执行同样的测试、lint 与 APK 构建，并保存报告及调试 APK；远程运行状态需以实际 Actions 结果为准。

受限网络下，可对本次命令传入 Java 的 `http.proxyHost`、`http.proxyPort`、`https.proxyHost`、`https.proxyPort` 参数，或配置自己的 Gradle 用户设置。测试进程继承明确传入的这些代理参数；仓库不固定开发者代理地址。

## 代码入口

| 路径 | 职责 |
| --- | --- |
| `app/src/main/java/dev/tracelife/model/` | 记录、发生时间、模板、附件与变更的公共类型 |
| `app/src/main/java/dev/tracelife/storage/` | 数据库迁移、事务、修订、模板版本、附件与变更队列 |
| `app/src/main/java/dev/tracelife/templates/` | 内置复盘模板、导入预览及输入限制 |
| `app/src/main/java/dev/tracelife/MainActivity.kt` | 标准控件操作入口、后台存储调用与界面状态 |
| `app/src/test/java/dev/tracelife/` | 合成数据的功能与失败场景测试 |

新数据库格式通过明确的版本迁移升级；升级失败不能删除数据库重新建表。后续 AI 读取须另行实现受控检索接口，不能把当前提供给本地界面的仓储查询直接开放给模型。

## 设备验证与阶段边界

Robolectric 使用 Android 框架测试环境与原生 SQLite；它不等同于实体手机测试。安装、系统文件选择器、厂商备份／迁移行为，以及华为授权仍需在目标手机验证。

M1 调试版本尚无完整备份恢复，卸载或清除应用数据会失去本地资料。当前用合成资料验证功能，M2 完成可恢复备份后再扩大正式积累；真实个人资料始终不进入 Git。
