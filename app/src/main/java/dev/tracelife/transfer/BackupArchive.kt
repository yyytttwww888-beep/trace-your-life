package dev.tracelife.transfer

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import dev.tracelife.storage.SnapshotValidation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable format v1, big endian: "TLBACKUP" (8), version (4), PBKDF2 iterations (4),
 * chunk size (4), random salt (16), random base nonce (12). The entire 48-byte header is AAD.
 * Every frame contains plaintext length (4) followed by AES-256-GCM ciphertext + 128-bit tag.
 * AAD = header || frame index (8) || plaintext length (4). Nonce = base nonce with its final
 * 8 bytes XORed with the bounded, increasing frame index. Nonces are distinct for one key.
 * A zero-length authenticated terminal frame followed by EOF is mandatory; order, header,
 * lengths and truncation are authenticated. Each chunk is verified before writing plaintext.
 *
 * The plaintext is a ZIP with no comment, containing manifest.json, snapshot.json and exactly
 * the manifest's attachment files. ZIP and extracted files remain in caller-private staging
 * until all frames, lengths, hashes, CRCs and references pass. Device keys are never involved.
 */
object BackupArchive {
    const val FORMAT_VERSION = 1
    const val KDF_ITERATIONS = 600_000
    const val CHUNK_BYTES = 1024 * 1024
    const val HEADER_BYTES = 48
    const val MAX_METADATA_BYTES = 32L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    const val MAX_ENTRIES = 100_000
    const val MAX_ARCHIVE_BYTES = MAX_TOTAL_BYTES + 64L * 1024 * 1024
    private const val MAX_FRAMES = 2048L
    private const val TAG_BYTES = 16
    private val magic = "TLBACKUP".toByteArray(Charsets.US_ASCII)
    private val safeId = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,199}")
    private val sha256 = Regex("[a-f0-9]{64}")

    fun write(snapshot: RepositorySnapshot, attachmentDirectory: File, destination: File, password: CharArray): ExportResult {
        require(password.size in 12..1024) { "恢复口令须为 12 至 1024 个字符" }
        require(!destination.exists()) { "目标文件已存在，请选择新文件" }
        validateReferences(snapshot)
        val parent = requireNotNull(destination.absoluteFile.parentFile)
        require(parent.isDirectory) { "备份暂存目录不存在" }
        val work = Files.createTempDirectory(parent.toPath(), ".backup-write-").toFile()
        var createdOutput = false
        try {
            val metadata = File(work, "snapshot.json")
            metadata.outputStream().use { output -> SnapshotJsonCodec.write(snapshot, BoundedOutput(output, MAX_METADATA_BYTES)) }
            val entries = mutableListOf(ManifestEntry("snapshot.json", metadata.length(), hashFile(metadata)))
            var totalBytes = metadata.length()
            snapshot.attachments.forEach { attachment ->
                val file = File(attachmentDirectory, "${attachment.id}.blob")
                checkBackup(file.isFile && !Files.isSymbolicLink(file.toPath()), "备份附件不存在或不是普通文件")
                checkBackup(file.length() == attachment.sizeBytes && attachment.sizeBytes in 0..MAX_ATTACHMENT_BYTES, "备份附件长度不一致或超过限制")
                checkBackup(hashFile(file) == attachment.sha256, "备份附件校验失败")
                entries += ManifestEntry("attachments/${attachment.id}.blob", attachment.sizeBytes, attachment.sha256)
                totalBytes += attachment.sizeBytes
                checkBackup(totalBytes <= MAX_TOTAL_BYTES, "备份资料超过 1 GiB 限制")
            }
            checkBackup(entries.size + 1 <= MAX_ENTRIES, "备份文件数量超过限制")
            val manifest = File(work, "manifest.json")
            manifest.outputStream().use { output -> writeManifest(snapshot.snapshotId, entries, BoundedOutput(output, MAX_MANIFEST_BYTES)) }
            checkBackup(totalBytes + manifest.length() <= MAX_TOTAL_BYTES, "备份资料超过 1 GiB 限制")
            val header = newHeader()
            val key = deriveKey(password, header.copyOfRange(20, 36))
            try {
                checkBackup(destination.createNewFile(), "备份目标文件已存在")
                createdOutput = true
                DataOutputStream(destination.outputStream().buffered()).use { output ->
                    output.write(header)
                    ZipOutputStream(EncryptingStream(output, header, key)).use { zip ->
                        addZipFile(zip, "manifest.json", manifest)
                        addZipFile(zip, "snapshot.json", metadata)
                        snapshot.attachments.forEach { attachment -> addZipFile(zip, "attachments/${attachment.id}.blob", File(attachmentDirectory, "${attachment.id}.blob"), attachment.sizeBytes, attachment.sha256) }
                    }
                }
            } finally { key.fill(0) }
            return ExportResult(snapshot.snapshotId, snapshot.records.map { it.id }.distinct().size, snapshot.attachments.size, entries.size + 1, destination.length())
        } catch (failure: Exception) {
            if (createdOutput) destination.delete()
            throw failure
        } finally { work.deleteRecursively() }
    }

    fun read(source: File, attachmentDestination: File, password: CharArray): RepositorySnapshot {
        require(password.size in 1..1024) { "请提供有效的恢复口令" }
        require(!attachmentDestination.exists() || attachmentDestination.isDirectory && attachmentDestination.list()?.isEmpty() == true) { "恢复附件目录必须为空" }
        require(!Files.isSymbolicLink(attachmentDestination.toPath())) { "恢复目录不能是符号链接" }
        checkBackup(source.isFile && source.length() in (HEADER_BYTES + 20L)..MAX_ARCHIVE_BYTES, "备份文件长度无效或超过限制")
        val parent = requireNotNull(attachmentDestination.absoluteFile.parentFile)
        require(parent.isDirectory) { "恢复暂存目录不存在" }
        val work = Files.createTempDirectory(parent.toPath(), ".backup-read-").toFile()
        val moved = mutableListOf<File>()
        val destinationExisted = attachmentDestination.exists()
        try {
            val plaintextZip = File(work, "verified.zip")
            decrypt(source, plaintextZip, password)
            val stagedAttachments = File(work, "attachments").apply { checkBackup(mkdir(), "无法建立恢复暂存目录") }
            val snapshot = unpackAndVerify(plaintextZip, File(work, "snapshot.json"), stagedAttachments)
            checkBackup(attachmentDestination.isDirectory || attachmentDestination.mkdir(), "无法建立恢复附件目录")
            snapshot.attachments.forEach { attachment ->
                val target = File(attachmentDestination, "${attachment.id}.blob")
                checkBackup(!target.exists(), "恢复目标附件已存在")
                checkBackup(File(stagedAttachments, target.name).renameTo(target), "无法发布已验证的附件")
                moved += target
            }
            return snapshot
        } catch (failure: Exception) {
            moved.forEach { it.delete() }
            if (!destinationExisted) attachmentDestination.delete()
            if (failure is InvalidBackupException) throw failure
            throw InvalidBackupException("无法验证备份：口令不正确、文件损坏或格式不受支持", failure)
        } finally { work.deleteRecursively() }
    }

    private fun decrypt(source: File, destination: File, password: CharArray) {
        DataInputStream(source.inputStream().buffered()).use { input ->
            val header = ByteArray(HEADER_BYTES).also(input::readFully)
            val fields = ByteBuffer.wrap(header)
            checkBackup(ByteArray(8).also(fields::get).contentEquals(magic), "不是 Trace Life 完整备份")
            checkBackup(fields.int == FORMAT_VERSION, "不支持的备份信封版本")
            checkBackup(fields.int == KDF_ITERATIONS, "不支持的口令派生参数")
            checkBackup(fields.int == CHUNK_BYTES, "不支持的备份分块参数")
            val key = deriveKey(password, header.copyOfRange(20, 36))
            try {
                destination.outputStream().buffered().use { output ->
                    var index = 0L
                    var total = 0L
                    while (true) {
                        checkBackup(index < MAX_FRAMES, "备份分块过多")
                        val length = input.readInt()
                        checkBackup(length in 0..CHUNK_BYTES, "备份分块长度无效")
                        val encrypted = ByteArray(length + TAG_BYTES).also(input::readFully)
                        val plain = cipher(Cipher.DECRYPT_MODE, key, header, index++, length).doFinal(encrypted)
                        checkBackup(plain.size == length, "解密长度无效")
                        if (length == 0) {
                            checkBackup(input.read() == -1, "备份结束后包含额外数据")
                            break
                        }
                        total += length
                        checkBackup(total <= MAX_ARCHIVE_BYTES, "备份解密内容超过限制")
                        output.write(plain)
                        plain.fill(0)
                    }
                }
            } finally { key.fill(0) }
        }
    }

    private class EncryptingStream(private val output: DataOutputStream, private val header: ByteArray, private val key: ByteArray) : OutputStream() {
        private val buffer = ByteArray(CHUNK_BYTES)
        private var size = 0
        private var index = 0L
        private var total = 0L
        private var closed = false
        override fun write(value: Int) { buffer[size++] = value.toByte(); if (size == buffer.size) frame() }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var cursor = offset
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size - size)
                bytes.copyInto(buffer, size, cursor, cursor + count)
                size += count; cursor += count; remaining -= count
                if (size == buffer.size) frame()
            }
        }
        private fun frame() {
            checkBackup(!closed && index < MAX_FRAMES, "备份分块过多")
            total += size
            checkBackup(total <= MAX_ARCHIVE_BYTES, "备份文件超过限制")
            val encrypted = cipher(Cipher.ENCRYPT_MODE, key, header, index++, size).doFinal(buffer, 0, size)
            output.writeInt(size); output.write(encrypted)
            size = 0
        }
        override fun flush() { output.flush() }
        override fun close() {
            if (closed) return
            try { if (size > 0) frame(); frame(); output.flush() }
            finally { closed = true; buffer.fill(0); output.close() }
        }
    }

    private fun cipher(mode: Int, key: ByteArray, header: ByteArray, index: Long, length: Int): Cipher {
        val nonce = header.copyOfRange(36, 48)
        val nonceBuffer = ByteBuffer.wrap(nonce)
        nonceBuffer.putLong(4, nonceBuffer.getLong(4) xor index)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(12).putLong(index).putInt(length).array())
        }
    }

    private fun newHeader(): ByteArray {
        val random = SecureRandom()
        return ByteBuffer.allocate(HEADER_BYTES).put(magic).putInt(FORMAT_VERSION).putInt(KDF_ITERATIONS).putInt(CHUNK_BYTES)
            .put(ByteArray(16).also(random::nextBytes)).put(ByteArray(12).also(random::nextBytes)).array()
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val specification = PBEKeySpec(password, salt, KDF_ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded }
        finally { specification.clearPassword() }
    }

    private data class ManifestEntry(val path: String, val size: Long, val sha256: String)
    private data class Manifest(val snapshotId: String, val entries: List<ManifestEntry>)

    private fun unpackAndVerify(archive: File, metadata: File, attachments: File): RepositorySnapshot {
        verifyZipEnding(archive)
        ZipFile(archive).use { zip ->
            val entries = linkedMapOf<String, ZipEntry>()
            var declaredTotal = 0L
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                checkBackup(entries.size < MAX_ENTRIES && !entries.containsKey(entry.name), "备份包含重复文件或文件过多")
                checkBackup(!entry.isDirectory && validEntryPath(entry.name), "备份包含不安全或未知的文件路径")
                checkBackup(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED, "不支持的 ZIP 压缩方式")
                checkBackup(entry.size in 0..entryLimit(entry.name), "备份文件长度超过限制")
                declaredTotal += entry.size
                checkBackup(declaredTotal <= MAX_TOTAL_BYTES, "备份解压内容超过 1 GiB 限制")
                entries[entry.name] = entry
            }
            val manifestEntry = entries["manifest.json"] ?: invalid("备份缺少清单")
            val manifestBytes = ByteArrayOutputStream()
            copyVerified(zip.getInputStream(manifestEntry), manifestBytes, manifestEntry, null)
            val manifest = parseManifest(manifestBytes.toByteArray())
            checkBackup(manifest.entries.size + 1 == entries.size && manifest.entries.map { it.path }.toSet() == entries.keys - "manifest.json", "备份文件与清单不一致")
            val metadataItem = manifest.entries.singleOrNull { it.path == "snapshot.json" } ?: invalid("备份缺少结构化资料")
            fun extract(item: ManifestEntry, target: File) {
                val entry = entries.getValue(item.path)
                checkBackup(entry.size == item.size, "备份文件长度与清单不一致")
                target.outputStream().buffered().use { output -> copyVerified(zip.getInputStream(entry), output, entry, item.sha256) }
            }
            extract(metadataItem, metadata)
            val snapshot = metadata.inputStream().use(SnapshotJsonCodec::read)
            checkBackup(snapshot.snapshotId == manifest.snapshotId, "备份快照身份不一致")
            validateReferences(snapshot)
            val expected = snapshot.attachments.associateBy { "attachments/${it.id}.blob" }
            checkBackup(expected.keys == manifest.entries.map { it.path }.toSet() - "snapshot.json", "附件清单与结构化资料不一致")
            manifest.entries.filter { it.path != "snapshot.json" }.forEach { item ->
                val attachment = expected.getValue(item.path)
                checkBackup(attachment.sizeBytes == item.size && attachment.sha256 == item.sha256, "附件元数据与清单不一致")
                extract(item, File(attachments, "${attachment.id}.blob"))
            }
            return snapshot
        }
    }

    private fun verifyZipEnding(file: File) {
        checkBackup(file.length() >= 22, "ZIP 文件不完整")
        RandomAccessFile(file, "r").use { input ->
            checkBackup(input.readInt() == 0x504b0304, "ZIP 文件起始无效")
            input.seek(file.length() - 22)
            val footer = ByteArray(22).also(input::readFully)
            checkBackup(footer.copyOfRange(0, 4).contentEquals(byteArrayOf(0x50, 0x4b, 0x05, 0x06)), "ZIP 文件不完整或包含尾随数据")
            checkBackup(footer[4] == 0.toByte() && footer[5] == 0.toByte() && footer[6] == 0.toByte() && footer[7] == 0.toByte() && footer[20] == 0.toByte() && footer[21] == 0.toByte(), "不支持分卷或带注释的 ZIP")
        }
    }

    private fun writeManifest(snapshotId: String, entries: List<ManifestEntry>, output: OutputStream) {
        val writer = JsonWriter(OutputStreamWriter(output, Charsets.UTF_8))
        writer.beginObject().name("formatVersion").value(FORMAT_VERSION.toLong()).name("snapshotId").value(snapshotId).name("entries").beginArray()
        entries.forEach { entry -> writer.beginObject().name("path").value(entry.path).name("size").value(entry.size).name("sha256").value(entry.sha256).endObject() }
        writer.endArray().endObject().flush()
    }

    private fun parseManifest(bytes: ByteArray): Manifest {
        val reader = JsonReader(InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)))
        reader.isLenient = false
        fun name(expected: String) { checkBackup(reader.hasNext() && reader.nextName() == expected, "备份清单字段重复、缺失或不受支持") }
        fun number(): Long { checkBackup(reader.peek() == JsonToken.NUMBER, "清单数值无效"); return reader.nextString().toLongOrNull() ?: invalid("清单整数无效") }
        fun string(): String { checkBackup(reader.peek() == JsonToken.STRING, "清单文字无效"); return reader.nextString() }
        reader.beginObject(); name("formatVersion"); checkBackup(number() == FORMAT_VERSION.toLong(), "不支持的备份清单版本")
        name("snapshotId"); val snapshotId = string()
        name("entries"); reader.beginArray()
        val entries = mutableListOf<ManifestEntry>()
        val paths = mutableSetOf<String>()
        while (reader.hasNext()) {
            checkBackup(entries.size < MAX_ENTRIES - 1, "备份清单文件过多")
            reader.beginObject(); name("path"); val path = string(); name("size"); val size = number(); name("sha256"); val hash = string()
            checkBackup(!reader.hasNext(), "备份清单包含未知字段"); reader.endObject()
            checkBackup(path != "manifest.json" && validEntryPath(path) && paths.add(path), "备份清单包含重复或不安全路径")
            checkBackup(size in 0..entryLimit(path) && sha256.matches(hash), "备份清单长度或哈希无效")
            entries += ManifestEntry(path, size, hash)
        }
        reader.endArray(); checkBackup(!reader.hasNext(), "备份清单包含额外字段"); reader.endObject()
        checkBackup(reader.peek() == JsonToken.END_DOCUMENT, "备份清单包含额外内容")
        return Manifest(snapshotId, entries)
    }

    private fun validateReferences(snapshot: RepositorySnapshot) {
        SnapshotValidation.validate(snapshot)
    }

    private fun validEntryPath(path: String): Boolean = path == "manifest.json" || path == "snapshot.json" ||
        path.startsWith("attachments/") && path.endsWith(".blob") && safeId.matches(path.removePrefix("attachments/").removeSuffix(".blob"))
    private fun entryLimit(path: String): Long = when (path) { "manifest.json" -> MAX_MANIFEST_BYTES; "snapshot.json" -> MAX_METADATA_BYTES; else -> MAX_ATTACHMENT_BYTES }
    private fun hashFile(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        digest.digest().hex()
    }
    private fun addZipFile(zip: ZipOutputStream, path: String, file: File, expectedSize: Long = file.length(), expectedHash: String? = null) {
        zip.putNextEntry(ZipEntry(path))
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer); if (size < 0) break
                total += size; checkBackup(total <= expectedSize && total <= entryLimit(path), "备份期间文件发生变化")
                digest.update(buffer, 0, size); zip.write(buffer, 0, size)
            }
        }
        checkBackup(total == expectedSize && (expectedHash == null || digest.digest().hex() == expectedHash), "备份期间附件发生变化")
        zip.closeEntry()
    }
    private fun copyVerified(input: InputStream, output: OutputStream, entry: ZipEntry, expectedHash: String?) {
        input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val crc = CRC32()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val size = it.read(buffer); if (size < 0) break
                total += size
                checkBackup(total <= entry.size && total <= entryLimit(entry.name), "备份解压长度超过限制")
                digest.update(buffer, 0, size); crc.update(buffer, 0, size); output.write(buffer, 0, size)
            }
            checkBackup(total == entry.size && crc.value == entry.crc, "ZIP 文件长度或 CRC 校验失败")
            checkBackup(expectedHash == null || digest.digest().hex() == expectedHash, "备份 SHA-256 校验失败")
        }
    }
    private class BoundedOutput(private val output: OutputStream, private val limit: Long) : OutputStream() {
        private var total = 0L
        override fun write(value: Int) { total++; checkBackup(total <= limit, "备份元数据超过限制"); output.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { total += length; checkBackup(total <= limit, "备份元数据超过限制"); output.write(bytes, offset, length) }
        override fun flush() = output.flush()
    }
    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun checkBackup(condition: Boolean, message: String) { if (!condition) invalid(message) }
    private fun invalid(message: String): Nothing = throw InvalidBackupException(message)
}
