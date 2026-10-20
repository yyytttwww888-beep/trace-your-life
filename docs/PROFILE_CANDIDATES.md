# 受控画像候选生成接口

本阶段从固定范围的原始记录生成近期状态或情境模式提案，随后独立核对，并由用户明确选择保存为画像候选。完整目标见[画像设计](superpowers/specs/2026-10-16-long-term-personal-profile-design.md)，范围和验证见[M4 第三阶段计划](plans/2026-10-19-profile-candidates.md)。后续[跨任务画像维护](PROFILE_MAINTENANCE.md)负责历史关系、反馈约束及范围内反例扫描；长期候选与修订摘要使用[纵向画像接口](LONGITUDINAL_PROFILE.md)；记录后台整理见[后台分析](BACKGROUND_ANALYSIS.md)。

## 建立任务与分批处理

应用入口是 `TraceLifeApplication.profileCandidates`，对应 `ProfileCandidateGenerator(repository, analysis, clock)`。在后台线程调用；这是受信任的应用编排接口，不向模型开放任意主存读取或写入。

```kotlin
val task = app.profileCandidates.start(
    AnalysisRequest(
        question = "从已记录的学习经历中，找出有明确情境限制的做法与体验。",
        scope = AnalysisQuery(
            LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18), "Asia/Shanghai",
        ),
        profileTarget = ProfileAnalysisTarget(
            ProfileDimension.ABILITIES_AND_STRATEGIES,
            ProfileLayer.CONTEXTUAL_PATTERN,
        ),
    ),
    id = stableRunId,
)
val report = app.profileCandidates.process(
    task.stored.id,
    generator = app.deepSeekProvider,
    reviewer = app.deepSeekProvider,
    maxModelRequests = 5,
    fragmentsPerBatch = 1,
)
val progress = app.profileCandidates.inspect(task.stored.id, limit = 25)
```

每个任务固定一个八维目标及一个层次；普通生成入口接受 `RECENT_STATE`、`CONTEXTUAL_PATTERN`；`LONG_TERM` 必须通过[纵向画像](LONGITUDINAL_PROFILE.md)的周期计划和跨期校验，旧格式仍拒绝。可以为其他维度单独建立有界任务，不能将八维分析解释为已经取得全部生活资料。范围中的未知日期、跨期记录和未分类资料仍按原协议处理，主题仅作关注提示。

目标以 `AnalysisRequest.profileTarget` 保存，重启和恢复不依赖解析问题文字。相同任务 ID 与相同计划重试复用已有任务；使用旧 ID 改变范围或目标会被拒绝。来源变化后原任务停止，新任务必须显式建立。

每轮先处理已有待核对提案，再沿元数据索引读取下一批未处理原文。生成与核对共用 `maxModelRequests=1..100`，单个生成批次最多 `fragmentsPerBatch=1..32` 个片段；单请求和累计预算仍由[受控分析](CONTROLLED_ANALYSIS.md)强制执行。默认每批 1 个片段，为整批重读核对保留输入空间；显式扩大批次时须同时为生成和核对提高任务输入预算。生成预算不足时减小批次，核对不能删掉必要原文来凑预算。较长候选或后续用户反馈仍可能使核对超过预算，此时保留已有进度并报告 `BUDGET_EXHAUSTED`；任务预算不可原地修改，须明确建立预算更充足的新任务。

处理不会自动确认陈述、发布知识或采用画像。取消只阻止后续派发；已经发送的请求无法撤回。失败保留安全原因码和进度，不自动重试付费请求；遗留在途调用使用 `analysis.recoverInterrupted(runId)` 后显式继续。

## 查看候选与证据

`inspect(runId, offset, limit)` 提供有界元数据页，不返回整批陈述或原文；`limit=1..100`。使用 `next(progress)` 翻页、`candidate(progress, claim)` 读取本页登记的单条提案，二者校验来源、分析代数、任务版本和时间。也可按已知身份调用 `candidate(runId, claim, expectedRunVersion)`；条件变化后须重新查看。原文仍沿受控分析的范围与字段入口定位，已物化条目也可以使用[画像证据接口](PERSONAL_PROFILE.md#证据和反馈边界)。

状态分别说明待核对、当前核对意见、待用户确认、用户否定／纠正／撤回、已删除、可保存或已有画像决定。模型的 `SUPPORTED` 仅表示有限证据下的意见，不是事实认证；用户认可也不增加独立原始事件。

生成只能引用本次登记的原文，所有陈述均为 `POSSIBLE_INTERPRETATION`。提示词要求模型保留自述性质、具体情境、支持与反例、替代解释和未知；画像目标、旧画像或用户采用决定不充作新原始证据。

独立核对重读整个生成批次的全部原文，不能只重读模型选择的支持引用。批内重读不代表已经搜索全库反例；`counterevidenceSearchExhaustive=false`。生成流程自身的 `crossTaskSemanticDeduplication=false`、`manualDeletedSlotTracking=false` 分别说明此步骤没有完成跨任务比较，也未跟踪已永久删除的手工组织位置；后续维护通过独立计划检查当前可用历史。本接口的多批处理明确报告尚未跨批整合；长期任务由[纵向整体整理](LONGITUDINAL_PROFILE.md)继续形成覆盖全部批次的根候选，仍不直接成为正式画像。

## 明确确认与保存

用户查看提案后，经[共享反馈接口](CLAIM_REVIEW.md)明确确认、纠正或否定。确认会产生新的反馈版本，先前核对因此失效，需要重新核对当前版本。以下为已经由用户明确选中并确认的陈述：

```kotlin
val confirmation = app.analysisFeedback.respond(
    runId = runId,
    claim = selectedClaim,
    expectedRunVersion = app.analysis.task(runId).stored.version,
    decision = ClaimFeedbackDecision.CONFIRMED,
)
app.analysis.reviewClaim(
    runId, reviewOperationId, selectedClaim, provider = app.deepSeekProvider,
)
val saved = app.profileCandidates.materialize(
    runId, selectedClaim, expectedRunVersion = app.analysis.task(runId).stored.version,
)
```

存在适用历史画像或纠正反馈时，`materialize` 会阻止直接新建，须改用[维护与明确应用](PROFILE_MAINTENANCE.md)。没有适用历史时，`materialize` 重新检查任务范围、来源、版本、期限、确认与最新支持核对，条件成立才将陈述发布为共享 `INSIGHT` 并保存为 `CANDIDATE`。机器正文和证据仍只由共享知识维护。候选默认不进入当前画像；用户另行通过 `personalProfile.adopt` 采用。

重复保存使用稳定身份，不重复增加知识或画像修订。已有条目的采用决定、独立文字和当前修订保留；不会把 `DECLINED` 或 `SUPERSEDED` 重置成候选，也不自动改绑另一个知识版本。发布与创建的仓储桥接保持事务一致，条件变化或容量失败不留下部分保存。

物化结果 `status` 区分 `CREATED`、`EXISTING`、`DELETED`、`CONFLICT`，`profile` 返回实际条目 ID／当前修订，`knowledge` 返回固定知识引用，`adoption` 返回已有采用决定；`DELETED` 时这三个可空字段均为空。`EXISTING` 不表示新建成功或已被采用；`CONFLICT` 须通过既有维护接口明确处理，不能重试时改用随机 ID。

由此流水线创建的画像被永久删除后，其稳定 ID 不能再次使用。现存同知识／同维度／同层次的手工条目也会被识别并保留。本阶段没有跨任务同义判断合并：永久删除的手工自定义 ID 不再保存原组织位置，不能据此识别后来显式选中的同义提案；这一限制与稳定身份幂等分别报告。旧的否定不会被流程自动恢复成当前画像。

## 维护和迁移

任务期间来源修订、补记、删除或到期，会阻止旧任务继续生成或保存；时钟回拨到任务更新时间之前也不能派发或物化。反馈变化使相关旧核对失效；确认后的新版本须重新核对，否定、纠正、撤回或永久删除反馈会阻止对应候选保存，其余未处理批次仍可继续。已发布知识与画像继续使用现有失效传播，用户独立文字按原规则保留。当前由调用方显式启动和续跑，没有 Android 定时任务，也不会在保存日记后自动联网。

分析载荷 v8 引入近期／情境画像目标，v9 增加后续维护计划及调用凭据，v10 增加长期目标与比较周期；旧版本不能夹带新字段。画像载荷 v2 兼容 v1。当前数据库与完整备份格式见[数据转移](DATA_TRANSFER.md)，长期整体整理的新增载荷见[纵向画像](LONGITUDINAL_PROFILE.md)。完整加密备份保留候选任务；恢复后按历史来源处理，不自动重跑或重新确认。普通阅读包省略分析提案和未采用画像；只有已采用、仍有效且共享知识也被纳入的条目进入画像阅读目录。

真实 DeepSeek 验证采用独立默认关闭的开关，只使用合成资料；命令和实际边界见[DeepSeek 接入](DEEPSEEK.md)。接口自动化测试、真实模型小样本和真机验收分别记录。

新候选已确认、局部核对支持、尚未物化且适用历史目录非空时，状态返回 `MAINTENANCE_REQUIRED`；必须经[跨任务维护](PROFILE_MAINTENANCE.md)比较、扫描并明确应用，普通局部核对通过仍不能直接物化。

## 手机任务接续

近期／情境生成表单保留输入、维度、层次和日期，页面重建恢复任务身份与候选页；恢复不会重新请求模型。候选详情现可进入[历史对照与反例检查](PROFILE_MAINTENANCE.md#手机核对流程)，长期入口及包括近期任务在内的分页历史见[跨阶段手机流程](LONGITUDINAL_PROFILE.md#手机跨阶段整理)。普通候选保存与画像采用仍是两个独立操作。
