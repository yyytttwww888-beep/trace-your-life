# 日常个人知识库需求验收矩阵

本清单对照最初的[主设计](superpowers/specs/2026-10-16-personal-knowledge-and-reflection-design.md)、[长期画像设计](superpowers/specs/2026-10-16-long-term-personal-profile-design.md)、[分层检索与可靠性设计](superpowers/specs/2026-10-16-knowledge-routing-and-reliability-design.md)，以及本轮[完整收口范围](superpowers/specs/2026-10-20-complete-daily-knowledge-design.md)。用于固定需求和交付证据，避免把新页面、已有测试文件或历史通过结果当作本轮验收完成。

最新修正的程序验证与 APK 身份见[语义核对修正验证](DEVELOPMENT.md#语义核对修正验证)，模型结果见[语义核对复评](reviews/2026-10-20-semantic-evidence-repair-review.md)。已补逐项真实引文、日期与连续分片验证，并阻止未经核对的独立生活缺口输出；没有改变原始资料读取范围。第五轮 70 次真实请求中，六组自由分析的 24 条发现全部核对，23 条发布、1 条顺序错误被拒；另设的长混合句仍有 2 条错误发布及 2 次协议失败，整体画像草稿仍有顺序和细节增强。**语义验收仍未通过，不能据程序测试成功宣布自动分析可靠。** 本轮没有重新启动模拟器，实体手机仍未验证。

最新完整自动回归为1,194项：1,175通过、19项显式联网测试默认跳过，0失败／错误；lint为0错误、15警告，`preview.10` APK构建与同签名核验成功。真实模型失败与自动测试结果分别报告。

以下功能矩阵及实际操作记录固定描述 `v0.1.0-preview.9` 的历史验收，不自动代表最新 APK 的手机操作已重做：当时自动回归为1,167项，其中1,150通过、17项显式联网测试默认跳过；lint为0错误、15警告，APK已构建，并完成模拟器覆盖升级、提问、核对、保存、经验积累和系统导出。真实模型语义验收未完全通过，华为健康接入未实现。

## 功能与维护矩阵

测试列列出具有行为断言的主要测试类，位于 `app/src/test/java/dev/tracelife/`。本表所列普通测试类已在本轮 `final-full-fixed-results/` 中核对为通过；Robolectric 页面测试仍属于自动回归，不等于实际 APK 旅程或实体手机通过。真实模型专测单独记录，不能用默认跳过的用例代表已验证。模块边界与完整接口以链接文档为准。

| 原始需求／验收行为 | 已实现接口与程序边界 | 手机入口／阅读路径 | 对应行为测试 | 本轮最终结果 |
| --- | --- | --- | --- | --- |
| 个人 Android 应用；资料先可靠保存，再整理 | `LifeRepository` 保存本地版本化记录与受管附件，保留时间范围、来源和历史；[主存决策](adr/0001-local-source-of-truth.md) | 首页记录及详情、历史、归档与删除 | `storage/LifeRepositoryTest`、`ui/MainActivityTest` | 自动回归通过；APK及实体手机结果分别见下表 |
| 随记和专业日／周／事件复盘；可导入个人模板 | 内置模板及文本／Markdown 导入预览，模板版本固定旧答案含义；[模板与记录范围](superpowers/specs/2026-10-16-personal-knowledge-and-reflection-design.md) | 首页新记录、复盘与模板导入 | `templates/BuiltInTemplatesTest`、`templates/TemplateImporterTest`、记录与模板存储测试 | 自动回归通过；不由模板结构推断长期复盘效果 |
| 能主动开始分析，知道处理了什么、失败在哪里 | `ReflectionWorkflow` 持久进度、分批综合、草稿与明确保存；[主动分析](REFLECTION.md) | 首页今天／近 7 天／近 30 天／自选范围、分析历史、核对与整理 | `analysis/ReflectionWorkflowTest`、`ui/ReflectionActivityTest` | 自动回归通过；六固定例真实生成及逐条核对完成，语义问题单列；最终 APK 已完成提问→核对→保存→经验积累 |
| 使用用户配置的 DeepSeek；完整请求受控 | 密钥独立保存；`ControlledAnalysis` 固定范围、快照、调用登记、输入／输出预算、失败恢复；[受控分析](CONTROLLED_ANALYSIS.md)、[接入](DEEPSEEK.md) | 设置密钥、分析执行与恢复入口 | `providers/DeepSeekProviderTest`、`analysis/AnalysisAccountingTest`、`analysis/ControlledAnalysisBoundaryTest` | 自动回归通过；最终FLASH固定专测51次请求完成；手机行动综合缺类型问题已修复，同问题新任务成功生成、核对并保存；旧失败保留 |
| 分层索引定位，不一次读取全库；支持不同说法和较老知识 | 记录／字段／分片分页、已有理解全目录选择；语义导航仅提供检索词，不充当个人事实；[知识导航](KNOWLEDGE_DISCOVERY.md) | 查找、主题概况、提问时的范围预览和原文下钻 | `analysis/KnowledgeContextTest`、`analysis/SemanticNavigationBudgetTest`、`conversation/ConversationWorkflowTest` | 自动回归通过，含超过200项目录中的旧知识、多经验去重不挤掉独立知识、导航预算与删除依赖；不保证所有同义表达都能命中 |
| 分类可维护；同一主题的不同叫法能够连接 | `SemanticClassification` 提供需确认的分类候选；`TopicAliases` 版本化规则只拓展导航；[主题叫法](TOPIC_ALIASES.md) | 记录分类建议、主题叫法新增／修改／归档／恢复 | `knowledge/TopicAliasesTest`、`knowledge/TopicAliasRetrievalTest`、`ui/TopicAliasesActivityTest` | 自动回归通过，包含实际检索命中、归档停止拓展和备份维护；APK 已实际新增叫法并查到 6 条相关记录；分类、归档与恢复由自动回归验证 |
| 知识不只是逐条复述；经验能够积累、修正和串联 | `KnowledgeLibrary` 条件发布；经验保留补充、例外、收窄、替代及独立说明；[经验线索](EXPERIENCE_CONTINUITY.md) | 知识、主题概况、经验详情与形成过程 | `knowledge/KnowledgeLibraryTest`、`knowledge/KnowledgeContinuityTest`、`knowledge/KnowledgeDiscoveryTest` | 存储和串联自动回归通过；最终真实样本有条件变化及未来观察，也有已发布时长误读，深度质量未完全通过 |
| 直接提问三个月的方法变化与反复问题，支持追问 | `ConversationWorkflow` 规划显式范围、选择有限已有理解及行动上下文、分批回看；追问身份与问题持久化；[对话式回看](CONVERSATION.md) | 回看／提问、范围确认、回答、追问与依据 | `conversation/ConversationWorkflowTest`、`conversation/ConversationActionDependencyTest`、`analysis/ConversationContextTest`、`ui/ConversationActivityTest` | 自动回归通过；真实六例经正式提问入口运行，不能据此证明任意三个月问题准确；最终 APK 同问题处理 372 字及 2 份行动材料并完成核对保存 |
| 当前目标、阶段与关注点有稳定入口 | `PersonalOverview` 与固定模板用户原话；汇集有效采用画像、近期知识、经验、行动和待更新数；[个人概览](PERSONAL_OVERVIEW.md) | 此刻的我、原始记录、各节完整目录；阅读包 `overview.md` | `overview/PersonalOverviewTest`、`ui/OverviewActivityTest`、`transfer/KnowledgePublicationExportTest` | 自动回归通过；APK 已编辑当前方向、打开来源并返回概览；最终阅读包包含概览 |
| 长期个人画像有维度、证据、反例和采用历史 | 八维／长期／情境／近期分层；候选生成、跨期整合、原文扫描、历史意见约束、明确采用；[画像](PERSONAL_PROFILE.md)、[纵向画像](LONGITUDINAL_PROFILE.md)、[维护](PROFILE_MAINTENANCE.md) | 个人画像、跨阶段候选、维护、变化摘要、原文 | `profile/PersonalProfileTest`、`profile/LongitudinalProfileGeneratorTest`、`profile/ProfileMaintenanceTest`、`analysis/ProfileMaintenanceProtocolTest`、`ui/ProfileFlowActivityTest` | 自动回归通过；最终真实两叶→整体保留四个事件和反例，但整体草稿将共现升级为因果。未运行整体最终维护／发布／采用，语义未完全通过 |
| 问题能转为下一步行动，并回看是否执行、是否有效 | 行动计划、执行与观察分别保存；固定计划和回看修订可进入周期分析；[综合与行动](SYNTHESIS_ACTIONS.md)、[行动周期分析](ACTION_PERIOD_REFLECTION.md) | 行动实验、回看、行动复盘及证据 | `actions/ActionExperimentsTest`、`actions/ActionPeriodReflectionTest`、`actions/ActionReflectionWorkflowTest`、`transfer/ActionKnowledgeExportTest` | 自动回归通过；旧失败完整保留，修后 APK 成功读取已有行动及回看并生成、核对、保存；本轮未新增行动，不能算新行动创建旅程 |
| 持续整理，不必每次人工重建周期任务 | `IncrementalOrganizer`、`RecurringAnalysisPlanner` 默认关闭；周／月草稿、月度指定维度候选共享持久额度、暂停、明确恢复和授权撤销；[后台分析](BACKGROUND_ANALYSIS.md) | 设置自动整理与周期选项、待查看、准确跳转原任务 | `background/RecurringAnalysisTest`、`background/IncrementalOrganizerTest`、`background/IncrementalWorkerTest`、`ui/MaintenanceInboxActivityTest`、`ui/MaintenanceInboxRefreshTest` | 自动回归通过；真实厂商节电、重启和连续周／月调度未验证，不能以测试时钟代替 |
| 看板展示关键趋势，缺失值不当作零，不以记录数判断成长 | `ActivityMetrics` 程序计算明确填写的工作／学习分钟和实践次数；成果保留原话；7／30 天按日、90 天按周；[看板](DASHBOARD.md) | 记录投入与实践、看板、图表点选来源、成果时间线 | `metrics/ActivityMetricsTest`、`dashboard/DashboardServiceTest`、`ui/ActivityEntryActivityTest`、`ui/ActivityTrendActivityTest`、`transfer/DashboardArchiveTest` | 自动回归通过；仅代表明确填写的投入与实践指标，设备健康趋势尚未接入；APK 已记录学习 35 分钟／1 次实践，完成趋势切换、原文下钻与成果查看 |
| 结论经过原文核对，用户确认不等于事实证明 | 机器意见、用户确认／纠正／否定分别登记；程序固定目标片段 ID，完整核对并检查局部与整体意见一致性；发布检查当前证据、任务和反馈，文本已提供与语义成立分开；[核对](CLAIM_REVIEW.md)、[质量](ANALYSIS_QUALITY.md) | 结果逐项核对、原文、纠正／否定、保存与缺口说明 | `analysis/SemanticReviewTest`、`analysis/AnalysisFeedbackTest`、`analysis/AnalysisInputCoverageTest`、`analysis/ControlledReductionReviewTest`、`providers/DeepSeekReviewProtocolTest`、真实模型固定案例 | 程序自动回归通过；最终自由生成23条核对、21条发布中至少1条明确误读，另有2条合理建议／假设被拒绝和unknowns错误；**语义未完全通过** |
| 修改、归档、删除、纠正会影响相关结论；不误伤无关范围 | 来源范围登记、依赖闭包、记录与任务删除身份、恢复栅栏；别名配置删除不使无关生活范围失效；[检索可靠性](superpowers/specs/2026-10-16-knowledge-routing-and-reliability-design.md)、[数据转移](DATA_TRANSFER.md) | 原始记录维护、待更新知识、任务归档、独立反馈 | `analysis/ControlledAnalysisBoundaryTest`、`analysis/HistoricalFeedbackTest`、`storage/SnapshotTransferTest`、`knowledge/TopicAliasesTest`、相关恢复测试 | 自动回归通过，包含真实SQLite依赖与恢复；旧 v15 经验快照兼容问题已修复，精确历史与篡改边界通过回归，APK 实际导出成功 |
| 任意 AI 能从有限入口理解资料，逐层定位原文 | Markdown／JSONL 阅读包含概览、维度／主题／时间索引、唯一知识正文及固定证据身份；可选独立历史有分页、范围和完整性声明；[数据转移](DATA_TRANSFER.md) | 备份与导出 → 知识阅读包／证据包；`README.md`、`overview.md`、`personal-history/index.md` | `transfer/KnowledgeArchiveTest`、`transfer/KnowledgePublicationExportTest`、`transfer/PersonalHistoryExportTest`、`storage/ClaimFeedbackTransferTest` | 自动回归通过；APK 系统另存成功，最终阅读包 195 个文件、15 条独立历史，包内 Markdown 链接无缺失；结构可定位不等于任意外部模型都能准确理解 |
| 可迁移、可恢复，不丢修订及用户独立文字 | 口令加密完整备份，预览、替换／合并、版本检查及删除优先；凭据和后台授权不恢复；[数据转移](DATA_TRANSFER.md) | 备份与导出、系统另存、恢复预览与确认、交付失败本机副本重试 | `transfer/BackupArchiveTest`、`transfer/DataTransferTest`、`storage/SnapshotTransferTest`、`transfer/PersonalHistoryExportTest`、`ui/TransferActivityTest` | 自动回归通过，含加密往返和另一SQLite库恢复；APK 已完成系统另存阅读包；恢复由自动回归及实际升级合成库加密往返验证，本轮未重做手机恢复，实体换机未验证 |
| 手机可实际完成主要流程，重建／返回不重复付费或漏刷新 | 页面恢复表单、任务身份与进度；只读页面不发模型请求，返回忙碌状态保留刷新意图；[开发与验证](DEVELOPMENT.md) | 统一侧边导航及各功能页 | 页面测试、最终 APK 模拟器合成旅程；实体手机单列 | Robolectric页面回归通过；最终 APK 已完成覆盖升级及下表旅程；强制重启只读重开新增调用为 0，临时密钥已移除、模拟器已关闭；实体手机未验证 |

## 本轮实际验证记录

下表自动结果已逐项读取本轮归档，真实模型结果由独立语义评审对照请求／响应。最终修复、全量复验、模拟器及交付身份已固定；历史测试通过不自动覆盖后续变更。

本轮仓库外证据根目录为 `~/Library/Caches/trace-life-tools/complete-daily-knowledge-20261020/`。`final-full-fixed.log` 与 `final-full-fixed-results/` 对应下述全量结果；各 `quality/` 运行身份、哈希和读取边界见独立评审。数据均为明确标注的合成资料。

最终51次固定质量请求保存的共同参数为 `model=deepseek-flash`、`temperature=0`、`thinking.type=disabled`、`response_format.type=json_object`、`stream=false`、`max_tokens=2000`。参数固定不保证重复运行得出完全一致或正确的自然语言结论。

| 验证对象 | 必须记录的证据 | 最终状态 |
| --- | --- | --- |
| 固定代码版本 | 发布标签、是否包含未提交改动、验证后是否再次修改 | `v0.1.0-preview.9` 固定交付代码；生产代码和测试在最终全量回归前冻结，之后仅更新文档与交付资料 |
| 定向回归 | 指令、用例数、失败与修复后结果、日志位置 | 后期 `regression-fixes-2-results/` 95项通过；`regression-parts-fixed-results/` 105项中90通过、15默认联网跳过；修复前失败保留在对应日志。最终以本次全量结果统一覆盖，具体筛选命令见[开发与验证](DEVELOPMENT.md) |
| 全量自动测试 | 指令、成功／失败／跳过数及原因、最终日志位置 | 已执行 `testDebugUnitTest lintDebug assembleDebug`；JUnit XML 共1,167项：1,150通过、0失败、0错误、17项显式联网测试默认跳过。日志 `final-full-fixed.log`，XML `final-full-fixed-results/`；已包含最终行动响应、覆盖元数据及旧 v15 快照兼容修复 |
| 静态检查与构建 | lint 结果、APK 构建结果、未解决警告 | 本次构建成功，APK位于 `app/build/outputs/apk/debug/app-debug.apk`；lint XML为0错误、15警告：9项文本国际化、4项工具／依赖更新、1项冗余SDK判断、1项View构造函数。报告在 `app/build/reports/lint-results-debug.xml`；最终交付构建固定为下列 APK |
| 真实 DeepSeek 生成与核对 | 固定六例原材料、最终 parts 协议、实际模型与参数、各例生成／核对／发布结果、独立语义评审及失败处理；FLASH 默认与 PRO 对照分别记录 | 最终FLASH为51次请求：六例35、画像5、挑战11；六例23条全部核对、21条发布，但计划例“五分钟后”误读被发布，长文2条条件假设／实验误拒，unknowns仍有错误。画像两叶和整体完成，整体草稿有因果升级，最终维护／发布／采用未运行。**语义未完全通过**；PRO对照不足以支持更换默认 |
| 真实 DeepSeek 发布反例 | 原固定十例及新增复合句负例，共十一例；错误命题被阻止、合理边界对照可通过，逐例理由不能伪报未读取正文 | 最终11项完成：9条错误均阻止、2条合理对照均通过；两个缺据目标被过强标为直接反证，理由／标签并非全对。只说明该固定集本次二元预期匹配；不能覆盖同轮自由生成误发布 |
| 规模与预算 | 全年合成资料、完整目录分页、最大输入／输出、恢复和删除依赖 | `LongTermConversationScaleTest` 3项通过，覆盖全年逐日路由／继续归并、长Unicode末尾反例、额度耗尽后不虚报完成／重复派发；目录超过200项及输入／导航输出预算、恢复与删除依赖相关类通过。不是实体手机满容量或长期性能测量 |
| 最终 APK 模拟器流程 | 系统版本、全新安装／升级、记录→分析→核对→保存→经验／行动→概览／趋势→导出／恢复、截图与日志 | Pixel 8 / Android 16 / API 36 同签名覆盖升级保留旧资料；记录学习 35 分钟／1 次实践、当前方向、别名查找、图表切换和来源下钻。同问题新任务处理 372 字与 2 份行动材料，对照历史纠正、核对后保存第 5 条知识，补充至经验第 4 次积累；系统另存 195 文件阅读包。重启前后调用台账均 26 次。本轮 6 次手机真实请求及 1 次开发机失败重放与固定质量测试分开；截图／UI XML／日志在上述根目录 |
| APK 交付 | 版本号、签名是否延续、下载地址、SHA-256、升级保留资料结果 | [v0.1.0-preview.9](https://github.com/yyytttwww888-beep/trace-your-life/releases/tag/v0.1.0-preview.9)，版本号 9，5,415,117 字节，Android 8.0 起；同签名覆盖升级成功。SHA-256 `841366b447fe82f3e592a15c68dc258338b5e58a4013e756ec3dfc22ab2fec02`；签名身份见[开发与验证](DEVELOPMENT.md#日常知识库完整收口验证) |
| 清理 | 临时测试密钥已清理、合成资料标识、模拟器已关闭；原始报告与数据位于仓库外 | 设置显示密钥未配置、自动整理已停用；`cleanup.json` 记录核验，模拟器已关闭，`adb devices` 无设备。APK 与变更文件不含配置密钥；原始报告、合成资料与截图保存在仓库外 |
| 实体 Android 手机 | 实际机型／系统、安装、文件交付、后台节电与重启、恢复 | **未验证**；不能以模拟器代替 |

自然语言质量须逐条读报告：[本轮独立质量评审](reviews/2026-10-20-complete-daily-knowledge-quality-review.md)。五轮测试共227次派发，其中226次有逐请求文件和报告，另1次画像首失败只有日志；各轮失败均保留。最终34份普通核对按程序归一后与报告完全一致，但这不改变误发布、误拒、unknowns错误和画像草稿因果升级的独立结论。固定覆盖元数据和分段全集已验证，模型语义正确性未获保证；用户确认也不等于事实证明。长期个人受益不能由短期合成测试证明。

## 仍未实现的外部接入

**华为运动健康仍未实现，属于原需求的真实未完成项。** 当前看板明确显示健康来源未连接。已有官方调研与[接入准备说明](HUAWEI_SETUP.md)，但没有 Health／Account SDK 连接、账号授权、睡眠和运动样本主存、同步游标、去重、源修订传播或真实手机验证。不是把一个 KEY 填好就已具备连接能力。

后续需要目标 Android 项目的包名与签名配置、实际账号的 Health Kit 准入和只读字段批准、仓库外应用配置，以及设备上的 HMS Core、华为运动健康授权与真实样本；个人账号是否符合目标 Android 能力资格仍须由实际控制台核实。SDK 和数据接入实现后，仍须验证部分授权、拒绝／撤销、跨午夜、迟到同步、重复读取、备份与图表下钻。模拟器和合成样本不能替代这些条件；详细官方依据及不确定项见接入说明。

睡眠质量、运动与饮食趋势不能由现有文字记录数推算。接入前可保留用户自述，但不得把自述或手工填写值标为设备测量，也不得伪造评分或正常／异常健康结论。

## 用户明确暂缓与首版范围外

餐食照片识别／营养分析按用户明确指令暂缓；现有图片附件保存不等于已实现识别。通用历史资料导入、多用户服务、多设备双向同步不在当前首版范围，不能作为本轮新增缺口反复扩张。个人模板导入与完整备份恢复是已约定的范围内例外，仍须验收。

实体手机的厂商节电、真实换机恢复、华为接入与长期生活效果单独列示，并不撤销当前环境可执行的代码、自动回归、真实模型合成案例、模拟器与 APK 交付责任。
