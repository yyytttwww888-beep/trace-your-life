# DeepSeek 配置与分批整理

用户已指定 DeepSeek。已实现官方 HTTPS 适配器、密钥保存、自动分批整理与独立证据核对；用户配置 KEY 后，开发机上的调用链及合成核对案例已通过小样本验证，更广泛的分析质量和真机行为仍待验证。模型协议依据及核验日期见[官方资料调研](research/2026-10-17-deepseek-integration.md)，实施范围见[第二阶段计划](plans/2026-10-17-deepseek-analysis.md)与[第三阶段计划](plans/2026-10-17-evidence-review-and-feedback.md)。

## 配置 KEY 后的开发机验证

手机可在设置中填写密钥；开发机验证将 KEY 保存到**仓库外**的纯文本文件：

```text
~/.config/trace-life/deepseek-api-key
```

文件只放 KEY 本身，可有末尾换行，不写变量名、引号或 JSON。建议目录权限 700、文件权限 600。不把 KEY 发进聊天、Git、Gradle 参数或 `local.properties`。保存好后告知 Agent，由 Agent 运行以下显式测试；普通测试与 CI 不会调用真实模型。

```bash
./gradlew -PtraceLifeDeepSeekSmoke=true testDebugUnitTest \
  --tests 'dev.tracelife.providers.DeepSeekLiveTest'
```

如需其他路径，设置 `TRACE_LIFE_DEEPSEEK_KEY_FILE`，值为仓库外的绝对文件路径。测试只读取该文件，不打印 KEY。构建只接收路径，不把密钥编译进 APK。

该测试使用一条明确标注的合成记录，经过真实 `ControlledAnalysis → AnalysisBatchProcessor → DeepSeekProvider → HTTPS`，最多一次模型请求，核对响应、引文与服务端用量。会使用账户额度。测试后清理临时数据库和凭据副本。JVM 中只替换 Android Keystore 的包装密钥；设备 Keystore、手机联网与真实个人任务的分析质量另行验证。完整测试日志与模型输出不写进仓库。

## 实际验证

2026-10-17：用户完成仓库外 KEY 配置后，显式执行一次 `DeepSeekLiveTest`，使用一条合成记录向默认 `deepseek-flash` 发起一次请求。结果为 1 项通过、0 失败、0 跳过；任务达到 `ALL_TEXT_PROCESSED`，提案成功保存，服务端用量通过生产适配器的完整性及预算校验。未修改生产代码，未读取个人记录，未重复发送付费请求。

该次测试允许返回空的结论列表，因此通过证明调用链和响应约束有效，不证明产生了非空引用或结论的语义正确。该次未评估多日期、多主题、反例、长记录及长期判断的模型效果，也未验证手机 Keystore 和网络；与此前 174 项离线测试、lint、APK 和 GitHub CI 通过分别记录。

同日第三阶段另行执行两轮、每轮 5 次合成核对请求，共 10 次请求。第一轮虽然结构与引用校验通过，但模型将因果证据不足、未知日期泛化判为直接矛盾，未达到预期语义边界。随后补充通用判定规则，明确缺乏支持不等于反证，并收紧案例断言。第二轮使用相同资料，5 个案例全部达到预期；结果如下：

| 合成案例 | 第二轮实际判定 | 检查内容 |
| --- | --- | --- |
| 用户记录完成一次学习练习 | `SUPPORTED` | 有非空、本次原文支持引用 |
| 主观睡眠感受写成设备测量 | `CONTRADICTED` | 识别自述变事实；原文明示没有设备测量 |
| 计划两次，长记录尾部更正为一次 | `CONTRADICTED` | 引用显式提供的尾部反例 |
| 咖啡与两晚感受推导因果改善 | `UNSUPPORTED` | 识别因果过度推断与范围扩大 |
| 未知日期一次散步推导整周每天锻炼 | `UNSUPPORTED` | 识别时间缺口与范围扩大 |

第二轮为 1 项包含 5 个案例的显式测试通过，0 失败、0 跳过；实际用量、引用归属与预算均通过生产调用链校验。本阶段另有 223 项离线测试通过，2 项真实测试默认跳过，lint 无错误（2 项既有升级提示），调试 APK 构建通过。候选陈述由测试人工构造，反例由调用方选定；这不是对真实提取、自动反例搜索、长期判断或总体准确率的验证，也不代表手机 Keystore 与联网已验收。

## 显式核对案例评估

`DeepSeekReviewLiveTest` 使用独立开关，默认与普通连通测试一起跳过：

```bash
./gradlew -PtraceLifeDeepSeekReview=true testDebugUnitTest \
  --tests 'dev.tracelife.providers.DeepSeekReviewLiveTest'
```

使用同一仓库外 KEY，最多 5 次付费请求，不自动重试。案例均为人工可核对的合成资料，分别检查有依据的自述、自述冒充设备测量、长记录尾部反例、跨日期因果过度推断，以及未知日期扩大为整周。候选陈述是专门构造的正确或错误样例，只有核对模型使用真实 DeepSeek；因此该评估不代表真实提取质量。输出仅记录固定案例标签、判定枚举及 token 数，不输出 KEY 或模型正文。核对接口与局限见[陈述核对](CLAIM_REVIEW.md)。

## Android 后端接口

```kotlin
// 以下都在后台线程执行。apiKey 来自未来的配置输入，不得作为源码常量。
try {
    app.deepSeekCredentials.configure(apiKey)
} finally {
    apiKey.fill('\u0000')
}
val status = app.deepSeekCredentials.status()
// CredentialStatus.MISSING / CONFIGURED / UNREADABLE

val task = app.analysis.start(request)
val result = app.analysisProcessor.process(
    runId = task.stored.id,
    provider = app.deepSeekProvider,
    maxBatches = 5,
    cancelled = { cancellationFlag.get() },
)
```

`DeepSeekCredentials.clear()` 清除配置，后续调用需要重新设置。密钥用 Android Keystore 中 AES-256 密钥加密，认证应用身份和格式版本；密文位于私有 `noBackupFilesDir/deepseek/api-key.enc`。数据库备份、知识包、系统备份均不含凭据。更换手机需要重新配置。配置损坏或 Keystore 密钥不可用时返回 `UNREADABLE`，不会退回明文保存。轮换或清除配置会等待正在使用密钥的请求结束。

应用默认使用 `deepseek-flash`，可通过 `DeepSeekProvider(credentials, DeepSeekModel.PRO)` 选择 `deepseek-v4-pro`。不接收任意服务器 URL；地址固定为官方 `https://api.deepseek.com/chat/completions`，只使用 HTTPS。首版使用非思考、非流式 JSON 输出，不携带工具、图片或未登记历史。Android 已有联网权限；[后台增量整理](BACKGROUND_ANALYSIS.md)默认关闭，明确启用后才自动派发受控的记录提取／核对请求。

## 预算与响应校验

供应商先生成最终 HTTP JSON，执行器再登记其字节数、摘要和 token 预算，供应商附加的输出格式提示词同样计量。DeepSeek 使用 `CONSERVATIVE_UTF8_BOUND`：整个请求 UTF-8 字节数加 1,024 的消息封装预留。公开 ByteLevel BPE 支持该保守文本上界推导，但服务端封装一致性尚未验证，**不是精确计费 token 数**。应用上下文上限保守限制为 100,000，任务默认仍为 12,000 输入预算加 2,000 输出预留。

成功响应必须提供 `usage`，其实际 prompt tokens 不得超过已登记上界，completion tokens 不得超过输出预留，total 必须相符；超界拒绝保存提案并停止本轮。服务端用量与预算计量类型分别持久化。核对调用使用独立 JSON 响应结构并执行相同计量，任务、备份及数据库版本见[数据转移格式](DATA_TRANSFER.md)。

响应必须是单条 assistant 的自然结束 `stop`，拒绝截断、工具请求和意外思考正文。JSON 严格检查重复字段、类型、未知提案字段、引用格式、UTF-8、嵌套与大小；执行器再检查引用归属：提取／综合只引用本次原文，归并只关联本次明确提供的派生输入。服务端错误正文不读取、不保存，避免把请求回显带入日志。

网络连接超时 15 秒、读取超时 60 秒，90 秒计时器主动断开连接，响应最多 256 KiB。DNS 或系统网络栈阻塞时取消不一定立即返回，因此不能承诺设备上绝对的 90 秒硬截止。禁用重定向；应用不自动重试付费调用，固定长度发送禁止网络栈缓冲请求体用于重放。鉴权、余额、限流、网络、超时、响应无效等以固定原因码保存到任务。外部请求已发出后无法撤回，取消处理只阻止下一批；模型调用期间修改或删除来源仍会阻止旧结果写回。

## 自动分批与继续处理

`AnalysisBatchProcessor.process` 是调用方启动的一轮后台工作，默认最多 5 批，可配置 1–100。它沿分页索引枚举完整范围，逐字段选取尚未完成的固定 Unicode 片段，每批一个片段。成功台账供下一轮恢复，已处理区间不重复计算；不会把全库送给模型。

| 状态 | 含义与后续动作 |
| --- | --- |
| `ALL_TEXT_PROCESSED` | 已知范围的文字片段均经过成功调用，提案仍需语义核对 |
| `TEXT_COMPLETE_WITH_GAPS` | 文字已处理，但附件、未知时间、跨期或不可读资料等缺口仍存在 |
| `PASS_LIMIT_REACHED` | 本轮批数已到，使用同一任务继续调用 |
| `BUDGET_EXHAUSTED` | 任务累计预算或单次输入不足，保留进度；不能静默加大范围 |
| `RETRY_NEEDED` | 本轮失败，查看最近调用的安全原因码，修复后显式重试 |
| `NEEDS_RECOVERY` | 存在进程中断留下的在途记录，先调用 `analysis.recoverInterrupted(id)` |
| `CANCELLED` | 已停止下一批派发，可以再次继续 |
| `STALE` / `EXPIRED` | 来源变化或任务过期，建立新任务 |
| `NO_DATA` / `TASK_UNAVAILABLE` / `CONFLICT` | 无资料、任务已删除或并发冲突，需要对应处理 |

显式提取接口本身不安排定时工作；[后台增量整理](BACKGROUND_ANALYSIS.md)提供独立的启用、暂停与限额策略。上述分批提取输出描述具体观察、方法或可能解释，并保留反例与缺口；提取本身不会发布知识。语义核对与用户纠正通过[独立接口](CLAIM_REVIEW.md)执行，通过检查后再由[知识发布接口](KNOWLEDGE.md)保存。[自动整理器](PERIOD_REFLECTION.md)可在共享请求上限下串起提取、核对和发布；另有[有界综合接口](SYNTHESIS_ACTIONS.md)对照多条原文并提出待确认解释，多个综合批次通过[分层周期草稿](PERIOD_REDUCTION.md)继续归并，归并陈述可继续通过[周期原文核对与发布](PERIOD_REVIEW.md)，已有[画像采用与维护](PERSONAL_PROFILE.md)，新的[受控画像候选](PROFILE_CANDIDATES.md)按指定维度提出近期／情境解释，[纵向画像](LONGITUDINAL_PROFILE.md)提供跨周期长期候选，仍需完整维护核对和明确保存。

## 综合真实调用验证

2026-10-17 使用同一仓库外 KEY 完成 1 项显式合成测试，0 失败、0 跳过。两条合成记录描述“先看资料未动手”与“先练习后查资料”，同时提供难度和可用时间不同的替代解释；生产综合调用生成 2 条 `POSSIBLE_INTERPRETATION`，其中一条同时引用两份原文。随后生产核对调用重新读取本批全部原文，返回 `SUPPORTED`。未自动发布知识或采纳行动。

本次共 2 次请求，无重试：综合实际用量为 1,139 输入／456 输出 token，核对为 1,732 输入／175 输出 token，均在已登记预算内。验证的是这一组有界合成资料的提案、引用及核对链，不代表跨批整合、自动反例召回、真实个人记录总体准确率或手机能力。

```bash
./gradlew testDebugUnitTest \
  --tests 'dev.tracelife.providers.DeepSeekSynthesisLiveTest' \
  -PtraceLifeDeepSeekSynthesis=true
```

此开关只启用综合测试，默认最多一次综合加一次核对，资料固定为合成内容，不输出 KEY、请求头或模型正文。普通测试和 CI 跳过它，也不读取真实凭据；显式测试仅验证开发机联网，Android Keystore 包装密钥在 JVM 使用替身。

## 分层归并真实调用验证

2026-10-19 单独执行 `DeepSeekReductionLiveTest`：5 个明确标注的合成局部草稿，经两层、共 2 次真实归并请求形成 2 条周期草稿陈述；1 项测试通过、0 失败、0 跳过。两次实际用量分别为 2,247 输入／637 输出 token 和 2,061 输入／518 输出 token，均在已登记预算内，没有自动重试。

测试核对实际请求每次仅含 2–4 个派生子节点、没有原文正文，根覆盖全部 5 个叶节点，至少一条陈述关联两个直接分支，未知项非空；草稿的实际原文读取台账为空，不能进入正式知识发布。局部提案由测试构造，真实 DeepSeek 仅负责归并；这不是原文提取、整体原文核对、反例保留率或总体分析准确率验证。未读取个人资料，未进行手机 Keystore 或联网验收。

```bash
./gradlew testDebugUnitTest \
  --tests 'dev.tracelife.providers.DeepSeekReductionLiveTest' \
  -PtraceLifeDeepSeekReduction=true
```

本开关最多启用两次付费请求，普通测试与 CI 默认跳过且不读取 KEY。沿用仓库外密钥文件；JVM 使用临时包装密钥，测试后清理临时数据库和凭据副本，不打印模型正文或 KEY。归并提示词与原文整理提示词分开，完整请求计量相同；接口及限制见[分层周期草稿](PERIOD_REDUCTION.md)。

## 周期原文核对真实调用验证

2026-10-19 显式执行 `DeepSeekPeriodReviewLiveTest`，1 项通过、0 失败、0 跳过。两条合成自述均提到完成一个小练习，并明确不证明方法有效；草稿和原文综合叶由测试构造，测试程序模拟用户明确确认。真实 DeepSeek 扫描两份原文，再独立重读两份相关原文，返回支持，随后通过生产知识发布事务保存一条 `INSIGHT`。

共 2 次真实请求、无重试：扫描实际为 1,339 输入／240 输出 token，最终核对为 2,186 输入／171 输出 token，均在登记预算内。仅验证这组合成资料的扫描、最终核对、确认依赖与发布链；不代表原文提取／归并质量、反例召回率、总体分析准确率或实体手机验收。没有发送真实个人资料。

```bash
./gradlew testDebugUnitTest \
  --tests 'dev.tracelife.providers.DeepSeekPeriodReviewLiveTest' \
  -PtraceLifeDeepSeekPeriodReview=true
```

本开关最多发起两次付费请求，普通测试与 CI 默认跳过且不读取 KEY。沿用仓库外凭据路径与临时测试包装密钥，结束后清理临时数据库和密钥副本，只记录安全计数和用量。扫描、最终核对分别使用独立提示词及严格输出解析；接口见[周期原文核对与发布](PERIOD_REVIEW.md)。

## 指标解释真实调用验证

2026-10-19 显式执行 `DeepSeekMetricLiveTest`，1 项通过、0 失败、0 跳过。程序从 4 条合成记录计算 7 天的记录覆盖，模型准确描述总数 4、精确日期记录 3、有精确日期记录的天数 2、未知时间记录 1，保留记录覆盖不表示活动、成长或健康的限制。独立核对重新计算同范围指标，返回支持；测试模拟用户明确确认后发布 1 条洞察。两次请求均没有发送原文或附件。

共 2 次真实请求、无重试：解释为 1,123 输入／275 输出 token，核对为 1,439 输入／136 输出 token，均在已登记预算内。此结果仅验证这组合成资料的数值解释、指标引用及发布链，不代表总体准确率、真实个人资料或实体手机验证。

```bash
./gradlew testDebugUnitTest \
  --tests 'dev.tracelife.providers.DeepSeekMetricLiveTest' \
  -PtraceLifeDeepSeekMetric=true
```

该开关最多启用两次付费请求，普通测试和 CI 默认跳过且不读取 KEY。沿用仓库外密钥、临时测试包装密钥及结束后的清理；仅输出合成陈述、安全计数和用量。接口见[受控指标分析](METRIC_ANALYSIS.md)。

## 画像候选真实调用验证

受控画像任务在执行器请求和适配器顶层系统指令中分别标明固定维度与层次，提示词同样计入完整输入预算；仅支持近期状态和情境模式。生成沿用 `SYNTHESIS`，核对沿用 `REVIEW` 并重读整批原文，响应结构与计量校验保持一致。详细行为见[画像候选接口](PROFILE_CANDIDATES.md)。

```bash
./gradlew testDebugUnitTest \
  --tests 'dev.tracelife.providers.DeepSeekProfileLiveTest' \
  -PtraceLifeDeepSeekProfile=true
```

该开关默认关闭，使用仓库外既有密钥和三条明确标注的合成资料，最多一次生成加一次独立核对，无自动重试。测试在生成后模拟用户明确确认，再核对当前反馈版本并保存为画像候选，检查默认画像不会自动采用。普通测试和 CI 跳过且不读取 KEY；JVM 仍使用临时包装密钥，只记录安全计数和用量。实际执行结果见[M4 第三阶段计划](plans/2026-10-19-profile-candidates.md#验证记录)，不代表总体准确率、长期人格判断或实体手机验证。

## 跨任务画像维护真实调用验证

```bash
./gradlew testDebugUnitTest \
  --tests 'dev.tracelife.providers.DeepSeekMaintenanceLiveTest' \
  -PtraceLifeDeepSeekMaintenance=true
```

该开关默认关闭，普通测试与 CI 不读取 KEY。最多发起一次历史关系比较、一次三片段原文扫描及一次最终核对，共 3 次真实请求，没有自动重试。旧判断、新候选及用户操作由测试构造；真实 DeepSeek 负责关系、扫描和核对。资料包含两次条件较充分时完成练习、一次困难且时间不足时未完成的合成自述，新描述须保留情境和例外。最终在原画像身份上追加候选修订，保留独立文字和历史，不自动采用。

该案例验证有限资料的调用、证据归属和更新链，不代表自动生成质量、总体反例召回率、长期判断准确率或实体手机验收。使用仓库外已有凭据和临时 JVM 包装密钥，日志仅输出分类、状态及用量。

2026-10-19 共执行三轮，每轮上限 3 次，实际共 9 次请求；没有程序自动重试。第一轮在最终核对被协议拒绝，未写入画像。第二轮加入仅枚举和计数的诊断，确认扫描将候选已承认的失败情境误标为反例，最终模型却返回支持；程序拒绝该不一致。随后补充通用扫描规则：比较候选的完整限定，原文证实已纳入的例外时应作为支持或情境，不能仅因事件失败就当作反证。

第三轮 1 项真实测试通过：关系为 `SUPPLEMENT`，建议 `NARROW`；三份原文均为支持，最终重读三份原文后返回支持。在原画像 ID 上追加第 3 个修订，保留独立文字并回到 `CANDIDATE`，未自动采用。最后一次修改同时通过 18 项离线协议／适配器测试、lint 与 APK 构建。

| 第三轮步骤 | 输入 token | 输出 token |
| --- | ---: | ---: |
| 关系比较 | 1,213 | 179 |
| 原文扫描 | 1,562 | 306 |
| 最终核对 | 3,136 | 210 |

以上为服务端实际用量，均符合登记预算。这个小样本展示了模型分类可能出错，也验证了程序能阻止已知反例与支持意见冲突时发布；第三轮通过不代表总体准确率或其他资料也能正确识别。

## 行动周期联动的显式测试

```bash
./gradlew testDebugUnitTest --tests 'dev.tracelife.providers.DeepSeekActionPeriodLiveTest' \
  -PtraceLifeDeepSeekActionPeriod=true
```

默认关闭，普通测试和 CI 不读取凭据。此用例使用仓库外的既有密钥和明确标注的合成行动计划、回看与日常记录，最多一次生成、一次独立核对，不自动重试。检查原计划与调整后计划分离、两类来源引用、整批核对以及确认后发布；JVM 使用临时包装密钥。实际运行结果见[四项后端验证记录](plans/2026-10-19-backend-completion.md#验证记录)，不代表模型总体准确率或真机验证。

## 行动跨批报告与知识包的显式测试

```bash
./gradlew -PtraceLifeDeepSeekActionReport=true testDebugUnitTest \
  --tests 'dev.tracelife.providers.DeepSeekActionReportLiveTest'
```

默认关闭，使用同一仓库外 KEY。先用明确合成的期初计划、补记回看及日常记录构造三个局部草稿，再最多调用真实 DeepSeek 三次：整体归并、全部原始来源扫描、最终相关原文独立核对。测试显式模拟确认后发布，再导出指定周的知识包，检查结论和旧／新计划均可定位。没有重试或追加压缩调用，JVM 使用临时包装密钥。实际结果见[本轮验证](plans/2026-10-19-action-report-export.md#验证记录)；不代表局部提取质量、复杂反例召回或总体分析准确率。


## 主动分析闭环真实验证

新增 `DeepSeekReflectionLiveTest`，通过[主动分析编排](REFLECTION.md)执行与手机相同的综合、用户确认和原文核对保存。每次测试最多 2 次真实请求，使用三条明确标注的合成学习记录，检查有内容的经验与问题、跨记录引用、正式保存和重新读取历史；普通测试默认跳过。

```bash
./gradlew testDebugUnitTest --tests 'dev.tracelife.providers.DeepSeekReflectionLiveTest' \
  -PtraceLifeDeepSeekReflection=true
```

运行结果和实际范围见[开发验证](DEVELOPMENT.md#主动分析闭环验证)。测试不读取真实个人记录或把密钥写入 APK；生成的合成文字用于人工检查表达质量，不代表个人任务总体准确率。

## 历史纠正与原文引用的真实验证

```bash
./gradlew testDebugUnitTest --tests 'dev.tracelife.providers.DeepSeekHistoricalFeedbackLiveTest' \
  -PtraceLifeDeepSeekReflection=true
```

新增用例沿用主动分析的显式开关，普通测试与 CI 默认跳过、不读取凭据；单次最多 1 个付费请求。它先构造旧判断和用户纠正，再将两条当前合成记录经过正式 `ReflectionWorkflow → ControlledAnalysis → DeepSeekProvider`。仅合成请求与响应正文写入仓库外 `~/Library/Caches/trace-life-tools/experience-memory-20261020/history-live-<随机ID>/`，不保存请求头或密钥，日志仅输出路径、响应结构、计数与用量。测试后清理数据库与临时凭据。

2026-10-20 共执行 2 次：首次响应自然结束、JSON 完整，用量 1,695 输入／578 输出 token；模型却把历史纠正的反馈 ID 放进了新判断的 `evidenceIds`。适配器结构解析成功，受控执行器的证据归属校验拒绝整份提案，任务记录 `INVALID_RESPONSE`，没有发布或放行部分判断。

修复将历史条目的传输标识改成明确类型的 `controlKey`，内容校验值保留在固定任务目录，并在顶层系统指令要求历史意见只约束判断、不能引用或转述为本期事实。生成、核对和扫描共用该规则，完整指令仍计量；没有增加输出预算或放宽引用检查。补充离线回归验证混入原始反馈 ID 时仍拒绝保存。

同一合成案例修复后通过：响应自然结束，4 条发现均通过原文引用检查，任务达到 `READY`，登记读取 1 条历史意见；实际用量 1,791 输入／615 输出 token，完整请求 7,650 字节，保守输入预算 8,674 token。同期 26 项历史协议与适配器离线测试全部通过。此用例没有调用独立核对、处理人工历史对照或发布知识，仅验证该有限案例的生成链与引用边界，不代表模型总体语义正确率或实体手机验收。

请求变化后旧失败批次不能在同一标识下改用新请求继续，需新建同范围任务；旧任务保留其原始调用和错误记录。供应商结构错误记录为 `PROVIDER_RESPONSE`，执行器的结构、引用或预算校验失败记录为 `INVALID_RESPONSE`，诊断时应区分两者。

## 历史意见语义回归

`DeepSeekHistoricalFeedbackAssistLiveTest` 使用独立开关，最多 3 次真实请求，分别检查遵守限制、用同义表达重复被否定的判断、无关情境；每次还带一项旧描述缺失的意见，要求保留不确定。生成夹具是本地合成内容，比较通过正式受控接口执行。模型建议不能直接产生用户决定或知识发布。

```bash
./gradlew testDebugUnitTest \
  --tests 'dev.tracelife.providers.DeepSeekHistoricalFeedbackAssistLiveTest' \
  -PtraceLifeDeepSeekHistoricalAssist=true
```

沿用本页仓库外密钥文件配置；普通回归默认跳过。固定案例及更广泛的评估边界见[分析质量](ANALYSIS_QUALITY.md)，本次实际结果见[开发验证](DEVELOPMENT.md)。


### 对话式回看真实验证

`DeepSeekConversationLiveTest` 默认跳过，明确执行 `./gradlew testDebugUnitTest --tests 'dev.tracelife.providers.DeepSeekConversationLiveTest' -PtraceLifeDeepSeekConversation=true` 才读取仓库外凭据。测试最多调用 2 次，不自动重试：三个月学习问题与选定发现的改善／反例追问，使用四条合成记录，其中一条无主题。实际用量与结果见[开发验证](DEVELOPMENT.md#对话式回看验证)。

## 固定质量案例与整体画像验证

新增显式测试 `DeepSeekKnowledgeQualityLiveTest`（`-PtraceLifeDeepSeekQuality=true`），使用六组固定合成案例检查重复事件、长文末尾反例、资料缺期、历史纠正、计划与执行以及相对旧理解的新信息。全套最多 12 次真实请求，单案例最多 3 次；不为得到理想结论自动重试。通过 `-PtraceLifeQualityReportDir=/仓库外目录` 保存 JSON／Markdown 报告；程序协议校验与逐案例语义评价分别报告。测试通过不能代表总体准确率。

新增 `DeepSeekProfileIntegrationLiveTest`，沿用 `-PtraceLifeDeepSeekProfile=true`，两个月四条合成原文最多使用 5 次请求验证分批生成、批内核对与整体整理。该测试不执行付费全范围维护扫描，不自动发布或采用画像。默认构建跳过两类真实调用。
