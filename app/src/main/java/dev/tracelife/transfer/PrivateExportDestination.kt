package dev.tracelife.transfer

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Unforgeable owner of one newly allocated, app-private export directory. Only this owner can
 * publish there; the general File export API rejects the entire reserved namespace. A completed
 * archive is verified and its receipt flushed before atomic rename makes the final name visible.
 * This is deliberately not a fallback for arbitrary filesystems or caller-chosen paths.
 */
class PrivateExportDestination private constructor(val file: File) {
    private var prepared: File? = null
    private var published = false

    internal fun prepare(stagedFile: File) = synchronized(this) {
        check(!published && prepared == null) { "这份导出已经准备或发布" }
        require(stagedFile.canonicalFile.parentFile == file.parentFile && stagedFile.isFile &&
            !Files.isSymbolicLink(stagedFile.toPath())) { "导出暂存不属于本次私有目录" }
        check(!file.exists() && !Files.isSymbolicLink(file.toPath())) { "导出目标已存在" }
        val digest = MessageDigest.getInstance("SHA-256")
        stagedFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        val receipt = JSONObject().put("version", 1).put("name", file.name)
            .put("bytes", stagedFile.length()).put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        FileOutputStream(File(file.parentFile, RECEIPT)).use { output ->
            output.write(receipt.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        prepared = stagedFile.canonicalFile
    }

    /** Called only inside the repository's generation-checked publication transaction. */
    internal fun publish(stagedFile: File) = synchronized(this) {
        check(!published && prepared == stagedFile.canonicalFile) { "导出所有权或准备状态不匹配" }
        check(!file.exists() && !Files.isSymbolicLink(file.toPath())) { "导出目标已存在" }
        // The reserved directory has exactly one capability/writer. No other export can obtain
        // the same destination, and the generic API cannot write here. Unlike a shared target,
        // rename is safe without a replacing race in this private owned namespace.
        Files.move(stagedFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        published = true
    }

    internal fun discardUnpublished() = synchronized(this) {
        if (!published && !file.exists()) file.parentFile?.deleteRecursively()
    }

    companion object {
        internal const val RECEIPT = "complete.json"
        internal fun root(context: Context) = File(context.applicationContext.noBackupFilesDir, "completed-exports").canonicalFile
        internal fun reserve(context: Context, kind: String): PrivateExportDestination {
            require(kind in setOf("backup", "knowledge", "evidence", "dashboard", "period")) { "未知导出类型" }
            val root = root(context)
            check(root.isDirectory || root.mkdirs()) { "无法创建导出目录" }
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
            val base = "trace-life-$kind-$stamp-${UUID.randomUUID()}"
            val directory = File(root, base)
            check(directory.mkdir()) { "无法保留独立导出目录" }
            val extension = if (kind == "backup") "tlb" else "zip"
            return PrivateExportDestination(File(directory, "$base.$extension"))
        }
    }
}
