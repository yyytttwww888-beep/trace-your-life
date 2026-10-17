package dev.tracelife.providers

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Injectable HTTPS connections record only synthetic requests and never open sockets. */
class DeepSeekTransportTest {
    private val syntheticKey = "synthetic-transport-key".toCharArray()

    @Test fun `POST sends exactly the provided UTF8 body with private authorization and fixed official origin`() {
        val connection = FakeConnection(response = "{\"synthetic\":true}".toByteArray())
        val body = "{\"synthetic\":\"合成证据与Unicode😀\"}"
        val response = HttpsDeepSeekTransport({ connection }).execute(body, syntheticKey)
        assertEquals(200, response.status)
        assertArrayEquals("{\"synthetic\":true}".toByteArray(), response.body)
        assertEquals("https://api.deepseek.com/chat/completions", connection.url.toString())
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer synthetic-transport-key", connection.getRequestProperty("Authorization"))
        assertEquals("application/json; charset=utf-8", connection.getRequestProperty("Content-Type"))
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertTrue(connection.doOutput)
        assertFalse(connection.useCaches)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(body.toByteArray(Charsets.UTF_8).size, connection.declaredRequestLength)
        assertArrayEquals(body.toByteArray(Charsets.UTF_8), connection.sent.toByteArray())
        assertTrue(connection.connectTimeout > 0)
        assertTrue(connection.readTimeout > 0)
        assertTrue(connection.disconnected.count == 0L)
        assertEquals(0, connection.errorStreamReads)
    }

    @Test fun `redirect and error responses are not followed or read even if their bodies contain private data`() {
        listOf(301, 302, 307, 308, 401, 402, 429, 500).forEach { status ->
            var opened = 0
            val connection = FakeConnection(status, "synthetic private body".toByteArray())
            val response = HttpsDeepSeekTransport({ opened++; connection }).execute("{}", syntheticKey)
            assertEquals(status, response.status)
            assertTrue(response.body.isEmpty())
            assertEquals(1, opened)
            assertFalse(connection.instanceFollowRedirects)
            assertEquals(0, connection.inputStreamReads)
            assertEquals(0, connection.errorStreamReads)
            assertTrue(connection.disconnected.count == 0L)
        }
    }

    @Test fun `response limit is enforced for declared and streaming bodies`() {
        val declared = FakeConnection(declaredResponseLength = 262_145)
        assertFailure(DeepSeekFailure.RESPONSE) { HttpsDeepSeekTransport({ declared }).execute("{}", syntheticKey) }
        assertEquals(0, declared.inputStreamReads)
        val streaming = FakeConnection(response = ByteArray(262_145), declaredResponseLength = -1)
        assertFailure(DeepSeekFailure.RESPONSE) { HttpsDeepSeekTransport({ streaming }).execute("{}", syntheticKey) }
        assertEquals(1, streaming.inputStreamReads)
        assertTrue(streaming.disconnected.count == 0L)
        val exact = FakeConnection(response = ByteArray(262_144) { 7 }, declaredResponseLength = -1)
        assertEquals(262_144, HttpsDeepSeekTransport({ exact }).execute("{}", syntheticKey).body.size)
    }

    @Test fun `socket timeout and IO errors are sanitized without retaining exception causes`() {
        val socket = FakeConnection(outputFailure = SocketTimeoutException("synthetic secret ${String(syntheticKey)}"))
        assertFailure(DeepSeekFailure.TIMEOUT) { HttpsDeepSeekTransport({ socket }).execute("{}", syntheticKey) }
        val network = FakeConnection(outputFailure = IOException("synthetic secret ${String(syntheticKey)}"))
        assertFailure(DeepSeekFailure.NETWORK) { HttpsDeepSeekTransport({ network }).execute("{}", syntheticKey) }
        assertTrue(socket.disconnected.count == 0L)
        assertTrue(network.disconnected.count == 0L)
        assertFailure(DeepSeekFailure.NETWORK) {
            HttpsDeepSeekTransport({ throw IOException("synthetic secret ${String(syntheticKey)}") }).execute("{}", syntheticKey)
        }
    }

    @Test fun `total deadline disconnects a blocked response stream and reports timeout`() {
        val connection = FakeConnection()
        connection.customInput = object : InputStream() {
            override fun read(): Int {
                check(connection.disconnected.await(5, TimeUnit.SECONDS)) { "synthetic deadline did not disconnect" }
                throw IOException("synthetic stream closed by disconnect")
            }
        }
        assertFailure(DeepSeekFailure.TIMEOUT) { HttpsDeepSeekTransport({ connection }, deadlineMillis = 20).execute("{}", syntheticKey) }
        assertTrue(connection.disconnected.count == 0L)
    }

    @Test fun `different origin and oversized request are rejected before authorization or body transmission`() {
        val wrongOrigin = FakeConnection(endpoint = "https://example.invalid/chat/completions")
        assertThrows(IllegalArgumentException::class.java) { HttpsDeepSeekTransport({ wrongOrigin }).execute("{}", syntheticKey) }
        assertNull(wrongOrigin.getRequestProperty("Authorization"))
        assertEquals(0, wrongOrigin.sent.size())
        var opened = false
        assertThrows(IllegalArgumentException::class.java) {
            HttpsDeepSeekTransport({ opened = true; FakeConnection() }).execute("x".repeat(262_145), syntheticKey)
        }
        assertFalse(opened)
    }

    private fun assertFailure(reason: DeepSeekFailure, action: () -> Unit) {
        val failure = assertThrows(DeepSeekException::class.java) { action() }
        assertEquals(reason, failure.reason)
        assertEquals("模型请求未完成：${reason.name}", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(String(syntheticKey)))
    }

    private class FakeConnection(
        private val status: Int = 200,
        private val response: ByteArray = byteArrayOf(),
        private val declaredResponseLength: Long = response.size.toLong(),
        private val outputFailure: IOException? = null,
        endpoint: String = "https://api.deepseek.com/chat/completions",
    ) : HttpsURLConnection(URL(endpoint)) {
        val sent = ByteArrayOutputStream()
        val disconnected = CountDownLatch(1)
        var declaredRequestLength: Int? = null
        var inputStreamReads = 0
        var errorStreamReads = 0
        var customInput: InputStream? = null

        override fun setFixedLengthStreamingMode(length: Int) { declaredRequestLength = length }
        override fun getOutputStream(): OutputStream { outputFailure?.let { throw it }; return sent }
        override fun getResponseCode() = status
        override fun getContentLengthLong() = declaredResponseLength
        override fun getInputStream(): InputStream { inputStreamReads++; return customInput ?: ByteArrayInputStream(response) }
        override fun getErrorStream(): InputStream { errorStreamReads++; return ByteArrayInputStream("synthetic private error".toByteArray()) }
        override fun disconnect() { disconnected.countDown() }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "SYNTHETIC_TEST_SUITE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
