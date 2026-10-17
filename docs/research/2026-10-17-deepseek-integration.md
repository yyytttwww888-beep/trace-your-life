# DeepSeek 文字分析接口核验

核验日期：2026-10-17。使用 research 与 agent-search 的官方网页直读路线；仅读取公开文档和分词器文件，没有 API Key，没有发送模型请求。以下是接入依据，不代表线上模型效果已经验证。

## 接口与模型

官方当前入口为 `POST https://api.deepseek.com/chat/completions`，认证为 `Authorization: Bearer <key>`。当前推荐 ID 为 `deepseek-flash`，另有 `deepseek-v4-pro`；前者对应 DeepSeek-V4.1-Flash，后者对应 DeepSeek-V4-Pro-0813。旧文章中的 `deepseek-chat` 不能作为本次默认配置依据。[首次调用](https://api-docs.deepseek.com/)、[模型说明](https://api-docs.deepseek.com/quick_start/pricing)

本项目首轮采用文字、非流式、非思考调用：`stream: false`、`thinking: {"type":"disabled"}`，固定 `max_tokens`，不附加工具、联网搜索、图片或隐式历史。官方默认开启思考，必须显式关闭。模型上下文标为 1M，输出上限 384K；应用继续采用自己的小请求预算，不因模型窗口增大而放宽分层检索。[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[模型说明](https://api-docs.deepseek.com/quick_start/pricing)

`response_format: {"type":"json_object"}` 要配合提示词中的 JSON 要求及格式示例。JSON 输出仍可能为空或被截断，不能替代字段、类型、数量、引用及语义校验。当前普通响应格式没有 `json_schema`；JSON Schema 的严格模式属于 Beta 工具调用，首轮不采用。[JSON 输出](https://api-docs.deepseek.com/guides/json_mode)、[严格工具调用](https://api-docs.deepseek.com/guides/tool_calls/)

## 本地计量的依据与边界

官方提供离线分词器，但同时要求以返回的 `usage` 作为实际消耗依据。页面上的中英文字数换算只是近似值，不能用于本项目的预算准入。[Token 用量](https://api-docs.deepseek.com/quick_start/token_usage)

本次下载并静态检查该页面链接的 [V4 分词器 ZIP](https://cdn.deepseek.com/api-docs/deepseek_v4_tokenizer.zip)：

- 文件大小 1,911,504 字节，SHA-256 为 `e7310d1dafe0a86d8a5629fe78a7c763760f651db9b8682718a1781dcd6fe495`。
- ZIP 包含 Python 示例、`tokenizer_config.json` 和 `tokenizer.json`，没有独立许可证文件；本轮未将该文件复制到仓库或 APK。
- 分词模型为 ByteLevel BPE；词表含全部 256 个字节对应的基础符号，合并操作减少符号数量，归一化为空，文本预处理不加前缀空格。已逐一检查基础符号存在。
- 配置包含聊天模板，但 `model_max_length` 仍为 16,384，与当前托管模型页面不一致。不能将这个字段当作当前 API 上限，也不能据此声称本地模板与托管服务完全相同。

**产品推导：**对上述公开分词器，普通文字的 UTF-8 字节数是保守的 token 上界；计入已知聊天模板后也可构造上界。完整 HTTP JSON 的 UTF-8 字节数还包含字段名称与转义，可作为本地保守准入量，并另预留封装空间。它必须标为“保守计量”，不能冒充精确分词结果或实际计费 token。

**尚未验证：**公开资料没有保证服务器永远不增加额外封装；未发现预请求精确 token 计量接口。因此本地保守计量仅基于公开模型机制与固定请求约束，仍需配置 Key 后用服务返回的 `prompt_tokens` 对账。实际用量超过准入量时拒绝采用结果并停止后续派发；已经发生的云端消耗无法事后撤销。不要声称“未经实测已证明每次服务端计量都精确一致”。

响应包含 `prompt_tokens`、`completion_tokens`、`total_tokens`，以及缓存命中/未命中信息；缓存命中不表示本次未提供这些内容。记录本地准入量和服务实际用量时应分开字段，不用后者覆盖先前的预算依据。[响应结构](https://api-docs.deepseek.com/api/create-chat-completion/)

## 网络失败与输出验收

非流式请求等待期间可能收到空行；客户端需允许 JSON 前导空白，同时限制累计响应字节、读取时间和整体请求时间。仅设置单次 socket 读取超时不足以限制持续空行。官方说明未开始推理的请求可能等待至十分钟后关闭。[连接保持机制](https://api-docs.deepseek.com/quick_start/rate_limit)

只有 `finish_reason: stop`、非空内容及完整有效结构可以进入候选校验。`length`、`content_filter`、`tool_calls`、`insufficient_system_resource`、`aborted` 均不能当作完整成功结果。[响应结构](https://api-docs.deepseek.com/api/create-chat-completion/)

官方列出 400/422 参数错误、401 认证错误、402 余额不足、429 限流、500/503 服务故障。适配器应转换为不含密钥、原文和供应商错误正文的安全错误码；中断或超时不能证明供应商没有收到请求。[错误码](https://api-docs.deepseek.com/quick_start/error_codes)

**产品推导：**HTTP 层不自动重试、不跟随重定向携带认证信息；恢复由持久化任务和显式重试入口负责。所读官方资料没有承诺 Chat Completions 接受应用幂等键或保证只计费一次，本地批次去重不能宣称云端去重。

## 数据与凭据

开放平台协议要求保护 API Key，不应把密钥暴露在客户端代码。个人 Android 场景中，用户配置的密钥应保存在设备私有加密存储中，不能编译进 APK、写入 Git、日志或知识/备份导出。[开放平台服务协议](https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html)

官方隐私政策覆盖输入收集、服务改进与保存，并明确下游应用的终端用户资料处理需由开发者另行说明。所读公开材料不足以证明该 API 账号享有“零保留”或“绝不训练”承诺，不在产品中作此保证。主存位于手机不代表发送到云端的任务片段仍只留在手机。[隐私政策](https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html)、[开放平台服务协议](https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html)

首次联网验收使用明确标注的合成记录，验证认证、模型可用性、请求封装、返回用量及异常路径；用户已明确由其后续配置 Key，本轮不进行线上测试。

## 来源访问记录

| 来源 | 读取目标 | 结果 |
| --- | --- | --- |
| `api-docs.deepseek.com` | 首次调用、模型、Chat Completions、JSON、工具、计量、限流、错误 | web 部分页面超时，改用同一官方页面的直接 HTTPS GET 读取 |
| `cdn.deepseek.com` | 官方链接的 V4 分词器 | 下载到仓库外缓存，静态检查结构与字节词表 |
| `cdn.deepseek.com/policies` | 开放平台协议、隐私政策 | 官方原文直接读取；没有推定额外的账号合同或保留承诺 |

模型别名、参数、配额和政策会变化；真实调用前及更换模型时重新核验。本文不记录价格，避免把本次时点价格变成长期产品常量。
