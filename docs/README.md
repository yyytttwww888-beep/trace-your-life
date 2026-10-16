# Trace Life 文档入口

当前处于产品设计阶段，应用尚未实现。设计草案描述建议方案；研究笔记区分一手来源事实、访问限制和产品推导，不等同于已验证的实现能力。

## 开始与协作

- [项目概览](../README.md)：产品范围与当前实现状态。
- [AGENTS.md](../AGENTS.md)：AI Agent 按任务读取文档的入口。
- [开发路线](ROADMAP.md)：实施阶段、依赖与验收。
- [贡献约定](../CONTRIBUTING.md)：文档权威位置、GitHub 工作流和验证要求。

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
| 画像维度、人格与价值观框架 | [框架调研](research/2026-10-16-personal-profile-frameworks.md) |
| 个体纵向分析、情境与证据维护 | [纵向证据调研](research/2026-10-16-longitudinal-profile-evidence.md) |
| 深度复盘的成熟方法 | [AAR、无责复盘与 PDSA](research/2026-10-16-reflection-methods.md) |
| 拍照饮食分析的依据与范围 | [饮食指南及分析边界](research/2026-10-16-dietary-photo-analysis.md) |
| Android 与华为健康数据接入 | [官方接入途径及待核实条件](research/2026-10-16-huawei-health-integration.md) |

同一设计结论在对应设计文件维护，主设计提供摘要和链接；研究材料保留核验日期、实际阅读范围与来源，避免把摘要再次当作新的研究证据。
