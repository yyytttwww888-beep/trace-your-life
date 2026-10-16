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
| 画像维度、更新、用户反馈或长期判断 | [画像设计](docs/superpowers/specs/2026-10-16-long-term-personal-profile-design.md)；涉及生成和维护时同时读检索协议 |
| 数据主存、迁移或索引边界 | [ADR 0001](docs/adr/0001-local-source-of-truth.md)、[ADR 0002](docs/adr/0002-controlled-evidence-retrieval.md) |
| 华为接入、饮食依据、复盘方法或画像研究 | 从[研究导航](docs/README.md)读取对应笔记，再核验会变化的官方资料 |
| 新功能排期、验收、提交或协作 | [开发路线](docs/ROADMAP.md)、[贡献约定](CONTRIBUTING.md) |
| 构建、运行、测试或修改 Android 代码 | [开发与验证](docs/DEVELOPMENT.md)；记录功能读 [M1 计划](docs/plans/2026-10-16-local-records.md) |
| 备份、恢复、导出格式、快照发布或数据迁移 | [数据转移接口](docs/DATA_TRANSFER.md)、[M2 计划](docs/plans/2026-10-16-backup-knowledge-export.md) |

## 必须保留的边界

- 实现状态以 [README.md](README.md) 和开发路线为准；仅以实际代码和验证结果报告能力，区分自动测试、APK 构建与真机验证。
- 当前按用户要求优先完成后端功能和验证，前端统一后置；新增接口与已接入页面的能力分别报告。
- 首版面向个人 Android 使用。通用历史资料导入、多用户平台和双向同步不在当前范围；模板导入与备份恢复是明确例外。
- 记录、观察、洞察与画像采用统一词汇和证据关系。模型输出、用户自述、设备测量及用户确认分别管理。
- 所有产品 AI 读取都经过程序执行的范围、快照、预算和证据登记；通过分层索引定位，禁止全库一次性灌入上下文。统计数值由明确口径的程序计算。
- 原始资料可靠保存后再分析；修订、删除和用户纠正向关联结果传播。派生更新保留用户已确认的独立文字。
- 个人真实记录、照片、健康数据、数据库、备份、导出文件和凭据存放在仓库之外。演示与测试资料使用明确标注的合成数据。

## 完成任务

- 修改对应权威文档，入口文档只更新摘要与链接；研究依据、产品推导、已实现能力分别说明。
- 依照 [CONTRIBUTING.md](CONTRIBUTING.md) 完成与改动相称的验证，报告实际运行结果和未验证边界。
- 提交前检查变更范围和暂存内容；输出交付物位置、验证结果，以及仍影响后续工作的具体依赖。
