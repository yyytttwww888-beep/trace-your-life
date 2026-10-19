# 后台增量整理接口

本接口接收明确启用之后新增、修改和恢复归档的记录，分批提取并核对提案。它不自动确认、正式发布知识、创建行动或采用画像；行动周期综合和纵向画像仍从各自接口明确启动。对应[后端闭环计划](plans/2026-10-19-backend-completion.md)，模型执行遵循[受控分析接口](CONTROLLED_ANALYSIS.md)。尚未接入设置页面。

## 启用、暂停与查询

应用入口为 `TraceLifeApplication.incrementalOrganizer`，全部方法在后台线程调用。策略默认关闭，当前设备的 DeepSeek 凭据另由[密钥接口](DEEPSEEK.md)配置。

```kotlin
val current = app.incrementalOrganizer.settings()
val enabled = app.incrementalOrganizer.configure(
    BackgroundPolicy(
        enabled = true,
        zoneId = "Asia/Shanghai",
        maxRequestsPerPass = 3,
        dailyRequestLimit = 10,
        maxRequestsPerJob = 20,
        unmeteredOnly = true,
    ),
    expectedVersion = current.version,
)
val jobs = app.incrementalOrganizer.jobs(offset = 0, limit = 25)
app.incrementalOrganizer.pause()
app.incrementalOrganizer.resume()
```

首次启用以当前变更事件 ID 为起点，不默认读取所有旧记录。暂停保留作业和事件起点；恢复后会补处理暂停期间的事件。停用会撤销本机队列意图，再次启用建立新的授权起点；停用期间和之前的资料不自动补发。停用不删除已保存的分析历史，也不重置当天消费额度。配置更新使用版本检查，过期版本不能覆盖后来设置。

`jobs` 默认每页 25 项、最多 100 项，只有记录身份、修订、事件、分析任务 ID、状态和消费计数，不复制记录或模型文字。按 `runId` 通过原分析接口查看提案、原文引用、核对与缺口。`PROPOSALS_READY` 只表示已处理文字和现有核对，不表示已发布、完整生活覆盖或语义必然正确。

## 持久队列与 Android 调度

主库原有 `change_events` 随记录写入同一事务，充当可重放的变更登记。应用启动、记录保存后的唤醒和 Android 周期工作读取其有界元数据页；即使保存后立即退出、未完成唤醒登记，已注册的周期工作和下一次启动仍可补扫。每轮最多读取 100 个事件，重复读取不会重复创建同记录修订的作业。同页多次修改合并到最新修订，旧待处理作业标为被替代；已删除或归档的记录不发给模型。

策略、事件游标、作业和消费预留保存在 `noBackupFilesDir/incremental-analysis/queue.db`。操作库不保存原文、模型结果或 API KEY；结果仍在主库受控分析台账中。目录最多 5,000 个作业，达到上限停止而不是丢弃未处理事件。`forget(jobId, expectedVersion)` 仅删除已结束的操作条目，要求事件游标已追上当前登记，避免尚未处理的同修订事件重建已清理作业；不删除分析历史。

`BackgroundScheduler` 使用唯一周期工作和唯一即时唤醒，周期最小为 15 分钟，默认要求非计费网络、电量非低。实际执行受系统调度和节电限制，不保证保存后立即运行或固定整点运行。策略暂停或关闭时取消未来工作；已经发送的请求无法撤回。实现采用 WorkManager 2.11.2，[官方依据与验证边界](research/2026-10-19-android-background-analysis.md)单独记录。

调度器只传网络约束标志，不把记录正文、凭据或记录清单放入 WorkManager 输入。工作绑定登记时的网络条件；执行中收紧或改变策略会阻止下一次派发，旧条件的已排队工作也不能执行新策略。后续由带新条件的周期工作续跑。`IncrementalWorker` 每次运行一轮，领域失败保存后返回，不使用 `Result.retry()` 触发额外付费请求。周期调度可继续处理未失败的其他作业，但失败或遗留在途的作业不会自动重试。

## 请求、配额与恢复

每轮最多 1–10 次请求；UTC 每日额度为 1–1,000 次；每个记录修订作业最多 1–100 次，默认分别为 3、10、20。每日窗口固定 UTC，不会因为改变展示时区而再获得一份额度；它是请求数上限，不是金额上限。每次模型派发前先持久预留，失败和未知是否完成的请求仍消耗额度。极小窗口内预留成功但尚未派发就发生停止时，额度保守保留。

每条作业固定一个记录修订，经过同一 `ControlledAnalysis` 字段枚举、分片、完整输入预算和证据校验。先核对已有提案，再读取下一片段；负面核对保留，不能自动反复请求直到得到支持。用户否定、纠正、撤回或永久删除反馈会阻止对应自动核对；用户确认仍是独立操作。原文未知日期、区间、未读附件和不可读内容不会被伪装成完整覆盖。

现有来源版本为全库级：其他原始资料写入也可能使旧任务失效。没有未决付费调用时允许为同作业建立新受控任务，已有调用保留历史，作业累计额度不重置。不能据此宣称已实现无关分支调用复用。主库的 100 个分析任务及字节容量限制仍有效；达到容量返回 `STORAGE_BLOCKED`，不会自动删除旧任务来继续付费。

跨进程文件锁防止并发派发；每次选中作业先持久保存 `RUNNING`。进程终止后下一执行者拿到锁，将遗留作业标为 `NEEDS_RECOVERY`，不猜测上一请求未计费。发生失败或中断后显式调用：

```kotlin
val job = app.incrementalOrganizer.jobs().first { it.status == BackgroundJobStatus.NEEDS_RECOVERY }
app.incrementalOrganizer.retry(job.id, expectedVersion = job.version)
app.backgroundScheduler.wake()
```

`retry` 是允许再次请求的独立操作，保留既有额度；在途受控调用会按原恢复接口登记为中断。预算耗尽时必须先明确提高该作业上限才可重试；每日额度耗尽的未失败作业可在下一 UTC 日继续。时钟回拨时停止派发，不能通过回拨重置消费。`process(extractor, reviewer, credentialsAvailable, cancelled)` 可供受控应用编排和合成测试调用；产品 Worker 在派发前检查凭据、策略和停止信号。

## 备份、恢复与边界

本机调度策略、消费预留、WorkManager 工作和操作队列不进入个人备份；分析任务、证据与提案照常进入主库完整备份。schema v8 的 `restore_epoch` 是本机恢复栅栏，不是迁移数据。每次恢复事务递增它，后台读取和每次派发前再次检查；变化时关闭策略、清除旧队列意图，要求重新启用。当日已经预留的额度继续保留。

这避免换机或恢复旧备份把以前的后台网络授权一起恢复。已经发出的外部请求和已导出文件无法收回；当前恢复后的历史分析仍受原来源有效性规则限制。仅恢复资料不会自动开始重新分析整库。

自动测试分别覆盖本机 SQLite 持久化、请求上限、失败／崩溃、暂停、恢复隔离、来源变化及 WorkManager 的唯一工作和约束。实际运行结果在[阶段计划](plans/2026-10-19-backend-completion.md#验证记录)记录；Robolectric 不能代替 Android 真机的后台限制、断网、重启或厂商节电验证。
