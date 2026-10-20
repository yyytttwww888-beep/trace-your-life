package dev.tracelife.transfer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Synthetic bytes only: a provider may fail on close, lie about a write, or refuse cleanup. */
class DocumentExportDeliveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "合成已发布文件：这里只测试文档交付，不含个人资料。".repeat(2000).toByteArray()

    @Test fun `success follows closed destination readback and preserves original`() {
        val source = temporary.newFile().apply { writeBytes(bytes) }
        var closed = false
        val out = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        val saved = DocumentExportDelivery.writeAndVerify(source, { out }, {
            assertTrue(closed)
            ByteArrayInputStream(out.toByteArray())
        }, { fail("Successful documents must not be deleted"); false })
        assertEquals(bytes.size.toLong(), saved.bytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }, saved.sha256)
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun `silently truncated provider cannot report completion and is cleaned up`() {
        val source = temporary.newFile().apply { writeBytes(bytes) }
        var deleted = false
        val failure = assertThrows(DocumentDeliveryException::class.java) {
            DocumentExportDelivery.writeAndVerify(source, { ByteArrayOutputStream() }, {
                ByteArrayInputStream(bytes.copyOf(bytes.size - 1))
            }, { deleted = true; true })
        }
        assertTrue(deleted)
        assertFalse(failure.incompleteDocumentRemains)
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun `same length corrupt destination fails content verification`() {
        val source = temporary.newFile().apply { writeBytes(bytes) }
        val damaged = bytes.copyOf().apply { this[5] = (this[5].toInt() xor 1).toByte() }
        assertThrows(DocumentDeliveryException::class.java) {
            DocumentExportDelivery.writeAndVerify(source, { ByteArrayOutputStream() }, {
                ByteArrayInputStream(damaged)
            }, { true })
        }
        assertTrue(source.isFile)
    }

    @Test fun `close failure and refused cleanup leave an explicit incomplete document warning`() {
        val source = temporary.newFile().apply { writeBytes(bytes) }
        val failure = assertThrows(DocumentDeliveryException::class.java) {
            DocumentExportDelivery.writeAndVerify(source, {
                object : ByteArrayOutputStream() { override fun close() { throw IOException("Synthetic provider close failure") } }
            }, { fail("Do not verify a failed close"); ByteArrayInputStream(bytes) }, { false })
        }
        assertTrue(failure.incompleteDocumentRemains)
        assertTrue(failure.message!!.contains("不完整文件"))
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test fun `write-only destination is not claimed as verified and cleanup exception is contained`() {
        val source = temporary.newFile().apply { writeBytes(bytes) }
        val failure = assertThrows(DocumentDeliveryException::class.java) {
            DocumentExportDelivery.writeAndVerify(source, { ByteArrayOutputStream() }, {
                throw SecurityException("Synthetic provider denied readback")
            }, { throw IOException("Synthetic cleanup failure") })
        }
        assertTrue(failure.incompleteDocumentRemains)
        assertTrue(source.isFile)
    }

    @Test fun `matching destination does not excuse a source that differs from the publication receipt`() {
        val source = temporary.newFile().apply { writeBytes(bytes) }
        var deleted = false
        assertThrows(DocumentDeliveryException::class.java) {
            DocumentExportDelivery.writeAndVerify(source, { ByteArrayOutputStream() }, { ByteArrayInputStream(bytes) },
                { deleted = true; true }, expectedSha256 = "0".repeat(64))
        }
        assertTrue(deleted)
        assertTrue(source.isFile)
    }
}
