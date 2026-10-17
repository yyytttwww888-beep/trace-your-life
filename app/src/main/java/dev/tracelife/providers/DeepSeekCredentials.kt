package dev.tracelife.providers

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class CredentialStatus { MISSING, CONFIGURED, UNREADABLE }

class MissingCredentialException : IllegalStateException("DeepSeek API key is not configured")
class CredentialAccessException : IllegalStateException("DeepSeek API key cannot be accessed; configure it again")

/**
 * Stores only authenticated ciphertext in Android's private, non-backed-up directory.
 * The AES key stays in Android Keystore. Calls perform disk/Keystore work and belong off the UI thread.
 * The caller owns and must clear the array passed to [configure]; internal plaintext arrays are cleared.
 */
class DeepSeekCredentials internal constructor(
    private val directory: File,
    private val applicationId: String,
    private val keyProvider: (createIfMissing: Boolean) -> SecretKey,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, "deepseek"),
        context.applicationContext.packageName,
        { create -> androidKey(context.applicationContext.packageName, create) },
    )

    private val file = File(directory, "api-key.enc")
    private val atomicFile = AtomicFile(file)
    private val lock = locks.computeIfAbsent(file.canonicalPath) { Any() }
    private val header = ByteBuffer.allocate(MAGIC.size + Int.SIZE_BYTES).put(MAGIC).putInt(VERSION).array()

    fun configure(apiKey: CharArray) {
        require(apiKey.size in MIN_KEY_LENGTH..MAX_KEY_LENGTH) { INVALID_KEY_MESSAGE }
        val copy = apiKey.copyOf()
        var plaintext: ByteArray? = null
        try {
            require(copy.all { it.code in 0x21..0x7e }) { INVALID_KEY_MESSAGE }
            val bytes = ByteArray(copy.size) { copy[it].code.toByte() }
            plaintext = bytes
            synchronized(lock) {
                try {
                    check(directory.isDirectory || directory.mkdirs())
                    val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                        init(Cipher.ENCRYPT_MODE, keyProvider(true))
                        updateAAD(header)
                        updateAAD(applicationId.toByteArray(Charsets.UTF_8))
                    }
                    val nonce = cipher.iv
                    check(nonce.size == NONCE_BYTES)
                    val ciphertext = cipher.doFinal(bytes)
                    var output: FileOutputStream? = null
                    try {
                        output = atomicFile.startWrite()
                        output.write(header)
                        output.write(nonce)
                        output.write(ciphertext)
                        // AtomicFile also syncs; explicit sync allows an I/O failure to be reported.
                        output.fd.sync()
                        atomicFile.finishWrite(output)
                        output = null
                        // Some AtomicFile implementations log a failed rename instead of throwing.
                        val saved = readKey()
                        try { check(saved.contentEquals(copy)) } finally { saved.fill('\u0000') }
                        syncDirectory()
                    } finally {
                        if (output != null) atomicFile.failWrite(output)
                    }
                } catch (_: Exception) {
                    // Keystore/provider exceptions can include implementation details. Never retain them.
                    throw CredentialAccessException()
                }
            }
        } finally {
            copy.fill('\u0000')
            plaintext?.fill(0)
        }
    }

    fun status(): CredentialStatus = synchronized(lock) {
        try {
            readKey().fill('\u0000')
            CredentialStatus.CONFIGURED
        } catch (_: MissingCredentialException) {
            CredentialStatus.MISSING
        } catch (_: CredentialAccessException) {
            CredentialStatus.UNREADABLE
        }
    }

    fun isConfigured(): Boolean = status() == CredentialStatus.CONFIGURED

    /** Removes pending/legacy versions first so a completed clear cannot restore an older key. */
    fun clear() = synchronized(lock) {
        try {
            Files.deleteIfExists(File(file.path + ".new").toPath())
            Files.deleteIfExists(File(file.path + ".bak").toPath())
            Files.deleteIfExists(file.toPath())
            if (directory.isDirectory) syncDirectory()
            Unit
        } catch (_: Exception) {
            throw CredentialAccessException()
        }
    }

    private fun syncDirectory() {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    /** A provider may use this only for the current request; it must not retain the provided array. */
    internal fun <T> withKey(block: (CharArray) -> T): T = synchronized(lock) {
        val plaintext = readKey()
        try {
            block(plaintext)
        } finally {
            plaintext.fill('\u0000')
        }
    }

    private fun readKey(): CharArray {
        var plaintext: ByteArray? = null
        try {
            if (!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) {
                throw MissingCredentialException()
            }
            val encoded = atomicFile.openRead().use { input ->
                val bounded = ByteArray(MAX_FILE_BYTES + 1)
                var length = 0
                while (length < bounded.size) {
                    val count = input.read(bounded, length, bounded.size - length)
                    if (count == -1) break
                    check(count > 0)
                    length += count
                }
                check(length <= MAX_FILE_BYTES)
                bounded.copyOf(length)
            }
            check(encoded.size in (header.size + NONCE_BYTES + TAG_BYTES + MIN_KEY_LENGTH)..
                (header.size + NONCE_BYTES + TAG_BYTES + MAX_KEY_LENGTH))
            check(encoded.copyOfRange(0, header.size).contentEquals(header))
            val nonce = encoded.copyOfRange(header.size, header.size + NONCE_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, keyProvider(false), GCMParameterSpec(TAG_BYTES * 8, nonce))
                updateAAD(header)
                updateAAD(applicationId.toByteArray(Charsets.UTF_8))
            }
            val bytes = cipher.doFinal(encoded, header.size + NONCE_BYTES, encoded.size - header.size - NONCE_BYTES)
            plaintext = bytes
            check(bytes.size in MIN_KEY_LENGTH..MAX_KEY_LENGTH && bytes.all { it.toInt() in 0x21..0x7e })
            return CharArray(bytes.size) { bytes[it].toInt().toChar() }
        } catch (failure: MissingCredentialException) {
            throw failure
        } catch (_: Exception) {
            throw CredentialAccessException()
        } finally {
            plaintext?.fill(0)
        }
    }

    private companion object {
        const val VERSION = 1
        const val MIN_KEY_LENGTH = 8
        const val MAX_KEY_LENGTH = 512
        const val MAX_FILE_BYTES = 8 * 1024
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val INVALID_KEY_MESSAGE = "API key must contain 8 to 512 printable ASCII characters without spaces"
        val MAGIC = byteArrayOf(0x54, 0x4c, 0x44, 0x53, 0x4b, 0x45, 0x59, 0x00)
        val locks = ConcurrentHashMap<String, Any>()

        fun androidKey(applicationId: String, createIfMissing: Boolean): SecretKey {
            val alias = "$applicationId.deepseek.credentials.v1"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(alias)) {
                return store.getKey(alias, null) as? SecretKey ?: throw CredentialAccessException()
            }
            check(createIfMissing)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build())
            }.generateKey()
        }
    }
}
