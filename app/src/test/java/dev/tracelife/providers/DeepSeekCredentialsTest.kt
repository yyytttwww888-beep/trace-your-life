package dev.tracelife.providers

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Synthetic credentials only. JVM AES tests do not establish Android Keystore/device compatibility. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekCredentialsTest {
    private lateinit var directory: File
    private lateinit var secretKey: SecretKey
    private lateinit var credentials: DeepSeekCredentials
    private val first = "synthetic-api-credential-one"
    private val second = "synthetic-api-credential-two"
    private val applicationId = "dev.tracelife.synthetic"

    @Before fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        directory = File(context.noBackupFilesDir, "synthetic-deepseek-${UUID.randomUUID()}")
        secretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        credentials = store()
    }

    @After fun tearDown() {
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun `configuration survives instances rotates and clears without writing plaintext`() {
        assertEquals(CredentialStatus.MISSING, credentials.status())
        assertFalse(credentials.isConfigured())
        val input = first.toCharArray()
        credentials.configure(input)
        assertArrayEquals(first.toCharArray(), input)
        assertEquals(CredentialStatus.CONFIGURED, store().status())
        assertTrue(store().isConfigured())
        assertEquals(first, store().withKey { String(it) })
        assertFalse(file().readBytes().toString(Charsets.ISO_8859_1).contains(first))
        assertFalse(credentials.toString().contains(first))
        assertEquals(listOf("api-key.enc"), directory.list()!!.toList())
        credentials.configure(second.toCharArray())
        assertEquals(second, store().withKey { String(it) })
        credentials.clear()
        assertEquals(CredentialStatus.MISSING, store().status())
        assertTrue(directory.listFiles()!!.isEmpty())
        credentials.clear()
        credentials.configure(first.toCharArray())
        assertEquals(first, store().withKey { String(it) })
    }

    @Test fun `invalid key never replaces an existing valid configuration`() {
        credentials.configure(first.toCharArray())
        val encoded = file().readBytes()
        listOf("", "short", "a".repeat(513), "synthetic key", "synthetic\nkey", "synthetic\u0000key", "合成测试credential").forEach { invalid ->
            val failure = assertThrows(IllegalArgumentException::class.java) { credentials.configure(invalid.toCharArray()) }
            assertEquals("API key must contain 8 to 512 printable ASCII characters without spaces", failure.message)
            assertNull(failure.cause)
            assertArrayEquals(encoded, file().readBytes())
        }
        assertEquals(first, credentials.withKey { String(it) })
    }

    @Test fun `credential arrays are cleared after successful and failing callbacks`() {
        credentials.configure(first.toCharArray())
        var escaped: CharArray? = null
        assertEquals(first.length, credentials.withKey { escaped = it; it.size })
        assertTrue(escaped!!.all { it == '\u0000' })
        assertThrows(UnsupportedOperationException::class.java) {
            credentials.withKey<Unit> { escaped = it; throw UnsupportedOperationException("synthetic callback failure") }
        }
        assertTrue(escaped!!.all { it == '\u0000' })
        assertTrue(credentials.isConfigured())
    }

    @Test fun `tampered ciphertext is visibly unreadable and can be replaced`() {
        credentials.configure(first.toCharArray())
        val encoded = file().readBytes()
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        file().writeBytes(encoded)
        assertEquals(CredentialStatus.UNREADABLE, credentials.status())
        assertFalse(credentials.isConfigured())
        val failure = assertThrows(CredentialAccessException::class.java) { credentials.withKey { fail("corrupt key must not reach caller") } }
        assertEquals("DeepSeek API key cannot be accessed; configure it again", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(first))
        credentials.configure(second.toCharArray())
        assertEquals(second, credentials.withKey { String(it) })
    }

    @Test fun `wrong application identity or lost wrapping key cannot decrypt stored key`() {
        credentials.configure(first.toCharArray())
        val wrongApp = DeepSeekCredentials(directory, "dev.tracelife.different") { secretKey }
        assertEquals(CredentialStatus.UNREADABLE, wrongApp.status())
        val replacementKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val wrongKey = DeepSeekCredentials(directory, applicationId) { replacementKey }
        assertEquals(CredentialStatus.UNREADABLE, wrongKey.status())
        assertEquals(first, credentials.withKey { String(it) })
    }

    @Test fun `missing and inaccessible configuration are distinct and provider failures are sanitized`() {
        var keyCalls = 0
        val inaccessible = DeepSeekCredentials(directory, applicationId) {
            keyCalls++
            throw IllegalStateException("synthetic provider error $first")
        }
        assertEquals(CredentialStatus.MISSING, inaccessible.status())
        assertThrows(MissingCredentialException::class.java) { inaccessible.withKey { fail() } }
        assertEquals(0, keyCalls)
        credentials.configure(first.toCharArray())
        val failure = assertThrows(CredentialAccessException::class.java) { inaccessible.configure(second.toCharArray()) }
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(first))
        assertEquals(CredentialStatus.UNREADABLE, inaccessible.status())
        assertEquals(first, credentials.withKey { String(it) })
    }

    @Test fun `oversized malformed truncated and pending-only files fail closed`() {
        directory.mkdirs()
        listOf(ByteArray(0), ByteArray(10), ByteArray(8193)).forEach { malformed ->
            file().writeBytes(malformed)
            assertEquals(CredentialStatus.UNREADABLE, credentials.status())
        }
        credentials.configure(first.toCharArray())
        val valid = file().readBytes()
        file().writeBytes(valid.copyOf(valid.size - 1))
        assertEquals(CredentialStatus.UNREADABLE, credentials.status())
        file().writeBytes(valid.apply { this[0] = 0 })
        assertEquals(CredentialStatus.UNREADABLE, credentials.status())
        credentials.clear()
        File(directory, "api-key.enc.new").writeText("synthetic incomplete ciphertext")
        assertEquals(CredentialStatus.UNREADABLE, credentials.status())
        credentials.clear()
        assertEquals(CredentialStatus.MISSING, credentials.status())
    }

    @Test fun `identical reconfiguration uses a fresh nonce and clear removes legacy copies`() {
        credentials.configure(first.toCharArray())
        val before = file().readBytes()
        credentials.configure(first.toCharArray())
        assertFalse(before.contentEquals(file().readBytes()))
        File(directory, "api-key.enc.bak").writeBytes(before)
        File(directory, "api-key.enc.new").writeBytes(before)
        credentials.clear()
        assertTrue(directory.listFiles()!!.isEmpty())
        assertEquals(CredentialStatus.MISSING, credentials.status())
    }

    @Test fun `parallel instances share atomic rotation and request access`() {
        credentials.configure(first.toCharArray())
        val readers = store()
        val writers = store()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val reader = executor.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(20) {
                    readers.withKey { assertTrue(String(it) in setOf(first, second)) }
                }
            }
            val writer = executor.submit {
                start.countDown()
                repeat(10) { writers.configure((if (it % 2 == 0) second else first).toCharArray()) }
            }
            reader.get(10, TimeUnit.SECONDS)
            writer.get(10, TimeUnit.SECONDS)
            assertEquals(first, credentials.withKey { String(it) })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun store() = DeepSeekCredentials(directory, applicationId) { secretKey }
    private fun file() = File(directory, "api-key.enc")
}
