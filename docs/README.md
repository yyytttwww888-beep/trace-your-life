# Trace Life 文档入口

当前推进手机上的主动分析与发现保存闭环，餐食照片分析暂缓；代码及页面接入状态见[项目入口](../README.md)与开发路线。设计描述完整目标；研究笔记区分一手来源事实、访问限制和产品推导，不等同于已验证的实现能力。

## 开始与协作

- [项目概览](../README.md)：产品范围与当前实现状态。
- [AGENTS.md](../AGENTS.md)：AI Agent 按任务读取文档的入口。
- [开发路线](ROADMAP.md)：实施阶段、依赖与验收。
- [贡献约定](../CONTRIBUTING.md)：文档权威位置、GitHub 工作流和验证要求。
- [开发与验证](DEVELOPMENT.md)：工具链、构建、测试、APK 安装与代码入口。
- [M1 实施计划](plans/2026-10-16-local-records.md)：离线记录与模板的范围和验证。
- [M2 实施计划](plans/2026-10-16-backup-knowledge-export.md)：备份恢复与知识导出的实现约束。
- [数据转移接口](DATA_TRANSFER.md)：调用方式、恢复语义、格式和当前上限。
- [M3 第一阶段计划](plans/2026-10-16-controlled-analysis.md)：受控取证与可恢复分析提案的范围和验证。
- [受控分析接口](CONTROLLED_ANALYSIS.md)：路由、请求预算、证据台账、恢复及后续验收条件。
- [DeepSeek 接入](DEEPSEEK.md)：密钥配置、分批整理、预算计量与真实测试入口。
- [M3 第二阶段计划](plans/2026-10-17-deepseek-analysis.md)：模型适配与分批整理的实现范围。
- [核对与反馈接口](CLAIM_REVIEW.md)：原文核对、用户纠正、反馈有效性与删除规则。
- [M3 第三阶段计划](plans/2026-10-17-evidence-review-and-feedback.md)：证据核对与用户反馈的实现和验收。
- [知识与指标接口](KNOWLEDGE.md)：条件发布、分层查阅、依赖失效与确定性记录指标。
- [M3 第四阶段计划](plans/2026-10-17-knowledge-publication.md)：正式知识、阅读索引及记录指标的实施与验证。
- [自动整理与周期复盘材料](PERIOD_REFLECTION.md)：有界执行、材料分组、指标证据、原文下钻和报告导出。
- [M3 第五阶段计划](plans/2026-10-17-period-reflection.md)：自动整理、周期材料与导出的实施及验证。
- [综合与行动接口](SYNTHESIS_ACTIONS.md)：分批原文对照、独立核对、明确采纳、执行与回看。
- [主动分析与发现保存](REFLECTION.md)：手机分析入口、真实进度、结果阅读、确认保存及历史分页。
- [M3 第六阶段计划](plans/2026-10-17-synthesis-actions.md)：有界综合及行动实验的实施与验证。

- [分层周期草稿](PERIOD_REDUCTION.md)：跨批归并、依赖失效、草稿读取与原文下钻。
- [周期原文核对与发布](PERIOD_REVIEW.md)：完整来源扫描、相关原文最终核对、用户确认与依赖维护。
- [M3 第七阶段计划](plans/2026-10-19-period-reduction.md)：分层草稿的实现与验证。
- [M3 第八阶段计划](plans/2026-10-19-period-review-publication.md)：周期核对与确认发布的实现及验证。
- [受控指标分析](METRIC_ANALYSIS.md)：程序统计、模型引用、重算核对及洞察发布。
- [M3 第九阶段计划](plans/2026-10-19-metric-evidence.md)：指标凭据、恢复和范围导出验收。
- [看板数据接口](DASHBOARD.md)：7／30／90 天趋势、来源下钻、全局行动、快照有效期及导出。
- [M4 第一阶段计划](plans/2026-10-19-dashboard-data.md)：看板数据与行动回看视图的实施及验证。
- [画像采用与维护接口](PERSONAL_PROFILE.md)：八维分类、明确采用、独立文字、历史和证据定位。
- [M4 第二阶段计划](plans/2026-10-19-personal-profile.md)：画像保存、维护与迁移的实施及验证。
- [受控画像候选生成](PROFILE_CANDIDATES.md)：指定维度的原文分批、独立核对与明确保存。
- [M4 第三阶段计划](plans/2026-10-19-profile-candidates.md)：画像生成范围、采用边界及验证。
- [跨任务画像维护](PROFILE_MAINTENANCE.md)：历史关系、反馈约束、反例扫描及明确更新。
- [M4 第四阶段计划](plans/2026-10-19-profile-maintenance.md)：维护范围、更新与恢复验收。

- [行动周期分析](ACTION_PERIOD_REFLECTION.md)：独立行动证据、综合、全批核对及依赖维护。
- [行动归并与知识包计划](plans/2026-10-19-action-report-export.md)：多批整体归并、双类型原文扫描、固定行动证据导出与验证。
- [纵向画像与变化摘要](LONGITUDINAL_PROFILE.md)：多周期分批候选、跨期维护门槛及修订比较。
- [后台增量整理](BACKGROUND_ANALYSIS.md)：启用、调度、持久额度、失败恢复及恢复授权。
- [看板偏好](DASHBOARD_PREFERENCES.md)：偏好版本、默认值、保存与迁移。
- [四项后端实施计划](plans/2026-10-19-backend-completion.md)：范围和统一验证记录。

## 架构决策

- [ADR 0001：本地权威资料与可迁移视图](adr/0001-local-source-of-truth.md)。
- [ADR 0002：执行层控制的证据检索](adr/0002-controlled-evidence-retrieval.md)。

## 产品设计

- [主设计：本地存储与导出、知识库、复盘、看板及第一版范围](superpowers/specs/2026-10-16-personal-knowledge-and-reflection-design.md)
- [长期个人画像：维度、分析、证据与更新](superpowers/specs/2026-10-16-long-term-personal-profile-design.md)
- [强制检索协议：分层索引、取证、维护与发布](superpowers/specs/2026-10-16-knowledge-routing-and-reliability-design.md)
- [统一领域词汇](../CONTEXT.md)

## 研究依据

| 主题 | 研究笔记 |
| --- | --- |
| 分层检索、长上下文与可靠性边界 | [检索与可靠性调研](research/2026-10-16-hierarchical-retrieval-reliability.md) |
| DeepSeek 接口、token 计量与资料处理 | [DeepSeek 官方资料核验](research/2026-10-17-deepseek-integration.md) |
| 画像维度、人格与价值观框架 | [框架调研](research/2026-10-16-personal-profile-frameworks.md) |
| 个体纵向分析、情境与证据维护 | [纵向证据调研](research/2026-10-16-longitudinal-profile-evidence.md) |
| 深度复盘的成熟方法 | [AAR、无责复盘与 PDSA](research/2026-10-16-reflection-methods.md) |
| 拍照饮食分析的依据与范围 | [饮食指南及分析边界](research/2026-10-16-dietary-photo-analysis.md) |
| Android 持久后台任务 | [WorkManager 官方核验](research/2026-10-19-android-background-analysis.md) |
| Android 与华为健康数据接入 | [官方接入途径及待核实条件](research/2026-10-16-huawei-health-integration.md) |

同一设计结论在对应设计文件维护，主设计提供摘要和链接；研究材料保留核验日期、实际阅读范围与来源，避免把摘要再次当作新的研究证据。

## 跨期经验与长期使用

- [行为设计](superpowers/specs/2026-10-20-experience-memory-and-archive-design.md)与[实施计划](plans/2026-10-20-experience-memory-and-archive.md)。
- [经验线索](EXPERIENCE_CONTINUITY.md)：固定知识修订、变化过程、独立说明及导出范围。
- [历史意见](HISTORICAL_FEEDBACK.md)：目录路由、有限上下文、人工对照与发布门槛。
- [分析归档](TASK_ARCHIVE.md)：生命周期、分页、容量、备份与恢复。
