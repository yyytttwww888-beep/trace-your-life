# 主动分析与发现保存

手机上的「分析」入口串联跨记录综合、分层归并、来源查阅及确认后的知识保存。交互目标见[本轮设计](superpowers/specs/2026-10-20-visible-reflection-design.md)，原文与派生内容的边界沿用[综合接口](SYNTHESIS_ACTIONS.md)、[分层归并](PERIOD_REDUCTION.md)及[周期核对](PERIOD_REVIEW.md)。

## 使用流程

首页「分析记录」进入日期选择，支持今天、近 7 天、近 30 天与自定义日期；记录详情和回顾页面也能带着日期进入。点击「开始分析」并确认本次在线调用后执行，一轮最多 5 次请求。普通「逐条整理」仍保留片段提取入口，两种操作分别保存任务。

结果按明确前缀显示值得留下的经验、值得关注的问题、可以尝试的改进；格式不匹配的内容留在其他发现。模型可以返回空结果，页面不根据记录缺失自行评价用户。部分综合结果会标明尚未完成，整合完成后才提供确认和保存。

点击一条发现可查看其生成批次的原文与上下文，分页阅读；多批成果沿完整来源链读取。选择「保留到知识库」表示用户确认，随后独立重新核对原文；只有最新核对支持时发布为洞察。可手动填写主题。否定不会被自动覆盖，单批多条发现可分别处理。原文变化、删除、反馈变化和超预算继续遵循原协议。

分析历史分页读取现有台账，旋转、返回和重新打开只读进度，不启动付费请求。中断恢复和重试分别由用户触发。首页展示最新分析范围、状态、发现数量与保存数量；历史失效结果保留阅读入口。知识库显示正文摘要，可切换发现、经验、观察及全部条目；待更新内容有明确入口，不作为当前结论。

## 应用接口

以下接口在应用 IO 线程调用，页面正文读取不开放给模型。

```kotlin
val run = app.reflectionWorkflow.start(AnalysisQuery(from, through, zoneId))
val pass = app.reflectionWorkflow.process(
    run.id, app.deepSeekProvider, maxRequests = 5,
    onProgress = { current -> /* 每次持久化进度后通知页面 */ },
)
val current = app.reflectionWorkflow.inspect(run.id)
val history = app.reflectionWorkflow.history(limit = 20, offset = 0)
```

`ReflectionRun` 返回状态、固定范围、文字覆盖、综合与归并批次数、累计请求、发现及缺口。`canReviewFindings` 表示当前最终结果可逐项处理，不代表任何发现已成立。`ReflectionPass.status` 还可能携带本轮预算或冲突状态，页面必须显示它，不能只显示 `run.status`。

`respond` 与 `reviewAndSave` 分开：前者需要用户实际查看的任务版本、目标身份及反馈版本，后者要求有效确认，沿普通原文核对或归并扫描／最终核对继续。生成阶段不发布、不自动确认、不采纳行动。`evidence` 只返回分页引用，`original` 限于登记片段，检查范围、版本及完整片段哈希。

任务使用 `reflection-v1-` 身份和固定版本的问题标记，保存在既有分析台账，沿现有完整备份保存。更改固定问题时需显式考虑历史识别，不可让旧任务从入口消失。没有新增数据库表或备份格式。

## 当前边界

- 新入口分析所选日期范围的原始文字，暂未把行动计划和回看并入此页面；已有行动周期后端保持独立。
- 用户选择保存的经验性解释仍按洞察协议发布；自动知识类别／主题识别与完整主题档案尚未实现。
- 来源版本仍为全库级，新增原始资料可能使旧任务失效。本轮让历史可见，未宣称已完成细粒度失效重算。
- 主任务台账最多 100 个任务，达到容量明确失败；不会偷偷删除分析历史来继续收费。分页不改变这个上限。
- 结果质量受实际输入和模型影响。分批完成、引文可达和模型核对均不能替代真实使用评估。默认只处理文字，图片及健康数据缺口如实保留。

## 验证入口

`ReflectionWorkflowTest` 检查实际综合与归并编排、请求额度、重启恢复、独立确认和否定、原文分页及历史。`ReflectionActivityTest`、`MainActivityTest`、`KnowledgeActivityTest` 检查真实页面操作。

显式合成 DeepSeek 验证最多 1 次综合和 1 次原文核对，普通测试默认跳过，不读取密钥：

```bash
./gradlew testDebugUnitTest --tests 'dev.tracelife.providers.DeepSeekReflectionLiveTest' \
  -PtraceLifeDeepSeekReflection=true
```

凭据使用[已有仓库外配置](DEEPSEEK.md#配置-key-后的开发机验证)。实际运行结果统一记录在[开发与验证](DEVELOPMENT.md#主动分析闭环验证)。
