# DeepSeek 配置与分批整理

用户已指定 DeepSeek。已实现官方 HTTPS 适配器、密钥保存与自动分批整理；用户配置 KEY 后，开发机上的单样本真实调用测试已通过，分析质量和真机行为仍待验证。模型协议依据及核验日期见[官方资料调研](research/2026-10-17-deepseek-integration.md)，实施范围见[第二阶段计划](plans/2026-10-17-deepseek-analysis.md)。

## 配置 KEY 后的开发机验证

前端仍后置。目前可先将 KEY 保存到**仓库外**的纯文本文件：

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

该测试允许返回空的结论列表，因此通过证明调用链和响应约束有效，不证明产生了非空引用或结论的语义正确。尚未评估多日期、多主题、反例、长记录及长期判断的模型效果，也未验证手机 Keystore 和网络。这次独立真实测试与此前 174 项离线测试、lint、APK 和 GitHub CI 通过分别记录，不表示本轮重跑了全套测试。

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

应用默认使用 `deepseek-flash`，可通过 `DeepSeekProvider(credentials, DeepSeekModel.PRO)` 选择 `deepseek-v4-pro`。不接收任意服务器 URL；地址固定为官方 `https://api.deepseek.com/chat/completions`，只使用 HTTPS。首版使用非思考、非流式 JSON 输出，不携带工具、图片或未登记历史。Android 新增联网权限，但没有后台自动上传行为。

## 预算与响应校验

供应商先生成最终 HTTP JSON，执行器再登记其字节数、摘要和 token 预算，供应商附加的输出格式提示词同样计量。DeepSeek 使用 `CONSERVATIVE_UTF8_BOUND`：整个请求 UTF-8 字节数加 1,024 的消息封装预留。公开 ByteLevel BPE 支持该保守文本上界推导，但服务端封装一致性尚未验证，**不是精确计费 token 数**。应用上下文上限保守限制为 100,000，任务默认仍为 12,000 输入预算加 2,000 输出预留。

成功响应必须提供 `usage`，其实际 prompt tokens 不得超过已登记上界，completion tokens 不得超过输出预留，total 必须相符；超界拒绝保存提案并停止本轮。服务端用量与预算计量类型分别持久化。任务载荷升级为 v2，兼容历史 v1，不强制改写接近容量上限的旧任务；外层备份格式仍为 v2、数据库仍为 v3。

响应必须是单条 assistant 的自然结束 `stop`，拒绝截断、工具请求和意外思考正文。JSON 严格检查重复字段、类型、未知提案字段、引用格式、UTF-8、嵌套与大小；执行器再检查引用确实来自本次提供的原文。服务端错误正文不读取、不保存，避免把请求回显带入日志。

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

当前未安排 Android 定时工作，也不会保存记录后自动联网。整理输出描述具体观察、方法或可能解释，并保留反例与缺口；未自动成为正式知识条目、周报或画像。语义支持核对、用户纠正与正式发布仍在后续阶段。
