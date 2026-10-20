package dev.tracelife.transfer

import android.content.Context
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

/**
 * Completed immutable export copies, separate from both source data and transfer staging.
 * Publication uses a reserved private directory capability and verified receipt. This avoids
 * the hard links rejected by Android while retaining atomic, exclusive publication.
 */
class CompletedExports(private val context: Context) {
    private val directory = PrivateExportDestination.root(context)

    fun create(kind: String, export: (PrivateExportDestination) -> ExportResult): Pair<ExportResult, File> {
        val destination = PrivateExportDestination.reserve(context, kind)
        try {
            val result = export(destination)
            check(get(destination.file.name)?.length() == result.bytes) { "导出文件未完整生成" }
            return result to destination.file
        } finally { destination.discardUnpublished() }
    }

    fun get(name: String): File? {
        if (!NAME.matches(name)) return null
        val parent = File(directory, name.substringBeforeLast('.'))
        val file = File(parent, name)
        return file.takeIf {
            !Files.isSymbolicLink(parent.toPath()) && !Files.isSymbolicLink(it.toPath()) &&
                parent.canonicalFile.parentFile == directory && receipt(name) != null
        }
    }

    /** Receipt is bounded and contains no source text; delivery verifies its hash while copying. */
    fun expectedHash(name: String): String = requireNotNull(receipt(name)) { "导出文件或完整性登记已损坏" }.getString("sha256")

    private fun receipt(name: String): JSONObject? = runCatching {
        require(NAME.matches(name))
        val parent = File(directory, name.substringBeforeLast('.'))
        val file = File(parent, name)
        val metadata = File(parent, PrivateExportDestination.RECEIPT)
        require(!Files.isSymbolicLink(parent.toPath()) && !Files.isSymbolicLink(metadata.toPath()) &&
            !Files.isSymbolicLink(file.toPath()) && file.isFile && metadata.isFile && metadata.length() in 1..2048)
        JSONObject(metadata.readText()).also {
            require(it.getInt("version") == 1 && it.getString("name") == name && it.getLong("bytes") == file.length() &&
                file.length() > 0 && Regex("[0-9a-f]{64}").matches(it.getString("sha256")))
        }
    }.getOrNull()

    fun list(offset: Int = 0, limit: Int = 20): List<File> {
        require(offset >= 0 && limit in 1..100)
        return directory.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }.filter { get(it.name) != null }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name }).drop(offset).take(limit)
    }

    fun remove(name: String): Boolean = get(name)?.parentFile?.deleteRecursively() ?: false

    companion object {
        private val NAME = Regex("trace-life-(backup|knowledge|evidence|dashboard|period)-[0-9]{8}-[0-9]{6}-[0-9a-f-]{36}\\.(tlb|zip)")
    }
}
