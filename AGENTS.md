# Trace Life：Agent 工作入口

本文件用于开发本仓库的 AI Agent；产品运行时的个人知识库另有生成的阅读入口与受控检索协议。

## 开始任务

1. 阅读 [README.md](README.md)，确认当前实现状态；检查工作区已有修改，保留用户未提交的工作。
2. 涉及领域概念、模型或命名时阅读 [CONTEXT.md](CONTEXT.md)。该文件只维护词汇，不存放实现方案。
3. 按下表读取任务相关文档，不默认加载整个 `docs/` 或个人知识库。

| 任务 | 必须读取 |
| --- | --- |
| 功能范围、记录、复盘、模板、看板、存储或导出 | [主设计](docs/superpowers/specs/2026-10-16-personal-knowledge-and-reflection-design.md) 的相关章节 |
| AI 调用、检索、摘要、批处理、纠错、删除、任务发布 | [检索与可靠性协议](docs/superpowers/specs/2026-10-16-knowledge-routing-and-reliability-design.md) |
| 修改受控分析执行、任务台账或模型适配器 | [受控分析接口](docs/CONTROLLED_ANALYSIS.md)、[M3 第一阶段计划](docs/plans/2026-10-16-controlled-analysis.md) |
| 语义核对、用户确认／纠正／否定、反馈删除或恢复 | [核对与反馈接口](docs/CLAIM_REVIEW.md)、[第三阶段计划](docs/plans/2026-10-17-evidence-review-and-feedback.md)；持久化变更同时读数据转移接口 |
| 正式知识发布、知识索引或记录覆盖指标 | [知识与指标接口](docs/KNOWLEDGE.md)、[第四阶段计划](docs/plans/2026-10-17-knowledge-publication.md)；持久化和导出同时读数据转移接口 |
| 自动整理、周期材料或报告导出 | [周期材料接口](docs/PERIOD_REFLECTION.md)、[第五阶段计划](docs/plans/2026-10-17-period-reflection.md) |
| 主动分析入口、进度、发现保存或分析历史 | [主动分析接口](docs/REFLECTION.md)、[闭环设计](docs/superpowers/specs/2026-10-20-visible-reflection-design.md)；涉及模型调用同时读检索协议 |
| 经验关联、补充／例外／收窄／替代或形成过程 | [经验线索](docs/EXPERIENCE_CONTINUITY.md)、[跨期积累设计](docs/superpowers/specs/2026-10-20-experience-memory-and-archive-design.md)；恢复或导出同时读数据转移接口 |
| 普通分析继承历史意见、逐项对照或反馈依赖清理 | [历史意见](docs/HISTORICAL_FEEDBACK.md)、检索协议；同时检查原文核对与知识发布门槛 |
| 分析归档、任务容量或历史分页 | [分析归档](docs/TASK_ARCHIVE.md)；恢复同时读数据转移接口 |
| 综合判断、行动采纳、回看或行动修订 | [综合与行动接口](docs/SYNTHESIS_ACTIONS.md)、[第六阶段计划](docs/plans/2026-10-17-synthesis-actions.md)；持久化与恢复同时读数据转移接口 |
| 跨批归并、周期草稿或派生依赖 | [分层周期草稿接口](docs/PERIOD_REDUCTION.md)、[第七阶段计划](docs/plans/2026-10-19-period-reduction.md)；备份变更同时读数据转移接口 |
| 周期草稿核对、归并反馈、正式发布或后代清理 | [周期原文核对接口](docs/PERIOD_REVIEW.md)、[第八阶段计划](docs/plans/2026-10-19-period-review-publication.md)；删除或恢复同时读数据转移接口 |
| 模型指标引用、统计凭据或指标说明发布 | [受控指标分析](docs/METRIC_ANALYSIS.md)、[第九阶段计划](docs/plans/2026-10-19-metric-evidence.md)；保存与导出同时读数据转移接口 |
| 看板趋势、图表下钻、行动时间线或看板导出 | [看板接口](docs/DASHBOARD.md)、[M4 第一阶段计划](docs/plans/2026-10-19-dashboard-data.md)；导出同时读数据转移接口 |
| DeepSeek、密钥、网络或自动分批处理 | [DeepSeek 接入](docs/DEEPSEEK.md)、[第二阶段计划](docs/plans/2026-10-17-deepseek-analysis.md)；接口事实变更时核验[官方研究](docs/research/2026-10-17-deepseek-integration.md) |
| 画像维度、采用、历史、反馈或长期判断 | [画像设计](docs/superpowers/specs/2026-10-16-long-term-personal-profile-design.md)、[画像接口](docs/PERSONAL_PROFILE.md)、[M4 第二阶段计划](docs/plans/2026-10-19-personal-profile.md)；生成和维护同时读检索协议，迁移同时读数据转移接口 |
| 画像候选生成、核对或转为画像条目 | [画像候选接口](docs/PROFILE_CANDIDATES.md)、[M4 第三阶段计划](docs/plans/2026-10-19-profile-candidates.md)；协议变更同时读受控分析与 DeepSeek 接口，迁移同时读数据转移接口 |
| 跨任务画像关系、历史反馈约束、原文反例扫描或维护应用 | [画像维护接口](docs/PROFILE_MAINTENANCE.md)、[M4 第四阶段计划](docs/plans/2026-10-19-profile-maintenance.md)；协议变更同时读检索协议，删除或恢复同时读数据转移接口 |
| 行动进入周期分析、行动证据核对或删除传播 | [行动周期分析](docs/ACTION_PERIOD_REFLECTION.md)；协议变更读受控分析，恢复变更读数据转移接口 |
| 跨周期长期候选或画像变化摘要 | [纵向画像](docs/LONGITUDINAL_PROFILE.md)；生成同时读画像维护接口与检索协议 |
| 后台增量整理、调度、额度或恢复授权 | [后台分析](docs/BACKGROUND_ANALYSIS.md)、[四项后端计划](docs/plans/2026-10-19-backend-completion.md)；核验变化的 Android 官方资料 |
| 看板偏好持久化或迁移 | [看板偏好](docs/DASHBOARD_PREFERENCES.md)、数据转移接口 |
| 数据主存、迁移或索引边界 | [ADR 0001](docs/adr/0001-local-source-of-truth.md)、[ADR 0002](docs/adr/0002-controlled-evidence-retrieval.md) |
| 华为接入、饮食依据、复盘方法或画像研究 | 从[研究导航](docs/README.md)读取对应笔记，再核验会变化的官方资料 |
| 新功能排期、验收、提交或协作 | [开发路线](docs/ROADMAP.md)、[贡献约定](CONTRIBUTING.md) |
| 构建、运行、测试或修改 Android 代码 | [开发与验证](docs/DEVELOPMENT.md)；记录功能读 [M1 计划](docs/plans/2026-10-16-local-records.md) |
| 备份、恢复、导出格式、快照发布或数据迁移 | [数据转移接口](docs/DATA_TRANSFER.md)、[M2 计划](docs/plans/2026-10-16-backup-knowledge-export.md) |

## 必须保留的边界

- 实现状态以 [README.md](README.md) 和开发路线为准；仅以实际代码和验证结果报告能力，区分自动测试、APK 构建与真机验证。
- 主动分析页面已接通原文综合、分层归并及确认后核对发布；画像跨任务维护、纵向画像与行动周期分析的流程页面仍后置。新增接口与手机可用能力分别报告。
- 首版面向个人 Android 使用。通用历史资料导入、多用户平台和双向同步不在当前范围；模板导入与备份恢复是明确例外。
- 记录、观察、洞察与画像采用统一词汇和证据关系。模型输出、用户自述、设备测量及用户确认分别管理。
- 所有产品 AI 读取都经过程序执行的范围、快照、预算和证据登记；通过分层索引定位，禁止全库一次性灌入上下文。统计数值由明确口径的程序计算。
- 原始资料可靠保存后再分析；修订、删除和用户纠正向关联结果传播。派生更新保留用户已确认的独立文字。
- 个人真实记录、照片、健康数据、数据库、备份、导出文件和凭据存放在仓库之外。演示与测试资料使用明确标注的合成数据。

## 完成任务

- 修改对应权威文档，入口文档只更新摘要与链接；研究依据、产品推导、已实现能力分别说明。
- 依照 [CONTRIBUTING.md](CONTRIBUTING.md) 完成与改动相称的验证，报告实际运行结果和未验证边界。
- 提交前检查变更范围和暂存内容；输出交付物位置、验证结果，以及仍影响后续工作的具体依赖。
