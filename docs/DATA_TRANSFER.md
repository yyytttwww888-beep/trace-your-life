# 数据转移接口与格式

本文件描述 M2 后端的实际接口。产品目的与完整范围见[主设计](superpowers/specs/2026-10-16-personal-knowledge-and-reflection-design.md)，实现顺序见[M2 计划](plans/2026-10-16-backup-knowledge-export.md)。当前不提供前端按钮；调用须在后台线程执行，不能把整库转移接口提供给模型。

## 调用入口

应用提供 `TraceLifeApplication.dataTransfer`；也可用 `DataTransfer(context, repository)` 独立构造。密码由调用者持有，接口不保存或记录密码，调用者在完成后清空 `CharArray`。

```kotlin
// 在后台线程；chosenDirectory 是调用者已有写权限的目录。
val backup = File(chosenDirectory, "trace-life-backup.tlb")
val saved = transfer.exportBackup(backup, recoveryPassword)

// 验证备份并显示其内容范围。预览绑定备份哈希、目标库身份和变更代数。
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

默认恢复只允许空资料库。目标存在记录或模板时，须由调用者明确选择替换后传入 `replaceExisting = true`；初始化过内置模板的新安装也适用。替换保留目标库身份和已知删除标记，备份中的其他资料成为恢复后的内容。不会把替换自动解释为跨设备合并。

发生新写入、目标库不同或备份文件变化后，旧预览失效；调用者重新预览并确认。恢复密码错误、格式无效或附件缺失时不会修改目标资料。文件接口拒绝覆盖已有导出路径，调用者为每份导出提供新文件名。

当前参数为 `File`，不能直接传入 `content://` URI。Android 系统文档选择器及各 provider 的提交行为留给前端接入阶段；不能假定所有网盘 provider 支持原子重命名。真正用于卸载／换机恢复的最终备份须存放在应用私有目录之外。

## 一致性与中断

SQLite schema v2 从 v1 原地升级，增加主库身份、变更代数和模板删除标记。触发器覆盖权威表写入；普通查询不使快照失效。采集快照时，在同一事务和主库操作锁下读取逻辑资料并核对附件；不复制正在写入的数据库文件。

生成文件先放入应用 `noBackupFilesDir/transfer-staging` 下的独立工作目录；输出前复制至目标旁的临时文件，检查快照仍有效后原子移动至最终路径。文件系统不支持原子移动时明确失败。所有权登记在写入临时输出内容前持久保存，`recoverInterruptedTransfers()` 只回收该任务的临时文件，不删除已发布文件。构造模块时执行一次恢复，并与当前进程正在执行的转移串行。

恢复先复制输入并固定其哈希，完成解密、清单和逻辑校验，再将完整附件准备好，最后在一个数据库事务内替换资料。失败事务不删除旧的有效附件，新产生的无引用文件可清理。提交后的清理失败保留登记供下次重试，不把已提交恢复报告成回滚。

任何来源变化均会使尚未发布的导出失效，包括删除。目标已知删除标记优先于备份；记录的全部历史及专属附件随该规则排除。模板已删除而待恢复记录仍引用它时拒绝该冲突备份。仅凭删除前的离线副本无法推知后来删除；已导出的外部旧副本仍由用户管理。

## 完整恢复备份 v1

扩展名建议 `.tlb`。结构数据为 UTF-8 JSON，包含全部保留的记录修订、模板版本、关联、保存记录引用的附件、记录／模板删除标记及待处理变更事件。未保存的编辑器草稿、孤立导入附件、缓存、设备密钥和登录凭据不在包内。后续新增持久类型必须升级格式并补充恢复验证，不能仅加入数据库而遗漏备份。

外层为口令加密信封；内部 ZIP 固定为 `manifest.json`、`snapshot.json` 与 `attachments/<id>.blob`。清单登记结构数据和每个附件的长度、SHA-256。读取同时校验 ZIP 条目、CRC、实际解压长度、文件清单和领域引用。未知版本、重复／额外条目、非法路径或缺失内容被拒绝。

信封格式由 [BackupArchive.kt](../app/src/main/java/dev/tracelife/transfer/BackupArchive.kt) 的版本常量和头部注释定义：

- 48 字节头部：固定魔数、格式版本、KDF 迭代数、分块长度、16 字节随机盐及 12 字节随机基础 nonce。
- PBKDF2-HMAC-SHA256，600,000 次迭代，派生 AES-256 密钥；读取只接受本版本明确支持的参数，避免由文件指定无限工作量。新建口令长度为 12–1024 个字符。
- AES-GCM 每块最多 1 MiB，128 位认证标签；nonce 随块序号变化，AAD 绑定整个头部、序号和长度。必须有已认证的空终止块及文件结束，不能通过删块或重排得到有效备份。
- 分块验证避免解密实现将整份大备份缓存在内存；临时明文只存在应用私有工作目录。密码或独立恢复信息丢失后，设备密钥不能替代它恢复。

选型参考：[Android 密码学建议](https://developer.android.com/privacy-and-security/cryptography)、[SecretKeyFactory 算法支持](https://developer.android.com/reference/javax/crypto/SecretKeyFactory)、[JCA GCM 的 IV 与 AAD 约束](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html)。600,000 次工作因子参考 [OWASP 口令存储指南](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)；该参考不代表本产品获得认证。

## 阅读包与证据包

两者均为普通 ZIP，manifest 标明 `restorable=false`。仅保留选中记录的当前修订和实际绑定的模板版本，不包含完整历史、恢复任务状态或删除台账；附件可选择省略。资料未被 AI 分析时，不生成画像、趋势结论或伪造复盘。

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

外部文件的阅读约定不能强制其他 AI 的上下文预算；M3 仍须另行实现受控取证与发布接口。

## 当前资源上限与验证边界

完整备份限制单个附件 20 MiB、结构 JSON 32 MiB、清单 16 MiB、总解压内容 1 GiB；加密文件额外预留 64 MiB 格式开销。JSON 还限制字段形状、列表数量、嵌套层数及 2,000,000 个结构节点，读写采用一致预算。超限明确失败，不截断数据。

来源快照最多 100,000 条记录修订、10,000 个模板版本，限制在范围筛选前执行；存储层先检查数量和文本预算再读取全部对象。阅读包筛选后最多 10,000 条当前记录、100,000 个文件、256 MiB 解压内容；索引默认每页 50 条，可设置 1–100。单页索引限制 128 KiB，过长机器数据按字节和条数分页。大量照片可选择省略或缩小范围。

原生 SQLite 和 Robolectric 测试覆盖往返、旧模板引用、并发版本检查、删除优先、损坏与超限输入、目录穿越、索引链接和中断清理。具体执行结果见[路线进度](ROADMAP.md)。尚未完成实体手机、系统文档 provider 或外部 AI 实际阅读验收。
