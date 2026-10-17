# 数据转移接口与格式

本文件描述数据转移后端的实际接口，包括 M3 分批分析、核对与用户反馈的备份边界。产品目的与完整范围见[主设计](superpowers/specs/2026-10-16-personal-knowledge-and-reflection-design.md)，初始实现见[M2 计划](plans/2026-10-16-backup-knowledge-export.md)。当前不提供前端按钮；调用须在后台线程执行，不能把整库转移接口提供给模型。

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

默认恢复只允许空资料库。目标存在记录、模板、分析任务或用户反馈时，须由调用者明确选择替换后传入 `replaceExisting = true`；初始化过内置模板的新安装也适用。替换保留目标库身份、已知删除标记和目标库独立用户反馈，备份中的其他资料成为恢复后的内容。不会把替换自动解释为跨设备双向同步。

发生新写入、目标库不同或备份文件变化后，旧预览失效；只更新分析任务或用户反馈也会使预览失效。`RestorePreview.analysisRuns` 与 `claimFeedback` 表示备份中的任务数和反馈数，`targetAnalysisGeneration` 绑定目标分析及反馈状态。调用者重新预览并确认。恢复密码错误、格式无效或附件缺失时不会修改目标资料。文件接口拒绝覆盖已有导出路径，调用者为每份导出提供新文件名。

当前参数为 `File`，不能直接传入 `content://` URI。Android 系统文档选择器及各 provider 的提交行为留给前端接入阶段；不能假定所有网盘 provider 支持原子重命名。真正用于卸载／换机恢复的最终备份须存放在应用私有目录之外。

## 一致性与中断

SQLite schema v4 保留原有资料并原地升级：v2 增加主库身份、来源变更代数和模板删除标记，v3 增加分析任务与独立分析变更代数，v4 增加独立反馈及其删除台账。分析和反馈写入不会修改来源代数；导出和恢复同时检查两种代数。反馈还递增关联任务版本以阻止旧模型结果写回。触发器覆盖相关表写入，普通查询不使快照失效。采集快照时，在同一事务和主库操作锁下读取逻辑资料并核对附件；不复制正在写入的数据库文件。

生成文件先放入应用 `noBackupFilesDir/transfer-staging` 下的独立工作目录；输出前复制至目标旁的临时文件，检查快照仍有效后原子移动至最终路径。文件系统不支持原子移动时明确失败。所有权登记在写入临时输出内容前持久保存，`recoverInterruptedTransfers()` 只回收该任务的临时文件，不删除已发布文件。构造模块时执行一次恢复，并与当前进程正在执行的转移串行。

恢复先复制输入并固定其哈希，完成解密、清单和逻辑校验，再将完整附件准备好，最后在一个数据库事务内替换资料。失败事务不删除旧的有效附件，新产生的无引用文件可清理。提交后的清理失败保留登记供下次重试，不把已提交恢复报告成回滚。

任何来源或分析变化均会使尚未发布的导出失效，包括删除。目标已知删除标记优先于备份；记录的全部历史及专属附件随该规则排除。模板已删除而待恢复记录仍引用它时拒绝该冲突备份。目标具有备份中尚未记录的记录或模板删除标记时，恢复时一并排除全部历史分析任务，防止提案残留被删内容；这是当前保守的删除传播策略。显式删除分析任务则单独保留任务 ID、最后版本和删除时间，恢复旧备份也不重新引入这个任务；其他未删除任务可保留。仅凭删除前的离线副本无法推知后来删除；已导出的外部旧副本仍由用户管理。

反馈合并使用双方删除标记优先、同 ID 目标库当前反馈优先的规则；备份中的高版本或较晚时间戳也不能覆盖目标库否定和纠正。独立用户文字可在任务缺失时保留。永久删除反馈时，其可能复述用户文字的核对结果也被清理；恢复时对导入任务应用同样规则，预算台账保留为 `FEEDBACK_DELETED`。恢复来源代数推进到所有同库导入任务及反馈来源之后，保证旧核对不会因数值碰撞变成当前有效结果。

输入备份自身的引用必须先通过完整校验。若合法旧备份中的任务尚未包含目标库用户后来评价的陈述，合并时省略这个无法对应的历史任务，保留用户反馈及其他任务；不会为完成恢复而丢掉用户的独立文字。反馈目标校验按任务缓存解码，避免大量反馈反复解析同一任务。

## 完整恢复备份

扩展名建议 `.tlb`。结构数据为 UTF-8 JSON，包含全部保留的记录修订、模板版本、关联、保存记录引用的附件、记录／模板删除标记、待处理变更事件及持久分析任务。分析任务保留问题、范围、预算、调用状态、证据字段／分片身份与校验值、待核验提案，不额外复制原始证据正文。未保存的编辑器草稿、孤立导入附件、缓存、设备密钥和登录凭据不在包内。后续新增持久类型必须升级格式并补充恢复验证，不能仅加入数据库而遗漏备份。

当前 `snapshot.json` 写入逻辑格式 v3，要求来源 schema v4，增加 `claimFeedback` 和 `deletedClaimFeedback`；保留 `analysisGeneration`、`analysisRuns` 与 `deletedAnalysisRuns`。读取兼容 v2／schema v3（无反馈）及 v1／schema v2（无分析和反馈）。版本与字段必须匹配，不接受混合版本或未知字段。口令信封及 ZIP 清单仍为 v1，密码学格式未变。

分析任务内部载荷另有版本：当前写入 v3，增加调用目的、核对目标、核对意见和反馈版本；v2 包含计量类型与实际用量。读取兼容 v1/v2，不强制改写未受影响的旧历史任务。DeepSeek KEY 与 Android Keystore 包装密钥均不进入备份，恢复后重新配置；详细限制见[DeepSeek 接入](DEEPSEEK.md)。

恢复校验每次调用的证据是否指向实际保留的记录修订、字段、Unicode 字符区间及相同 SHA-256；当前修订之外的历史修订也可作为历史调用证据。缺失、删除或正文不匹配的证据会使整个备份无效。恢复保留分析任务的原始来源身份，不把历史任务改标成目标库当前分析；需要在目标当前来源上建立新任务。通过此校验仅说明引用结构一致，不代表提案获得语义验证或成为正式结论。

外层为口令加密信封；内部 ZIP 固定为 `manifest.json`、`snapshot.json` 与 `attachments/<id>.blob`。清单登记结构数据和每个附件的长度、SHA-256。读取同时校验 ZIP 条目、CRC、实际解压长度、文件清单和领域引用。未知版本、重复／额外条目、非法路径或缺失内容被拒绝。

信封格式由 [BackupArchive.kt](../app/src/main/java/dev/tracelife/transfer/BackupArchive.kt) 的版本常量和头部注释定义：

- 48 字节头部：固定魔数、格式版本、KDF 迭代数、分块长度、16 字节随机盐及 12 字节随机基础 nonce。
- PBKDF2-HMAC-SHA256，600,000 次迭代，派生 AES-256 密钥；读取只接受本版本明确支持的参数，避免由文件指定无限工作量。新建口令长度为 12–1024 个字符。
- AES-GCM 每块最多 1 MiB，128 位认证标签；nonce 随块序号变化，AAD 绑定整个头部、序号和长度。必须有已认证的空终止块及文件结束，不能通过删块或重排得到有效备份。
- 分块验证避免解密实现将整份大备份缓存在内存；临时明文只存在应用私有工作目录。密码或独立恢复信息丢失后，设备密钥不能替代它恢复。

选型参考：[Android 密码学建议](https://developer.android.com/privacy-and-security/cryptography)、[SecretKeyFactory 算法支持](https://developer.android.com/reference/javax/crypto/SecretKeyFactory)、[JCA GCM 的 IV 与 AAD 约束](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html)。600,000 次工作因子参考 [OWASP 口令存储指南](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)；该参考不代表本产品获得认证。

## 阅读包与证据包

两者均为普通 ZIP，manifest 标明 `restorable=false`。仅保留选中记录的当前修订和实际绑定的模板版本，不包含完整历史、恢复任务状态或删除台账；附件可选择省略。分批分析任务和待核验提案只进入完整加密备份，阅读包通过 `analysisRunsIncluded=false`、`omittedAnalysisRuns` 和 `analysisGap=analysis_not_included` 明示省略；反馈另以 `claimFeedbackIncluded=false` 和 `omittedClaimFeedback` 标明省略。不会将待核验提案或用户认可直接导出为已验证的画像、趋势结论或复盘。

```text
README.md                 阅读顺序与范围
manifest.json             小型清单与完整性说明
indexes/index.md          分层导航
indexes/ids/              按稳定 ID 哈希定位的分页映射
indexes/evidence/         分页证据目录
topics/                   分页主题及时间入口
timeline/                 年、月、日／周、未知时间与区间入口
records/                  唯一的当前记录 Markdown
templates/                实际引用的模板版本
data/                     分页结构化资料
inventory/                文件长度与校验值的分页清单
attachments/              明确选择包含的原始附件
```

具体文件路径以生成的 manifest 和路由入口为准。索引只包含定位信息，记录文字与回答作为资料保留，不能成为索引中的可执行指令。主题、时间与 ID 入口链接同一份 Markdown；结构化 JSONL 供程序使用。文件清单通过哈希链链接，manifest 保持小型，避免全量 catalog 被默认装入上下文。

范围按发生日期或区间交集筛选；多个主题为 OR，`topics=null` 包括未分类资料。归档和未知时间默认纳入，排除未知时间仍保留缺口数量。日期区间不展开成每天均发生；跨期与未知记录有独立入口，按日／周分析时须一并核对。记录数量只描述资料覆盖，不代表成长、健康评分或生活完整程度。

外部文件的阅读约定不能强制其他 AI 的上下文预算；应用内受控取证执行与文件导航分别管理，正式分析发布仍属后续能力。

## 当前资源上限与验证边界

完整备份限制单个附件 20 MiB、结构 JSON 32 MiB、清单 16 MiB、总解压内容 1 GiB；加密文件额外预留 64 MiB 格式开销。分析任务最多 100 个，每个 payload 最多 2 MiB UTF-8、合计最多 16 MiB；进行中的调用还预留响应容量，该预留同时计入单任务和总保存上限，防止恢复后缺少空间登记返回结果。内部 JSON 另有严格形状和节点预算；显式任务删除标记最多 100,000 个。外层 JSON 还限制字段形状、列表数量、嵌套层数及 2,000,000 个结构节点，读写采用一致预算。超限明确失败，不截断数据。

独立反馈最多 10,000 条、每条用户文字最多 2,000 字符，整行 JSON 总量最多 4 MiB；反馈删除标记最多 100,000 个。恢复合并后仍检查上限，超限整笔失败，不静默丢弃目标库反馈。

来源快照最多 100,000 条记录修订、10,000 个模板版本，限制在范围筛选前执行；存储层先检查数量和文本预算再读取全部对象。阅读包筛选后最多 10,000 条当前记录、100,000 个文件、256 MiB 解压内容；索引默认每页 50 条，可设置 1–100。单页索引限制 128 KiB，过长机器数据按字节和条数分页。大量照片可选择省略或缩小范围。

原生 SQLite 和 Robolectric 测试覆盖往返、旧模板引用、并发版本检查、删除优先、损坏与超限输入、目录穿越、索引链接和中断清理。具体执行结果见[路线进度](ROADMAP.md)。尚未完成实体手机、系统文档 provider 或外部 AI 实际阅读验收。
