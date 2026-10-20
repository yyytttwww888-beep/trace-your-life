package dev.tracelife.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A successful result means the closed destination was read back and matched the complete file. */
data class DocumentDeliveryResult(val bytes: Long, val sha256: String)

class DocumentDeliveryException(val incompleteDocumentRemains: Boolean, cause: Exception) : IllegalStateException(
    if (incompleteDocumentRemains) "没有完成另存。目标位置可能留有不完整文件，请删除后重试；应用内的完整文件仍然保留。"
    else "没有完成另存。应用内的完整文件仍然保留，可以换一个位置重试。", cause,
)

/**
 * Deliver an already committed historical snapshot to a newly created SAF document.
 * A provider may expose partial bytes while writing and need not offer atomic rename. Therefore
 * this operation is separate from snapshot publication and never reports success before readback.
 * The caller must use ACTION_CREATE_DOCUMENT (not OPEN_DOCUMENT) so existing files are not replaced.
 */
class DocumentExportDelivery(private val resolver: ContentResolver) {
    fun save(source: File, newlyCreatedDocument: Uri, expectedSha256: String): DocumentDeliveryResult = writeAndVerify(source,
        output = { resolver.openOutputStream(newlyCreatedDocument, "wt") ?: throw IOException("无法写入所选位置") },
        input = { resolver.openInputStream(newlyCreatedDocument) ?: throw IOException("无法核验保存的文件") },
        delete = { DocumentsContract.deleteDocument(resolver, newlyCreatedDocument) },
        expectedSha256 = expectedSha256,
    )

    internal companion object {
        /** Stream seam exercises provider truncation, close failures and readback independently. */
        fun writeAndVerify(source: File, output: () -> OutputStream, input: () -> InputStream,
            delete: () -> Boolean, expectedSha256: String? = null): DocumentDeliveryResult {
            try {
                require(source.isFile && source.length() > 0) { "完整导出文件已不存在，请重新生成" }
                val expected = MessageDigest.getInstance("SHA-256")
                var written = 0L
                source.inputStream().use { original ->
                    output().use { destination ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = original.read(buffer)
                            if (count < 0) break
                            expected.update(buffer, 0, count)
                            destination.write(buffer, 0, count)
                            written += count
                        }
                        destination.flush()
                    }
                }
                val actual = MessageDigest.getInstance("SHA-256")
                var read = 0L
                input().use { saved ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = saved.read(buffer)
                        if (count < 0) break
                        read += count
                        if (read > written) throw IOException("目标文件大小不一致")
                        actual.update(buffer, 0, count)
                    }
                }
                val hash = expected.digest()
                val hashText = hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (expectedSha256 != null && hashText != expectedSha256) throw IOException("应用内导出文件校验不一致，请重新生成")
                if (read != written || !MessageDigest.isEqual(hash, actual.digest())) {
                    throw IOException("目标文件校验不一致")
                }
                return DocumentDeliveryResult(written, hashText)
            } catch (failure: Exception) {
                val removed = runCatching(delete).getOrDefault(false)
                throw DocumentDeliveryException(!removed, failure)
            }
        }
    }
}
