# 开发与验证

当前代码包含 M1 记录、M2 数据转移、M3 受控整理与核对，以及 M4 [看板数据与行动回看](plans/2026-10-19-dashboard-data.md)、[画像采用与维护](plans/2026-10-19-personal-profile.md)、[受控画像候选生成](plans/2026-10-19-profile-candidates.md)、[跨任务画像维护](plans/2026-10-19-profile-maintenance.md)。新增[四项后端闭环](plans/2026-10-19-backend-completion.md)，使用固定 WorkManager 2.11.2。各阶段实现与验证见[路线进度](ROADMAP.md#本轮进度)。首轮页面已接入看板、核对与整理、知识库、行动、周期材料、画像、数据转移与设置；[主动分析页面](REFLECTION.md)已接通文字综合、分层归并与确认后核对保存；画像维护、纵向画像和行动周期分析流程页面仍后置。

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
| `app/src/main/java/dev/tracelife/MainActivity.kt` | 记录与模板入口、后台存储调用与界面状态 |
| `app/src/main/java/dev/tracelife/ui/` | `Ui` 设计系统与 `ScreenActivity` 页面基类；看板、核对与整理、知识库、行动、周期复盘、画像、备份与导出、设置页面 |
| `app/src/test/java/dev/tracelife/` | 合成数据的功能与失败场景测试 |

新数据库格式通过明确的版本迁移升级；当前为 v9，可从 v1–v8 升级，失败不能删除数据库重新建表。模型执行统一经过 `ControlledAnalysis`；不能把提供给本地界面的知识正文、指标查询、行动／画像写入或备份快照直接开放给模型。DeepSeek 真实提取、核对、综合、归并、周期原文核对、指标分析、画像候选、画像维护和行动周期联动使用各自的显式开关，结果与边界见[接入说明](DEEPSEEK.md#实际验证)；普通测试与 CI 自动跳过十一项，不读取真实 KEY。行动跨批归并、扫描、最终核对及知识包验证使用独立开关，见[本轮计划](plans/2026-10-19-action-report-export.md)。

## 设备验证与阶段边界

Robolectric 使用 Android 框架测试环境与原生 SQLite；它不等同于实体手机测试。安装、系统文件选择器、厂商备份／迁移行为，以及华为授权仍需在目标手机验证。

M2 备份恢复接口已接入备份与导出页面（含系统文件选择与恢复确认）。手机上卸载或清除数据仍会失去未另行备份的资料；真机恢复链路验收前，继续使用合成资料验证。真实个人资料始终不进入 Git。

M3 任务创建、核对、反馈与知识发布已接入核对与整理页面，知识查阅见知识库页面，周期材料查阅与导出见周期复盘页面；周期综合归并与周期原文核对发布的编排页面仍后置。[后台调度](BACKGROUND_ANALYSIS.md)策略与任务管理已接入设置页面；[跨周期长期候选](LONGITUDINAL_PROFILE.md)页面未接入。语义核对和正式保存都不是事实认证，模型质量和真机行为需分别验证。手机导出已接入私有完整文件生成与系统「另存为」，另存成功需读回核验；第三方 provider 不保证原子提交。通用 `File` 导出仍需要同目录硬链接，手机页面不使用此入口，详情见[交付与发布边界](DATA_TRANSFER.md#一致性与中断)。

M4 已有看板数据、独立导出、画像采用／维护、受控候选生成及跨任务维护接口；看板、画像采用与候选生成已接入页面，跨任务维护页面与图表渲染仍后置，关注项偏好已持久化并可在看板页面修改。健康卡片不返回推测数值；可立即验证的是记录覆盖趋势、已有指标洞察、行动回看，以及固定知识版本的画像组织、失效传播和迁移。画像生成复用受控原文综合与核对；普通自动化验证不需要 DeepSeek KEY，显式真实测试有独立开关。餐食照片分析按用户要求暂缓。

## Android 预览版

`v0.1.0-preview.1` 对应首轮前端的签名调试 APK，供个人手机验收；应用内版本为 `0.1.0`（版本号 1），包名为 `dev.tracelife.app`，最低 Android 8.0，目标 API 36。[GitHub Release](https://github.com/yyytttwww888-beep/trace-your-life/releases/tag/v0.1.0-preview.1) 提供 APK 与 SHA-256 校验文件，源码由同名 Git 标签固定。

安装：手机浏览器下载 Release 中的 `.apk`，按系统提示允许该浏览器安装应用。打开应用，在首页「更多功能」→「设置」配置自己的 DeepSeek KEY；离线记录不需要 KEY，安装包不携带开发机凭据或个人资料。

2026-10-19 执行 `testDebugUnitTest lintDebug assembleDebug` 成功，Gradle 复用了有效的已有任务输出；测试报告合计 776 项，其中 766 项通过、10 项显式联网测试默认跳过，失败与错误均为 0。lint 为 0 错误、4 项依赖升级提示；APK 签名验证通过，包内包含全部八个新增页面。未在本轮调用真实模型，尚未完成实体手机安装与操作验收。

下载包大小为 4,652,800 字节，SHA-256 为 `808df0da0d1ba88293e76edbc55a7ec04a6ca80b4567284cbd54a5cd24556832`。APK 使用本机构建的调试签名；后续覆盖安装需继续使用同一签名。不同机器或 CI 的调试签名通常不同，不能直接相互覆盖；切勿通过卸载来绕过签名冲突而丢失未备份资料。

`preview.1` 当时的页面边界（后续导出改进见下文）：新增流程页未保存重建状态，旋转屏幕、切换深浅模式或进程重建可能丢失未保存表单；原记录编辑页保留已有恢复逻辑。导出当前写入应用专属外部目录并显示路径，尚无系统「另存为／分享」入口，目标手机文件系统的硬链接支持仍待验证。其余功能范围见上方「设备验证与阶段边界」。

## 移动端页面优化验证

2026-10-19 的 `v0.1.0-preview.2` 在首轮前端基础上优化固定顶栏、侧边导航、记录列表与编辑顺序，并将筛选、用量和技术明细改为按需展开。用户原有单色配色与自适应图标继续保留；修复行动编辑及下一轮调整的预填，后台首次开启需确认发送记录给 DeepSeek 和可能费用。设计见[本轮说明](superpowers/specs/2026-10-19-mobile-ui-polish-design.md)。

执行 `assembleDebug testDebugUnitTest lintDebug`：780 项测试中 770 项通过、10 项需显式开启的真实 DeepSeek 测试跳过，失败和错误均为 0。新增四项界面行为测试，覆盖导航、筛选、行动预填和隐藏调整不误提交；原草稿恢复测试同时检查折叠后仍能恢复。首次验证发现一项新增测试错误地期待原样保存增加版本，按原有幂等契约修正测试后，全量重新执行通过。lint 为 0 错误、6 警告（4 项依赖升级、1 项资源目录版本冗余、1 项文本国际化提示）。本轮未调用真实模型。

使用官方 Android Emulator 37.2.12，Pixel 8 / API 36 / ARM64，在独立 AVD 中完成覆盖安装与启动、中文合成记录输入保存、列表及八个功能入口、深浅色、软键盘、360 × 640dp 与 1.3 倍字体的首页检查，并确认主记录草稿旋转后仍保留。后台开启提示已实际打开并取消。模拟方法及可复现命令见[Android 页面预览](ANDROID_PREVIEW.md)；这不替代实体华为手机验收，也不意味着已遍历所有后端流程。

版本号为 2，版本名 `0.1.0-preview.2`，包名不变；APK 签名验证通过，证书与 `preview.1` 相同，可覆盖安装旧预览版。APK 的 SHA-256 为 `628ed4995b3a90f4479d3b4c482e01aa8acf206fa2e0c9c64bd1a18e632b4d86`。[本版发布页](https://github.com/yyytttwww888-beep/trace-your-life/releases/tag/v0.1.0-preview.2) 提供 APK、校验文件与使用合成记录的实际页面截图。其余流程页的草稿重建恢复、系统导出分享及未接入页面仍按上面的阶段边界处理。


## 主动分析闭环验证

2026-10-20 新增[主动分析](REFLECTION.md)：首页和记录详情直接进入范围选择，显示实际进度、分类发现、原文依据与逐项确认后保存；历史支持分页和旋转恢复。知识库展示正文摘要并明确待更新入口。数据库及备份格式未改变。

执行 `testDebugUnitTest lintDebug assembleDebug` 成功：807 项测试中 796 项通过、11 项显式联网测试默认跳过，失败和错误均为 0。新增 26 项离线行为测试，覆盖编排、共享请求额度、独立反馈、原文读取、分页及页面恢复。lint 为 0 错误、11 警告（4 项依赖升级、1 项资源目录版本冗余、6 项文本国际化提示）；测试进程另有既有后台数据库 CloseGuard 释放提示，未造成用例失败。

本轮没有重新开启模拟器，页面行为使用 Robolectric 验证；实体手机与实际个人资料效果仍待用户试用。来源版本仍为全库级，自动主题识别、完整主题档案及细粒度维护仍未实现，不能把这次接通分析和保存等同于所有知识库目标完成。

真实 DeepSeek 验证使用三条合成记录执行两轮、合计 4 次请求；第一轮生成三类发现并完成核对保存，检查后将第三人称表达调整为面向记录者本人。第二轮同样生成 3 条发现，包含跨记录引用，1 条经明确确认、重新读取原文核对为 `SUPPORTED` 后保存，重新打开历史仍能定位。最终一轮综合用量为 1,488 输入／665 输出 token，核对为 2,131 输入／215 输出 token，未发生自动重试。该小样本检查了真实调用链和表达，不代表长期准确率、所有主题或手机网络／Keystore 验收。

最后的表达与范围说明调整后，再次执行 37 项受影响的编排／页面测试、lint 与 APK 构建全部通过。版本号为 3，版本名 `0.1.0-preview.3`，包名不变；签名验证通过，证书与 `preview.2` 一致。安装包为 4,655,422 字节，SHA-256 为 `32c2eaf735236fbaf8385ff839cc338df53d06af08283f7d5919be04192eba41`。[下载本版](https://github.com/yyytttwww888-beep/trace-your-life/releases/tag/v0.1.0-preview.3)。

## 人生知识库用户旅程评审

2026-10-20 使用同一 `preview.3` APK，在 Pixel 8 / Android 16 模拟器上完成合成记录输入、真实 DeepSeek 综合与核对保存，共 2 次模型请求。实际复现新增范围外记录后旧知识退出当前视图、经验类别错位，以及默认知识文档导出失败；三名 Agent 另从日常、长期知识和个人画像角度评审。完整操作、代码依据、失败边界与后续验收见[用户旅程评审](reviews/2026-10-20-user-knowledge-journey-review.md)。本轮没有修改应用或重新运行 Gradle 检查，不能将此前后端验证等同于这些手机旅程已通过。

## 知识持续积累优化验证

2026-10-20 按[实施计划](plans/2026-10-20-durable-knowledge-journey.md)完成范围维护、依赖删除、保存状态、主题阅读、经验到行动及 Android 文件交付。原评审保留修复前的观察，本节记录修复结果。

最终执行 `testDebugUnitTest` 成功：846 项中 835 项通过、11 项显式联网测试默认跳过，0 失败、0 错误。相对前版增加 39 项测试，覆盖范围与删除／迁移、私有发布与文档读回、手机主题和行动路径。首轮回归中旧全库失效／清理断言及一个文档提供方测试夹具未初始化的问题已修正，最终完整重跑通过。独立执行 `lintDebug assembleDebug` 成功，lint 0 错误、11 警告。相对文档链接与 `git diff --check` 通过。

模拟器使用保留原合成资料的 `trace_life_preview`，Pixel 8、Android 16 / API 36、Asia/Shanghai；以无窗口方式运行。覆盖安装从 schema v8 升级至 v9，旧记录和知识仍在，升级前已失效的知识没有被重新认证。应用身份文件探针证实内部与外部目录硬链接均遭 `AccessDeniedException`；新私有发布及系统另存已在同一环境跑通。

| 实际操作 | 结果与边界 |
| --- | --- |
| 重新分析 10 月 5–13 日三条合成学习／工作记录 | 真实 DeepSeek 1 次综合，348 字完整处理；明确确认后再 1 次原文核对并保存经验，共 2 次请求。保留任务难度与可用时间的替代解释；不代表总体准确率。 |
| 新增 10 月 20 日无关散步随记，再永久删除该验收随记 | 两次操作后均为「已保存 2 条 · 当前 1 条 · 待更新 1 条」：新知识持续有效，旧历史仍可见。相关补记、源删除、未知日期与跨任务清理另由 SQLite 行为测试验证。 |
| 切换经验入口、查看来源和主题 | 「经验与知识」能找到两条保存内容；新经验带入原文已有主题供用户确认。主题页是目录与经历关联，未生成长期主题概况。 |
| 从有效经验创建行动，填写执行与回看，再返回起点知识 | 知识详情显示「由此开始的尝试 · 1」及本次回看；保存真实固定来源。全部使用合成计划与观察，不代表实际生活执行。 |
| 知识包另存至系统 Downloads | 应用关闭并读回校验成功；宿主再次打开 ZIP，32,241 字节、49 个条目，CRC 正常。这个早期文件只包含当时有效范围，旧待更新知识按规则省略。 |
| 无关新增／删除、行动回看之后再次导出知识包 | 最终包 56410 字节、81 个条目，CRC 正常；清单纳入 5 条原文、1 条有效知识及 1 个行动计划，按规则省略 1 条旧待更新知识。完整行动回看历史仍在加密备份中。 |
| 强制重启后再次另存已有知识包 | 完整文件仍在「已生成的文件」，再次系统保存及校验成功，系统创建新副本。 |
| 最终 APK 覆盖安装并创建加密备份 | 备份成功保存至 Downloads 并读回校验；重新通过系统选取该文件、输入合成口令，成功解密预览 5 条记录、2 条知识、1 个行动。未执行覆盖恢复。 |

证据在仓库外 `~/Library/Caches/trace-life-tools/knowledge-optimization-20261020/`，包括应用身份文件探针、操作记录、稳定 UI XML／截图、`journey-verification.json` 与 ZIP 核验；全量测试归档在 `~/Library/Caches/trace-life-tools/durable-knowledge-20261020/`。临时 DeepSeek 凭据已从模拟器移除，自动整理保持关闭；结束后已关闭模拟器并保留合成资料。未测试华为实体手机、任意网盘 provider、长期一年容量与完整画像维护旅程。长期任务归档、跨任务经验演化和纠正继承、主题概况、普通复盘自动对照行动仍是后续范围。

版本号 4，版本名 `0.1.0-preview.4`，包名保持 `dev.tracelife.app`。APK 4,665,489 字节，SHA-256：`2ff7f2209a55803f5b6cc5e7061cfba0517fdef77e1bfeaa058616de2e915c88`。签名验证通过，证书与前版相同，可覆盖安装保留数据。[发布页](https://github.com/yyytttwww888-beep/trace-your-life/releases/tag/v0.1.0-preview.4)。
