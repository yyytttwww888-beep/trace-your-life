# 数据转移接口与格式

本文件描述数据转移的实际接口，包括分析、核对、用户反馈、正式知识、行动与画像的备份边界，以及周期材料和看板导出。产品目的与完整范围见[主设计](superpowers/specs/2026-10-16-personal-knowledge-and-reflection-design.md)，初始实现见[M2 计划](plans/2026-10-16-backup-knowledge-export.md)。知识发布与有效状态见[正式知识接口](KNOWLEDGE.md)。手机已接入备份、阅读文档、看板及周期材料四个导出入口，共用完整本机副本与系统「另存为」交付；调用须在后台线程执行，不能把整库转移接口提供给模型。

## 调用入口

应用提供 `TraceLifeApplication.dataTransfer`；也可用 `DataTransfer(context, repository)` 独立构造。密码由调用者持有，接口不保存或记录密码，调用者在完成后清空 `CharArray`。

```kotlin
// 在后台线程；chosenDirectory 是调用者已有写权限的目录。
val backup = File(chosenDirectory, "trace-life-backup.tlb")
val saved = transfer.exportBackup(backup, recoveryPassword)

// 验证备份并显示其内容范围。预览绑定备份哈希、目标库身份、来源和分析变更代数。
val preview = transfer.previewBackup(backup, recoveryPassword)
val restored = transfer.restoreBackup(backup, recoveryPassword, preview)

val reading = transfer.exportReadingPackage(
    File(chosenDirectory, "trace-life-knowledge.zip"),
    ReadingExportOptions(includeAttachments = false),
)
val evidence = transfer.exportReadingPackage(
    File(chosenDirectory, "trace-life-evidence.zip"),
    ReadingExportOptions(
        kind = ReadingPackageKind.EVIDENCE,
        question = "指定期间有哪些学习记录？",
        scope = ExportScope(
            from = LocalDate.of(2026, 10, 1),
            through = LocalDate.of(2026, 10, 16),
            topics = setOf("learning"),
        ),
    ),
)
```

默认恢复只允许空资料库。目标存在记录、模板、分析任务、用户反馈、正式知识、行动或画像时，须由调用者明确选择替换后传入 `replaceExisting = true`；初始化过内置模板的新安装也适用。替换保留目标库身份、已知删除标记和目标库独立用户反馈，知识、行动与画像按下述完整修订链规则合并；原始记录等资料使用备份内容。不会把替换自动解释为跨设备双向同步。

发生新写入、目标库不同或备份文件变化后，旧预览失效；只更新分析任务、用户反馈、知识、行动或画像也会使预览失效。`RestorePreview.analysisRuns`、`claimFeedback`、`knowledge`、`actions` 与 `profiles` 分别表示备份中的任务数、反馈数及三类当前指针数，`targetAnalysisGeneration` 绑定目标派生资料状态。调用者重新预览并确认。恢复密码错误、格式无效或附件缺失时不会修改目标资料。文件接口拒绝覆盖已有导出路径，调用者为每份导出提供新文件名。

通用 `File` 接口不能直接接收 `content://` URI，其无覆盖发布契约保留。手机使用独立 `PrivateExportDestination` 重载生成完整副本，再通过系统 `ACTION_CREATE_DOCUMENT` 选择新文档并交付；具体保证见下一节。真正用于卸载／换机恢复的最终备份须另存到应用私有目录之外，本机副本会随卸载删除。

## 一致性与中断

SQLite schema v9 保留原有资料并原地升级：v2 增加主库身份、来源变更代数和模板删除标记，v3 增加分析任务与独立分析变更代数，v4 增加独立反馈及其删除台账，v5 增加不可变知识修订、当前指针及知识删除台账，v6 增加行动修订、当前指针和删除台账，v7 增加画像修订、当前指针与删除台账，v8 增加看板偏好及本机恢复代数，v9 增加来源范围变更台账与可信代数下界。分析、反馈、知识、行动、画像和看板偏好写入不会修改来源代数；导出和恢复同时检查来源与分析两种代数。反馈还递增关联任务版本以阻止旧模型结果写回。触发器覆盖相关表写入，普通查询不使快照失效。采集快照时，在同一事务和主库操作锁下读取逻辑资料并核对附件；不复制正在写入的数据库文件。

来源台账只保存身份、日期和归档元数据，记录变更前后范围；任务和知识保留原来源戳，通过它判断后续变化是否相关。升级将可信下界设为迁移时来源代数，不会猜测升级前已失效任务重新有效。该台账与下界不进入逻辑备份；恢复后清空台账，并将下界推进到全部导入来源戳之后。全库代数仍用于导出整体快照，不因任务范围失效规则变细而放宽发布事务。

生成文件先放入应用 `noBackupFilesDir/transfer-staging` 下的独立工作目录；输出前复制并刷盘至目标旁的临时文件。通用 `File` 接口继续在快照检查后使用同目录硬链接原子创建最终文件名，目标在预检查后被其他写入创建也不会被覆盖；文件系统不支持硬链接时明确失败，不降级为部分发布。该接口拒绝写入手机导出的专用目标目录。底层仓储调用者负责在发布后清理自己持有的暂存文件，不能再修改它。所有权登记在写入临时输出内容前持久保存，`recoverInterruptedTransfers()` 只回收该任务的临时文件，不删除已发布文件。构造模块时执行一次恢复，并与当前进程正在执行的转移串行。

手机导出通过 `CompletedExports.create` 取得不能由任意路径构造的 `PrivateExportDestination` 能力对象。每次在 `noBackupFilesDir/completed-exports/` 下分配新的独占目录，先生成完整文件、计算长度与 SHA-256，并刷盘保存 `complete.json` 收据，再于来源／分析代数和有效期检查的主存事务内原子 rename 发布。独占目录只允许持有该对象的写入者，通用 `File` 接口不能绕过它写入，因此没有共享目标的替换竞态。这是专用发布协议，不是对任意不支持硬链接路径的降级。未完成目录不进入可交付列表。

```kotlin
val completed = CompletedExports(context)
val (result, localFile) = completed.create("knowledge") { destination ->
    transfer.exportReadingPackage(destination, ReadingExportOptions(includeAttachments = false))
}
// 用户通过 ACTION_CREATE_DOCUMENT 选定新文档后，在后台线程交付。
val delivered = DocumentExportDelivery(context.contentResolver).save(
    localFile, newlyCreatedDocumentUri, completed.expectedHash(localFile.name),
)
```

备份、知识／证据阅读包、看板和周期材料均采用上述本机发布与交付路径。系统另存是已完成历史副本的外部复制：写入、刷新并关闭输出流之后重新读取目标，字节数、复制内容哈希和本机完成收据全部匹配才报告成功。文档提供方可能在写入期间暴露部分内容，不保证原子提交；失败会尝试删除新建的不完整文档，删除失败则明确提示可能有残留。取消选址、关闭失败、截断或校验失败不会删除完整本机副本，可从「已生成的文件」重新另存，应用重启后仍可继续。该列表也允许明确删除本机副本，不影响原始资料或已另存文件。

恢复先复制输入并固定其哈希，完成解密、清单和逻辑校验，再将完整附件准备好，最后在一个数据库事务内替换资料。失败事务不删除旧的有效附件，新产生的无引用文件可清理。提交后的清理失败保留登记供下次重试，不把已提交恢复报告成回滚。

任何来源或分析变化均会使尚未发布的导出失效，包括删除。目标已知删除标记优先于备份；记录的全部历史及专属附件随该规则排除。模板已删除而待恢复记录仍引用它时拒绝该冲突备份。恢复按原文取证回执、完整指标来源及跨任务画像维护闭包清理受已知删除影响的机器任务和知识，不再因存在任意记录／模板删除标记清空全部机器历史。归并知识检查完整来源链，用户独立反馈、行动回看与画像文字继续保留。显式删除分析任务则保留任务 ID、最后版本和删除时间，同时清理其派生知识；恢复旧备份也不重新引入这个任务或相关知识。仅凭删除前的离线副本无法推知后来删除；已导出的外部旧副本仍由用户管理。

反馈合并使用双方删除标记优先、同 ID 目标库当前反馈优先的规则；备份中的高版本或较晚时间戳也不能覆盖目标库否定和纠正。独立用户文字可在任务缺失时保留。永久删除反馈时，其可能复述用户文字的核对结果及对应知识修订也被清理；恢复时对导入任务应用同样规则，预算台账保留为 `FEEDBACK_DELETED`。恢复来源代数推进到所有同库导入任务、反馈和知识来源之后，保证历史结果不会因数值碰撞变成当前有效结果。

知识合并以双方删除标记优先；同 ID 的目标库完整知识修订链优先于备份，不逐条拼接或回滚当前指针。若替换后的原始资料不能保留该链全部引用的记录修订、字段、片段和哈希，则清理整条知识链并写入永久删除标记；后续恢复更早副本也不能使其复活。合并后的修订数、总字节及删除台账超限时整笔恢复失败。保留的知识维持原来源身份，恢复后作为历史资料查阅，不能重新标成当前有效知识。

输入备份自身的引用必须先通过完整校验。若合法旧备份中的任务尚未包含目标库用户后来评价的陈述，合并时省略这个无法对应的历史任务，保留用户反馈及其他任务；不会为完成恢复而丢掉用户的独立文字。反馈目标校验按任务缓存解码，避免大量反馈反复解析同一任务。

## 完整恢复备份

扩展名建议 `.tlb`。结构数据为 UTF-8 JSON，包含全部保留的记录修订、模板版本、关联、保存记录引用的附件、记录／模板删除标记、待处理变更事件、持久分析任务、独立用户反馈、正式知识、行动和画像。分析任务保留问题、范围、预算、调用状态、证据字段／分片身份与校验值、待核验提案，不额外复制原始证据正文。知识、行动与画像保留全部修订、当前指针和删除台账。未保存的编辑器草稿、孤立导入附件、缓存、设备密钥和登录凭据不在包内。后续新增持久类型必须升级格式并补充恢复验证，不能仅加入数据库而遗漏备份。

当前 `snapshot.json` 写入逻辑格式 v8，要求来源 schema v9；保留 `dashboardPreferences`、画像、行动、知识、分析任务与反馈字段，不编码本机来源范围台账。读取兼容 v7／schema v8、v6／schema v7（无偏好）、v5／schema v6（无画像与偏好）、v4／schema v5（无画像与行动）、v3／schema v4（无知识与行动）、v2／schema v3（无反馈、知识和行动）及 v1／schema v2（无分析及上述派生类型）。版本与字段必须匹配，不接受混合版本或未知字段。口令信封及 ZIP 清单仍为 v1，密码学格式未变。

本机 `captureSnapshot` 会在同一事务内附加 `currentKnowledgeSourceRevisions`，记录经范围台账证明来源仍有效的 `(knowledgeId, revision)` 集合，供本次阅读包判断；它不替代核对、反馈、期限和完整来源检查。该集合不是序列化字段，不由 JSON 解码恢复，也不会被 `RepositorySnapshot.copy()` 继承。外部备份或复制修改后的快照不能凭此声明旧知识当前有效；缺少本机证明时保持原有保守检查。

看板偏好内部载荷 v1，严格校验字段、8 KiB 上限及选项。新库可以采用备份设置；目标已有设置（包括显式重置）整体优先。设置变更参与分析代数，旧恢复预览随之失效。恢复事务递增 `restore_epoch`，该本机值不从备份恢复；[后台整理](BACKGROUND_ANALYSIS.md)检测到恢复后撤销旧自动授权。后台策略、队列和付费额度保存在 `noBackupFilesDir`，不进入备份；本机恢复不会清零已经预留的当日额度。

行动周期任务与知识保存固定行动目录及每次读取的行动修订、当前指针、正文哈希，恢复必须与备份行动链匹配。本地行动链合并优先，冲突的导入机器结果清理，用户独立反馈仍保留。普通知识阅读包已纳入有效行动知识及实际核对／扫描用到的固定行动凭据，包括跨批归并的完整来源链；具体选择规则见下文。独立周期材料格式仍明确省略含行动依据的知识并记录数量，不能把它与知识阅读包混作同一能力。完整行动修订链只进入加密备份。详见[行动周期分析](ACTION_PERIOD_REFLECTION.md)。

`knowledgeRevisions` 是独立版本化 JSON 载荷的字符串列表，当前载荷 v6，兼容 v1–v5，增加归并证明中的完整行动来源链及扫描行动凭据；最终核对 `actionEvidence` 与 `reductionProof.originalActionEvidence` 分别保存。v5 的行动目录与行动凭据继续保留；v4 的 `profileMaintenanceProof` 继续保留；v3 的类型化指标凭据和完整来源修订清单继续保留。维护凭据保存纯身份计划、关系与扫描意见、完整原文凭据及历史反馈依赖，不另存历史用户正文原字段；模型理由仍按可能转述这些内容参与删除清理。每个修订保存原陈述、核对意见、范围、期限、反馈版本与完整核对证据引用；直接原文知识的审核副本不依赖父任务仍然存在。归并洞察另存 `reductionProof` 的完整扫描原文、叶清单及全部祖先反馈，父任务缺失时保留历史副本，但不能认定为当前有效；原文仍由记录历史提供。恢复校验修订链连续、稳定身份、唯一且指向最新修订的当前指针，并拒绝知识与其自身、关联任务、自身反馈或任一祖先反馈的永久删除标记共存。后续任务更新不会改写已保存知识的审核副本；当前有效性规则见[正式知识接口](KNOWLEDGE.md)。

分析任务内部载荷另有版本：当前写入 v11，归并节点、扫描与最终核对可携带类型化行动凭据；v10 增加行动周期标记、固定行动目录及类型化行动证据，也支持带比较周期的长期画像目标；v9 增加纯身份 `profileMaintenancePlans`、`PROFILE_RELATION`／`PROFILE_SCAN` 及最终维护核对凭据。v8 增加可空的 `profileTarget`，固定画像维度和近期／情境层次；带目标的任务允许原文综合、核对及维护专用调用，普通任务目标为空。v7 增加 `METRIC_ANALYSIS` 与独立 `metricEvidence`；v6 增加 `REDUCTION_SCAN`／`REDUCTION_REVIEW`、完整祖先反馈依赖和纯身份清理记录；v5 增加独立 `REDUCTION` 派生输入、草稿及来源依赖图；v4 允许 `SYNTHESIS` 调用及其全部原文上下文核对，v3 增加调用目的、核对目标、核对意见和反馈版本，v2 包含计量类型与实际用量。读取兼容 v1–v10，不强制改写未受影响的旧历史任务；旧版本不能混入新增目的或字段。派生依赖只能引用同任务此前成功节点，校验父输出摘要、反馈版本、传递关系和去重。永久反馈删除会清理全部受影响后代草稿、扫描和最终核对结果以及知识整条历史，保留预算、必要纯身份及用户独立文字；恢复合并当前墓碑后执行同样清理。已带墓碑但未擦除相应文字的备份被拒绝。DeepSeek KEY 与 Android Keystore 包装密钥均不进入备份，恢复后重新配置；详细限制见[DeepSeek 接入](DEEPSEEK.md)。

画像修订内部载荷 v2 严格读取 v1，新增独立 `maintenanceApplication`，记录维护计划摘要、操作与明确重新考虑历史限制的理由，不覆盖原 `userText`。永久删除画像、知识、任务或反馈时，按纯身份依赖闭包清理关联维护任务及其机器知识，保留无关任务和独立用户文字。恢复合并本地删除决定后执行相同清理；输入本身已带墓碑却仍保留关联机器文字时拒绝整个备份。新知识的持续有效性检查共享反馈与删除依赖，不以自身画像后续采用或改绑使其失效。接口见[跨任务画像维护](PROFILE_MAINTENANCE.md)。

恢复合并反馈删除记录后，若合法历史任务的身份清理超过单任务或全库分析容量，会保守清理该任务及全部关联机器知识，记录任务墓碑，并在 `RestoreResult.capacityPurgedAnalysisRuns` 返回数量；独立用户反馈与行动保留。总容量按完成清理并排除不兼容任务后的最终集合判断，避免任务处理顺序造成多余删除。常规反馈删除通过 `FeedbackDeletionResult.purgedAnalysisRunId` 报告同一处理。规则见[周期核对维护](PERIOD_REVIEW.md#发布与维护)。

恢复校验每次调用及知识修订的证据是否指向实际保留的记录修订、字段、Unicode 字符区间及相同 SHA-256；当前修订之外的历史修订也可作为历史证据。输入备份自身缺失、删除或正文不匹配的证据会使整个备份无效。恢复保留分析任务与知识的原始来源身份，不把历史结果改标成目标库当前分析；需要在目标当前来源上建立新任务。通过结构校验不代表重新验证模型判断，也不会把待核验提案自动发布为知识。

外层为口令加密信封；内部 ZIP 固定为 `manifest.json`、`snapshot.json` 与 `attachments/<id>.blob`。清单登记结构数据和每个附件的长度、SHA-256。读取同时校验 ZIP 条目、CRC、实际解压长度、文件清单和领域引用。未知版本、重复／额外条目、非法路径或缺失内容被拒绝。

信封格式由 [BackupArchive.kt](../app/src/main/java/dev/tracelife/transfer/BackupArchive.kt) 的版本常量和头部注释定义：

- 48 字节头部：固定魔数、格式版本、KDF 迭代数、分块长度、16 字节随机盐及 12 字节随机基础 nonce。
- PBKDF2-HMAC-SHA256，600,000 次迭代，派生 AES-256 密钥；读取只接受本版本明确支持的参数，避免由文件指定无限工作量。新建口令长度为 12–1024 个字符。
- AES-GCM 每块最多 1 MiB，128 位认证标签；nonce 随块序号变化，AAD 绑定整个头部、序号和长度。必须有已认证的空终止块及文件结束，不能通过删块或重排得到有效备份。
- 分块验证避免解密实现将整份大备份缓存在内存；临时明文只存在应用私有工作目录。密码或独立恢复信息丢失后，设备密钥不能替代它恢复。

选型参考：[Android 密码学建议](https://developer.android.com/privacy-and-security/cryptography)、[SecretKeyFactory 算法支持](https://developer.android.com/reference/javax/crypto/SecretKeyFactory)、[JCA GCM 的 IV 与 AAD 约束](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html)。600,000 次工作因子参考 [OWASP 口令存储指南](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)；该参考不代表本产品获得认证。

## 阅读包与证据包

两者均为普通 ZIP，manifest 标明 `restorable=false`。原始资料只保留选中记录的当前修订和实际绑定的模板版本，不包含完整历史、恢复任务状态或删除台账；附件可选择省略。完整分析任务和待核验提案只进入完整加密备份，阅读包通过 `analysisRunsIncluded=false`、`omittedAnalysisRuns` 和 `analysisGap=analysis_not_included` 明示省略；独立反馈行及其用户正文仍以 `claimFeedbackIncluded=false` 和 `omittedClaimFeedback` 标明省略。

`KNOWLEDGE` 包另纳入采集时有效的正式知识当前修订及其审核副本，要求本次核对涉及的全部原始记录修订都位于选定包内。知识正文显示对应用户决定和反馈版本，不复制独立反馈的 `userText` 字段。失效、过期、核对或反馈已变化、主题不符、原文不在范围内等条目按原因计数并省略，不扩大导出范围。`EVIDENCE` 包仅纳入原始资料，省略全部知识，防止将既有判断当作新证据。知识的发布条件和范围规则见[正式知识接口](KNOWLEDGE.md)。

阅读包 manifest 保留 `snapshotId`、来源 `generation`、`analysisGeneration` 与 `capturedAt`，并以 `validity=historical_snapshot_at_capturedAt` 声明固定历史状态。`knowledge` 节点登记纳入／省略数量、原因、`validAt` 和 `originalEvidence=false`，`completeness` 单独计数省略的历史知识修订。顶层 `validUntil` 是纳入知识期限与画像窗口的最早边界；最终发布以同一次时钟读取验证 `[capturedAt, validUntil)`，即使没有画像也检查到期与时钟回退，同时复查来源和分析代数。离线包不会随时间、用户纠正或新核对自动更新；导出本身不调用模型，也不生成画像、趋势结论或复盘。

```text
README.md                 阅读顺序与范围
manifest.json             小型清单与完整性说明
indexes/index.md          分层导航
indexes/ids/              按稳定 ID 哈希定位的分页映射
indexes/note-ids/         已纳入知识的独立 ID 分片映射
indexes/evidence/         分页记录证据目录
indexes/action-evidence-ids/  已引用行动凭据的分页 ID 映射
topics/                   分页主题及时间入口
timeline/                 年、月、日／周、未知时间与区间入口
records/                  唯一的当前记录 Markdown
templates/                实际引用的模板版本
notes/                    已纳入知识的主题／期间导航、唯一正文及原文反向链接
action-evidence/          知识引用的固定行动凭据、类型／历史身份与反向知识路由
data/                     分页结构化资料
inventory/                文件长度与校验值的分页清单
attachments/              明确选择包含的原始附件
```

具体文件路径以生成的 manifest 和路由入口为准；没有纳入知识时不生成对应知识目录。索引只包含定位信息，记录文字与回答作为资料保留，不能成为索引中的可执行指令。主题、时间与 ID 入口链接同一份 Markdown；知识从 `notes/index.md` 路由，结构化记录与知识 JSONL 分别位于 `data/records/` 和 `data/knowledge/`，供程序使用。知识与原文相互链接，原始证据目录不混入派生知识。文件清单通过哈希链链接，manifest 保持小型，避免全量 catalog 被默认装入上下文。

范围按发生日期或区间交集筛选；多个主题为 OR，`topics=null` 包括未分类资料。归档和未知时间默认纳入，排除未知时间仍保留缺口数量。日期区间不展开成每天均发生；跨期与未知记录有独立入口，按日／周分析时须一并核对。记录数量只描述资料覆盖，不代表成长、健康评分或生活完整程度。

外部文件的阅读约定不能强制其他 AI 的上下文预算；应用内受控取证执行与文件导航分别管理。正式知识是已有分析的保存结果，不能在后续分析中被重复计作独立原始证据。

## 周期复盘材料导出

`exportPeriodReport(destination, scope)` 建立新报告并导出；`exportPeriodReport(destination, report)` 导出调用者已查阅的同一报告，失效时拒绝。报告 API、范围筛选和材料含义见[周期复盘材料](PERIOD_REFLECTION.md)。它不是恢复备份，不调用模型，不新生成综合判断。

ZIP 格式 `trace-life-period-report` v1 包含 `README.md`、`report.md`、`manifest.json`、按复盘意图组织的 `sections/`、原模板答案 `answers/`、当前知识及审核副本 `knowledge/`、候选记录元数据 `sources/`、指标 `metrics/` 和 SHA-256 文件清单 `inventory/`。目录每页最多 25 项；答案和知识的已登记原始引用按最多 1,024 个 Unicode 码点分片，知识引用拼接后核验原登记哈希。原问题、历史模板版本和时间精度保留；不复制整条随记正文、附件、独立反馈正文、任务台账或全部历史修订。

`metrics/record-coverage.csv` 仅含程序生成的指标身份、口径版本、单位、ISO 日期和记录数，避免自由文字成为表格公式。`metrics/metadata.json` 保存来源、完整范围、去重／区间／缺失口径及覆盖计数。指标证据身份供应用使用，`modelCitableReceipt=false`。

manifest 明确 `synthesisStatus=NOT_GENERATED`、`restorable=false`、`validity=historical_snapshot_at_evaluatedAt`，单列无效／越界知识、时间未知、未读附件、不可读记录与缺失指定 ID。报告不宣称全部周期已由 AI 分析。清单通过分页哈希链验证文件；生成后验证全部内部导航目标。包最多 100,000 个文件、256 MiB 解压内容，索引页最大 128 KiB、其他文件最大 1 MiB，超限中止。

导出使用相同暂存和回收协议，并在最终原子创建文件时检查来源与分析代数，以及最早纳入知识的到期时间；跨过期限而无数据库写入也会拒绝。导出后的文件只描述生成时状态，不会跟随以后修改、删除或过期自动改变。

普通阅读包、证据包和周期材料包均明确 `reductionDraftsIncluded=false`。已正式发布的纯记录归并洞察可进入普通阅读包和周期材料包；含行动依据的归并洞察进入普通阅读包，周期材料包仍通过 `omittedActionEvidenceKnowledge` 明示省略；导出检查全部祖先反馈及完整扫描原文范围，扫描分类与最终重读证据分别标明。原文证据包仍不包含正式知识。归并草稿及依赖保存在完整备份中，通过[草稿接口](PERIOD_REDUCTION.md)当前读取，专用草稿文件导出尚未实现。

## 看板导出

`exportDashboard(destination, request)` 建立新看板后导出；`exportDashboard(destination, snapshot)` 导出已查阅的同一快照，失效则拒绝。请求、趋势口径、全局行动与日期语义见[看板接口](DASHBOARD.md)。本模块不新增持久化类型，数据库及完整备份格式不变。

ZIP 格式为 `trace-life-dashboard` v1：

```text
README.md                  阅读入口和范围
dashboard.md               当前／前期覆盖、卡片与导航
manifest.json              格式、历史状态、包含／省略项与校验入口
metadata.json              关注项顺序、来源状态、比较与数量
metrics/current.json       本期口径、范围、每日值和图表点
metrics/current.csv        本期图表点；90 天按自然周
metrics/previous.json      前期口径、范围、每日值和图表点
metrics/previous.csv       前期图表点
sources/current/           本期分页来源元数据及贡献点映射
sources/previous/          前期分页来源元数据及贡献点映射
insights/current/          本期匹配洞察 ID／修订定位
insights/previous/         前期匹配洞察 ID／修订定位
actions/                   当前全局行动摘要
markers/                   本期行动采纳／回看标记
inventory/                 文件长度与 SHA-256 分页清单
```

资料覆盖是独立辅助区，manifest 明示 `sourceMetadataPurpose=global_record_coverage_not_health_card_evidence`；即使默认三张健康卡片尚不可用，也不会把记录覆盖当作健康数值。每个来源保存 ID、修订、发生日期、时区、归档、主题、时间类别与贡献点索引；未知时间及区间不贡献每日点，可能同时出现在两期候选中。`ExportResult.records` 为两期来源 ID 去重后的数量。

每页最多 25 项；CSV 仅含程序生成的身份、日期、版本、数值和状态。用户标题、主题只放入 JSON 资料与转义后的 Markdown，不进入 CSV。洞察只保存应用内 ID／修订，不复制洞察正文或受控模型凭据。行动区保存标题、状态、回看日期、来源状态、最新执行及回看身份；做法、预期信号和回看观察正文省略。原始记录全文、照片、独立反馈、任务台账和凭据也不在包内。

manifest 标明 `validity=historical_snapshot_at_evaluatedAt`、`restorable=false`、`modelCitableReceipt=false`、`modelRequests=0`。文件清单以哈希链分页，生成后验证内部导航目标。包最多 10,000 个文件、64 MiB 解压内容；索引页最多 128 KiB，其他文件最多 1 MiB，超限中止。

沿用受控暂存、刷盘、无覆盖原子发布与中断清理。最终创建文件时在同一主存锁／事务内检查来源与分析代数，并用同一次时钟读取验证 `[evaluatedAt, validUntil)`；本机生成期间发生修改、到期、跨午夜或时钟回拨均拒绝发布。手机看板入口使用上述私有目标，完成后进入统一系统另存流程。外部交付复制的是已经发布的历史包，不重新声明它代表此刻资料，亦不会随以后资料变化自动更新。

## 行动保存与阅读导出

完整备份自 v5 起以 `actionRevisions` 保存严格版本化的行动载荷字符串，内部格式 v1；`actionHeads` 指向每条完整修订链的最新版本，`deletedActions` 登记永久删除。每个修订保留当前计划、本次回看、被回看的旧计划及回看操作 ID。载荷只保存用户明确提交的文字，来源知识仅保留 ID／修订，不复制机器正文。备份校验连续修订链、当前指针、状态转换、旧计划绑定与操作身份，来源已删除不阻止用户独立行动文字恢复。

恢复合并双方删除标记；同 ID 的目标库完整行动链优先于输入，即使备份版本更高或时间更新。恢复没有行动字段的旧备份仍保留目标已有行动。合并后超过容量上限整笔拒绝，不能丢掉本地回看来凑空间。`RestorePreview.actions` 为备份的行动当前指针数；只有行动的目标库也需要显式替换选择，预览后任何行动修改都会使预览失效。

KNOWLEDGE 阅读包从 `actions/index.md` 进入当前计划，提供按回看日期、状态、稳定 ID 的分页导航和 `data/actions/` JSONL。日期筛选匹配计划的 `reviewOn`，不是行动发生或完成日期；行动暂无主题，指定 `topics` 时全部省略，EVIDENCE 包也省略全部行动，避免把用户选择当成执行证据。manifest 登记纳入／省略及原因。

当前计划目录只导出当前计划，不附上回看文字或旧计划，避免当前 `reviewOn` 命中范围时夹带其他日期的历史。来源知识只保留身份，包中不保证它仍有效或已纳入。周期材料 ZIP 单独标记 `actionsIncluded=false` 并记录省略的行动知识数；行动列表通过[行动服务](SYNTHESIS_ACTIONS.md)查阅。

已纳入知识的行动证据采用独立路径和选择规则：

- 完整知识分析周期必须被导出日期范围包含；收窄到该周期一部分时以 `action_scope_not_contained` 省略整条知识。行动暂无主题字段，指定主题时以 `action_topics_unavailable` 省略含行动依据的知识；知识自身主题与其他记录依据仍执行原有筛选。
- `OPENING_PLAN` 是该周期开始前最后开放的计划，允许作为明确标注的“周期前计划背景”进入包，只有当时计划与状态，没有旧回看。`PLAN_CHANGE` 按知识分析时区解释保存日期，仅保留周期内计划变更；`USER_REVIEW` 按 `reviewedOn` 选择，晚于周期补记的保存日期不使它被排除。
- 用户回看严格区分 `reviewedPlan` 与 `nextPlan`。计划不是执行证明；`execution`、`observedText` 是用户报告，不是设备测量；`UNKNOWN` 或未回看不表示失败。当前行动头部的 `reviewOn` 不用于选择知识原始凭据。
- 导出前以保留的历史修订重算凭据哈希，并重建知识周期的完整行动目录。目录变化以 `action_evidence_changed` 省略，删除以 `action_deleted` 省略；同修订正文被篡改或必要原修订缺失则拒绝生成。周期之后的无关计划修改不会用当前正文替换旧凭据。
- 只输出实际用于生成批次核对或归并扫描的行动凭据。归并时被扫描为 `IRRELEVANT` 的来源仍保留；最终支持、最终上下文与扫描来源分别标记。固定行动目录中未读取的身份只保留为身份，不自动导出其正文，也不宣称全周期已经核对。

路由为 `notes/` 的主题／分析期间 → 单条知识 → `notes/action-evidence/<knowledge-hash>/` 分页依据目录 → `action-evidence/entries/<receipt-id>.md` 唯一正文；所有知识共享同一凭据正文。`action-evidence/index.md` 另提供类型、行动历史身份及精确凭据 ID 分片入口，每个凭据反向链接所有引用它的知识。结构化原投影保存在 `data/action-evidence/entries/<receipt-id>.json`，正文保留登记哈希；知识 JSONL 的 `actionEvidencePaths` 提供对应位置。索引不复制回看或计划正文。

manifest 的 `actionEvidence` 独立登记凭据数、去重修订数、关联知识数、期初背景例外数、回看数、只含目录身份的数量和日期规则；现有 `actions` 节点仍专指当前计划目录。`completeness.actionRevisionsIncluded` 计两种入口涉及的去重修订，`omittedHistoricalActionRevisions` 排除已经通过凭据投影携带的历史修订，`completeActionHistoryIncluded=false` 始终明示没有完整历史。凭据投影不等于完整原行动修订，阅读包仍不可恢复。EVIDENCE 包维持仅原始记录的范围，不纳入知识、行动或行动凭据。

行动限制为最多 10,000 个修订、单载荷 128 KiB、总计 16 MiB、删除标记 100,000 个；读取、写入、快照和恢复合并采用相同限制。完整备份仍受整体结构 JSON 32 MiB 上限约束。

## 画像保存与阅读导出

完整备份自 v6 保存 `profileRevisions`、`profileHeads`、`deletedProfiles`；画像载荷当前为 v2，兼容 v1。每个画像修订只保存固定知识引用、维度／层次、采用状态、独立用户文字与操作身份，不复制机器陈述、核对摘录或证据正文。完整历史纳入备份，`RestorePreview.profiles` 显示画像数量；只有画像的目标库同样需要显式替换选择。

恢复合并双方画像删除标记，同 ID 的目标库完整修订链优先；备份中的更高版本或较晚时间不能覆盖本地采用、不采用、替代或用户文字。没有画像字段的旧版备份也保留目标画像。知识采用软引用：来源删除或本地知识版本优先可能留下缺失的指定修订，保留身份及用户文字，但在当前画像中标为来源不可用。引用存在时必须属于允许的观察或洞察类别；修订链、指针、操作身份、删除状态与容量仍严格校验。

KNOWLEDGE 阅读包从 `profile/index.md` 进入分维度目录，再定位画像条目、同包知识和原文。仅纳入当前已采用且来源有效的画像，固定知识 ID／修订还须已被知识范围筛选纳入；不会通过画像扩大主题、日期或证据范围。EVIDENCE 包省略全部画像。候选、不采用、被替代、失效、近期窗口之外和缺失来源的条目省略并计数。

近期画像默认按 30 天窗口判断；`ReadingExportOptions.profileZoneId` 默认 `UTC`，调用者可传目标手机时区，`profileRecentDays` 可设置为 1–366。manifest 记录实际时区、窗口、纳入／省略数量及历史状态。普通阅读包只保存组织元数据和知识链接，不重复机器正文，也不导出画像独立用户文字、全部修订或新的画像摘要；这些历史资料通过完整加密备份保存。

包含画像时，最终文件发布同时校验来源、分析代数和 `[capturedAt, profileValidUntil)`。画像有效期不晚于指定时区下一午夜及其来源知识期限，单纯时间推进或时钟回拨也会阻止旧视图发布。导出后的文件保留生成时状态，不随以后的修改、删除或过期自动更新。

## 当前资源上限与验证边界

完整备份限制单个附件 20 MiB、结构 JSON 32 MiB、清单 16 MiB、总解压内容 1 GiB；加密文件额外预留 64 MiB 格式开销。分析任务最多 100 个，每个 payload 最多 2 MiB UTF-8、合计最多 16 MiB；进行中的调用还预留响应容量，该预留同时计入单任务和总保存上限，防止恢复后缺少空间登记返回结果。内部 JSON 另有严格形状和节点预算；显式任务删除标记最多 100,000 个。外层 JSON 还限制字段形状、列表数量、嵌套层数及 2,000,000 个结构节点，读写采用一致预算。超限明确失败，不截断数据。

独立反馈最多 10,000 条、每条用户文字最多 2,000 字符，整行 JSON 总量最多 4 MiB；反馈删除标记最多 100,000 个。恢复合并后仍检查上限，超限整笔失败，不静默丢弃目标库反馈。

正式知识最多 10,000 个修订，每个载荷最多 256 KiB UTF-8、合计最多 16 MiB，知识删除标记最多 100,000 个；历史修订也占用该额度。载荷严格检查字段、类型、重复键及嵌套预算，完整备份仍受上述 32 MiB 结构 JSON 总上限约束。恢复合并本地与备份知识后再次检查容量，超限不会丢弃本地修订来凑入备份。

来源快照最多 100,000 条记录修订、10,000 个模板版本，限制在范围筛选前执行；存储层先检查数量和文本预算再读取全部对象。阅读包筛选后最多 10,000 条当前记录、100,000 个文件、256 MiB 解压内容；索引默认每页 50 条，可设置 1–100。单页索引限制 128 KiB，过长机器数据按字节和条数分页。大量照片可选择省略或缩小范围。

原生 SQLite 和 Robolectric 测试覆盖往返、旧格式兼容、知识和行动完整修订链、本地回看优先、删除优先与原文缺失、发布／恢复版本检查、损坏与超限输入、目录穿越、索引链接和中断清理；周期材料还覆盖全部核对引用、指标 CSV、到期失效，以及预检查后出现同名目标时不覆盖。看板导出增加双窗口贡献映射、正文省略、分页哈希导航、跨午夜及最终发布时钟回拨验证。画像验证覆盖完整链往返、本地决定与删除优先、近期时区筛选及最终发布期限。画像维护还覆盖历史关系依赖清理、固定参照缺失及裁剪任务后非法知识副本的拒绝。行动归并与固定行动证据导出的历史验证见[阶段计划](plans/2026-10-19-action-report-export.md#验证记录)。本次增加范围失效／删除／迁移与恢复、私有发布所有权和收据、系统选址取消、关闭失败、目标截断及读回校验等测试；实际结果、APK 与模拟器操作统一见[开发与验证](DEVELOPMENT.md)。这些结果不代表实体手机、任意网盘文档提供方或外部 AI 阅读验收。

指标解释的备份重算与完整范围导出规则见[受控指标分析](METRIC_ANALYSIS.md)。指标凭据保存实际来源修订，知识阅读包和周期包检查完整日期范围与全部成员；普通 `metrics/metadata.json` 的应用统计身份保持 `modelCitableReceipt=false`，已发布洞察另带受控指标凭据。
