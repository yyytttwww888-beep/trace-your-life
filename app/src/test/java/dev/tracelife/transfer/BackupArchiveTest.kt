package dev.tracelife.transfer

import dev.tracelife.model.Attachment
import dev.tracelife.model.Cadence
import dev.tracelife.model.ChangeEvent
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RecordRevision
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.model.TemplateRef
import dev.tracelife.model.TemplateVersion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Every payload and password in this suite is explicitly synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupArchiveTest {
    private lateinit var root: File
    private lateinit var attachments: File
    private val password = "synthetic passphrase 2026 合成".toCharArray()
    private val time = Instant.parse("2026-10-16T03:04:05.123456789Z")
    private var sequence = 0

    @Before fun setUp() {
        root = Files.createTempDirectory("synthetic-backup-test-").toFile()
        attachments = File(root, "source-attachments").apply { mkdir() }
    }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun `complete logical data and original attachments round trip on another directory`() {
        val snapshot = fixture()
        val archive = File(root, "portable.tlb")
        val result = BackupArchive.write(snapshot, attachments, archive, password)
        assertEquals(snapshot.snapshotId, result.snapshotId)
        assertEquals(2, result.records)
        assertEquals(2, result.attachments)
        assertEquals(4, result.files)
        assertEquals(archive.length(), result.bytes)
        val restoredFiles = File(root, "restored")
        assertEquals(snapshot, BackupArchive.read(archive, restoredFiles, password))
        snapshot.attachments.forEach { assertArrayEquals(File(attachments, "${it.id}.blob").readBytes(), File(restoredFiles, "${it.id}.blob").readBytes()) }
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".backup-") })
    }

    @Test fun `independent exports randomize encryption and never write plaintext metadata`() {
        val snapshot = fixture()
        val one = File(root, "one.tlb")
        val two = File(root, "two.tlb")
        BackupArchive.write(snapshot, attachments, one, password)
        BackupArchive.write(snapshot, attachments, two, password)
        assertFalse(one.readBytes().contentEquals(two.readBytes()))
        assertFalse(one.readBytes().toString(Charsets.ISO_8859_1).contains(snapshot.storeId))
        assertArrayEquals("synthetic passphrase 2026 合成".toCharArray(), password)
    }

    @Test fun `wrong password corrupted ciphertext and truncated tag publish no attachments`() {
        val archive = File(root, "good.tlb")
        BackupArchive.write(fixture(), attachments, archive, password)
        rejects(archive, "a different synthetic password".toCharArray())
        val encrypted = archive.readBytes()
        rejects(file(encrypted.copyOf().apply { this[BackupArchive.HEADER_BYTES + 8] = (this[BackupArchive.HEADER_BYTES + 8].toInt() xor 1).toByte() }))
        rejects(file(encrypted.copyOf(encrypted.size - 1)))
        rejects(file(encrypted.copyOf(BackupArchive.HEADER_BYTES + 2)))
    }

    @Test fun `unknown versions and attacker supplied KDF settings are rejected before derivation`() {
        val archive = File(root, "good.tlb")
        BackupArchive.write(fixture(), attachments, archive, password)
        val bytes = archive.readBytes()
        rejects(file(bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(8, 999) }))
        rejects(file(bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(12, Int.MAX_VALUE) }))
        rejects(file(bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(16, Int.MAX_VALUE) }))
        rejects(file(bytes.copyOf().apply { this[36] = (this[36].toInt() xor 1).toByte() }))
    }

    @Test fun `frame order duplication terminal deletion and trailing bytes are authenticated`() {
        val snapshot = fixture(randomAttachmentSize = BackupArchive.CHUNK_BYTES * 2 + 17)
        val archive = File(root, "multi-frame.tlb")
        BackupArchive.write(snapshot, attachments, archive, password)
        val bytes = archive.readBytes()
        val header = bytes.copyOfRange(0, BackupArchive.HEADER_BYTES)
        val frames = frames(bytes)
        assertTrue(frames.size >= 4)
        fun changed(parts: List<ByteArray>) = file(header + parts.fold(byteArrayOf()) { accumulated, frame -> accumulated + frame })
        rejects(changed(frames.toMutableList().apply { val first = this[0]; this[0] = this[1]; this[1] = first }))
        rejects(changed(listOf(frames.first()) + frames))
        rejects(changed(frames.dropLast(1)))
        rejects(file(bytes + byteArrayOf(1)))
        val restored = File(root, "large-restored")
        assertEquals(snapshot, BackupArchive.read(archive, restored, password))
    }

    @Test fun `ZIP paths duplicates and unexpected entries cannot escape staging`() {
        val snapshot = fixture()
        rejects(seal(zip(snapshot, extra = "../escaped" to byteArrayOf(1))))
        rejects(seal(zip(snapshot, extra = "unrecognized.txt" to byteArrayOf(1))))
        val duplicated = replaceBytes(zip(snapshot), "attachments/image-two.blob".toByteArray(), "attachments/image-one.blob".toByteArray())
        rejects(seal(duplicated))
        assertFalse(File(root, "escaped").exists())
    }

    @Test fun `manifest hashes lengths references and versions are required`() {
        val snapshot = fixture()
        rejects(seal(zip(snapshot) { manifest -> manifest.getJSONArray("entries").getJSONObject(1).put("sha256", "0".repeat(64)) }))
        rejects(seal(zip(snapshot) { manifest -> manifest.getJSONArray("entries").getJSONObject(0).put("size", 1) }))
        rejects(seal(zip(snapshot) { manifest -> manifest.put("formatVersion", 99) }))
        rejects(seal(zip(snapshot) { manifest -> manifest.getJSONArray("entries").put(manifest.getJSONArray("entries").getJSONObject(0)) }))
        val missingRef = snapshot.copy(records = snapshot.records.map { it.copy(draft = it.draft.copy(attachmentIds = emptySet())) })
        rejects(seal(zip(missingRef)))
        val missingTemplate = snapshot.copy(templates = emptyList())
        rejects(seal(zip(missingTemplate)))
    }

    @Test fun `authenticated but truncated or trailing ZIP data remains invalid`() {
        val zip = zip(fixture())
        rejects(seal(zip.copyOf(zip.size - 10)))
        rejects(seal(zip + byteArrayOf(0, 1, 2)))
    }

    @Test fun `declared oversized entries and ZIP bombs are rejected before extraction`() {
        val snapshot = fixture()
        val payload = zip(snapshot)
        rejects(seal(patchCentralSizes(payload, mapOf("snapshot.json" to BackupArchive.MAX_METADATA_BYTES + 1))))
        rejects(seal(patchCentralSizes(payload, mapOf("attachments/image-one.blob" to BackupArchive.MAX_ATTACHMENT_BYTES + 1))))
        // A highly compressible attachment has a tiny archive but exceeds the extraction limit.
        val compressedBomb = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { output ->
                output.putNextEntry(ZipEntry("attachments/bomb.blob"))
                val zeros = ByteArray(64 * 1024)
                repeat((BackupArchive.MAX_ATTACHMENT_BYTES / zeros.size).toInt() + 1) { output.write(zeros) }
                output.closeEntry()
            }
        }.toByteArray()
        rejects(seal(compressedBomb))
        val oversizedSource = File(root, "too-large.tlb")
        RandomAccessFile(oversizedSource, "rw").use { it.setLength(BackupArchive.MAX_ARCHIVE_BYTES + 1) }
        rejects(oversizedSource)
    }

    @Test fun `total uncompressed budget is enforced across individually legal entries`() {
        val raw = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { output -> repeat(52) { index ->
                output.putNextEntry(ZipEntry("attachments/a$index.blob")); output.write(1); output.closeEntry()
            } }
        }.toByteArray()
        val sizes = (0 until 52).associate { "attachments/a$it.blob" to BackupArchive.MAX_ATTACHMENT_BYTES }
        rejects(seal(patchCentralSizes(raw, sizes)))
    }

    @Test fun `write refuses modified attachments missing references short passwords and existing destinations`() {
        val snapshot = fixture()
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.write(snapshot, attachments, File(root, "short.tlb"), "short".toCharArray()) }
        val existing = File(root, "existing.tlb").apply { writeText("synthetic previous backup") }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.write(snapshot, attachments, existing, password) }
        assertEquals("synthetic previous backup", existing.readText())
        File(attachments, "image-one.blob").appendText("modified")
        val failedOutput = File(root, "failure.tlb")
        assertThrows(InvalidBackupException::class.java) { BackupArchive.write(snapshot, attachments, failedOutput, password) }
        assertFalse(failedOutput.exists())
        assertThrows(InvalidBackupException::class.java) { BackupArchive.write(snapshot.copy(attachments = emptyList()), attachments, failedOutput, password) }
    }

    @Test fun `restore refuses a populated destination and preserves caller files`() {
        val archive = File(root, "good.tlb")
        BackupArchive.write(fixture(), attachments, archive, password)
        val destination = File(root, "preexisting").apply { mkdir() }
        val original = File(destination, "keep.txt").apply { writeText("synthetic preexisting data") }
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.read(archive, destination, password) }
        assertEquals("synthetic preexisting data", original.readText())
    }

    @Test fun `logical JSON rejects duplicate unknown coerced and incompatible fields`() {
        val metadata = metadata(fixture()).toString(Charsets.UTF_8)
        listOf(
            metadata.replace("\"generation\":41", "\"generation\":41,\"generation\":42"),
            metadata.replace("\"generation\":41", "\"generation\":\"41\""),
            metadata.replace("\"formatVersion\":1", "\"formatVersion\":999"),
            metadata.replace("\"sourceSchemaVersion\":2", "\"sourceSchemaVersion\":999"),
            metadata.dropLast(1) + ",\"unknown\":true}",
            metadata + "{}",
        ).forEach { invalid -> assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(invalid.byteInputStream()) } }
    }

    @Test fun `logical event history beyond one hundred thousand entries remains readable`() {
        val snapshot = fixture().copy(changes = (1L..100_001L).map { ChangeEvent(it, "template", 1, "TEMPLATE_ARCHIVED", time) })
        val bytes = metadata(snapshot)
        assertTrue(bytes.size < BackupArchive.MAX_METADATA_BYTES)
        assertEquals(snapshot, SnapshotJsonCodec.read(bytes.inputStream()))
    }

    @Test fun `unknown nested fields are rejected before their large values are consumed`() {
        val malicious = ("{\"records\":[{\"unknown\":[" + "{},".repeat(50_000) + "{}]}]}").toByteArray()
        val input = object : java.io.ByteArrayInputStream(malicious) {
            var consumed = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also { if (it > 0) consumed += it }
        }
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(input) }
        assertTrue("Unknown AST must not be allocated", input.consumed < malicious.size / 2)
    }

    @Test fun `write refuses snapshots exceeding the shared structural read budget before output`() {
        val snapshot = fixture()
        val record = snapshot.records.first()
        val expanded = snapshot.copy(records = (1..100_000).map { record.copy(id = "synthetic-$it") }, changes = emptyList())
        val output = ByteArrayOutputStream()
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.write(expanded, output) }
        assertEquals(0, output.size())
    }

    private fun fixture(randomAttachmentSize: Int = 200): RepositorySnapshot {
        fun attachment(id: String, size: Int): Attachment {
            val bytes = ByteArray(size).also(SecureRandom()::nextBytes)
            File(attachments, "$id.blob").writeBytes(bytes)
            return Attachment(id, "$id-合成图片.png", "image/png", size.toLong(), hash(bytes))
        }
        val photos = listOf(attachment("image-one", randomAttachmentSize), attachment("image-two", 103))
        val draft = TemplateDraft("合成复盘", Cadence.WEEKLY, "合成方法说明", listOf(TemplateQuestion("q1", "合成问题", "合成提示", "expectation")), "# 合成原文\n## 合成问题\n合成提示")
        val templates = listOf(TemplateVersion("template", 1, draft, time, true), TemplateVersion("template", 2, draft.copy(name = "合成新版本"), time.plusSeconds(1), true))
        val original = RecordRevision("record-one", 1, RecordDraft("合成资料：原文", Occurrence(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-03"), "Asia/Shanghai"), setOf("工作", "学习"), TemplateRef("template", 1), mapOf("q1" to "合成回答"), setOf("image-one")), time, time)
        return RepositorySnapshot("synthetic-snapshot", "synthetic-store", 41, time.plusSeconds(3),
            records = listOf(original, original.copy(revision = 2, draft = original.draft.copy(body = "合成资料：修订", template = TemplateRef("template", 2), attachmentIds = setOf("image-two")), updatedAt = time.plusSeconds(2), archived = true), RecordRevision("record-two", 1, RecordDraft("合成资料：未知发生日期", Occurrence(zoneId = "UTC")), time, time)),
            templates = templates, attachments = photos,
            deletedRecords = listOf(Deletion("deleted-record", 3, time.plusSeconds(2))), deletedTemplates = listOf(Deletion("deleted-template", 2, time.plusSeconds(2))),
            changes = listOf(ChangeEvent(1, "record-one", 1, "RECORD_CREATED", time), ChangeEvent(2, "record-one", 2, "RECORD_UPDATED", time.plusSeconds(2))),
        )
    }

    private fun metadata(snapshot: RepositorySnapshot): ByteArray = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toByteArray()
    private fun zip(snapshot: RepositorySnapshot, extra: Pair<String, ByteArray>? = null, alterManifest: (JSONObject) -> Unit = {}): ByteArray {
        val files = linkedMapOf("snapshot.json" to metadata(snapshot))
        snapshot.attachments.forEach { files["attachments/${it.id}.blob"] = File(attachments, "${it.id}.blob").readBytes() }
        val manifest = JSONObject().put("formatVersion", 1).put("snapshotId", snapshot.snapshotId).put("entries", JSONArray().apply {
            files.forEach { (path, bytes) -> put(JSONObject().put("path", path).put("size", bytes.size).put("sha256", hash(bytes))) }
        })
        alterManifest(manifest)
        return ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { zip ->
            fun add(path: String, content: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(content); zip.closeEntry() }
            add("manifest.json", manifest.toString().toByteArray())
            files.forEach(::add)
            extra?.let { add(it.first, it.second) }
        } }.toByteArray()
    }

    /** Independent fixture encoder permits hostile ZIPs inside a correctly authenticated envelope. */
    private fun seal(zip: ByteArray): File {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val baseNonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val header = ByteBuffer.allocate(48).put("TLBACKUP".toByteArray()).putInt(1).putInt(600_000).putInt(1_048_576).put(salt).put(baseNonce).array()
        val specification = PBEKeySpec(password, salt, 600_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded
        specification.clearPassword()
        val archive = File(root, "sealed-${sequence++}.tlb")
        try {
            DataOutputStream(archive.outputStream()).use { output ->
                output.write(header)
                var index = 0L
                var offset = 0
                do {
                    val count = minOf(1_048_576, zip.size - offset)
                    val nonce = baseNonce.copyOf()
                    ByteBuffer.wrap(nonce).putLong(4, ByteBuffer.wrap(nonce).getLong(4) xor index)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                    cipher.updateAAD(header); cipher.updateAAD(ByteBuffer.allocate(12).putLong(index++).putInt(count).array())
                    output.writeInt(count); output.write(cipher.doFinal(zip, offset, count))
                    offset += count
                } while (count != 0)
            }
        } finally { key.fill(0) }
        return archive
    }

    private fun rejects(archive: File, passphrase: CharArray = password) {
        val destination = File(root, "failed-${sequence++}")
        assertThrows(InvalidBackupException::class.java) { BackupArchive.read(archive, destination, passphrase) }
        assertTrue(!destination.exists() || destination.list()?.isEmpty() == true)
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".backup-read-") })
    }
    private fun file(bytes: ByteArray): File = File(root, "changed-${sequence++}.tlb").apply { writeBytes(bytes) }
    private fun frames(bytes: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 48
        while (offset < bytes.size) {
            val length = ByteBuffer.wrap(bytes, offset, 4).int
            frames += bytes.copyOfRange(offset, offset + 4 + length + 16)
            offset += 4 + length + 16
        }
        return frames
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun replaceBytes(bytes: ByteArray, old: ByteArray, replacement: ByteArray): ByteArray {
        require(old.size == replacement.size)
        return bytes.copyOf().also { copy -> for (i in 0..copy.size - old.size) if (copy.copyOfRange(i, i + old.size).contentEquals(old)) replacement.copyInto(copy, i) }
    }
    private fun patchCentralSizes(zip: ByteArray, sizes: Map<String, Long>): ByteArray = zip.copyOf().also { bytes ->
        var cursor = 0
        while (cursor <= bytes.size - 46) {
            if (ByteBuffer.wrap(bytes, cursor, 4).int != 0x504b0102) { cursor++; continue }
            val fields = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val nameLength = fields.getShort(cursor + 28).toInt() and 65535
            val extraLength = fields.getShort(cursor + 30).toInt() and 65535
            val commentLength = fields.getShort(cursor + 32).toInt() and 65535
            val name = bytes.copyOfRange(cursor + 46, cursor + 46 + nameLength).toString(Charsets.UTF_8)
            sizes[name]?.let { fields.putInt(cursor + 24, it.toInt()) }
            cursor += 46 + nameLength + extraLength + commentLength
        }
    }
}
