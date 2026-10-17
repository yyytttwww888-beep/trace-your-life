package dev.tracelife.providers

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

internal class DeepSeekHttpResponse(val status: Int, val body: ByteArray)
internal fun interface DeepSeekTransport { fun execute(body: String, apiKey: CharArray): DeepSeekHttpResponse }

/** Fixed official origin. No redirects, app retries, response/error logging or credential export. */
internal class HttpsDeepSeekTransport(
    private val open: () -> HttpsURLConnection = { URL(ENDPOINT).openConnection() as HttpsURLConnection },
    private val deadlineMillis: Long = 90_000,
) : DeepSeekTransport {
    override fun execute(body: String, apiKey: CharArray): DeepSeekHttpResponse {
        val bytes = body.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 262_144 && deadlineMillis in 1..90_000)
        var connection: HttpsURLConnection? = null
        val expired = AtomicBoolean(false)
        var timer: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            val target = open().also { connection = it }
            require(target.url.toString() == ENDPOINT) { "DeepSeek 仅允许官方接口" }
            target.requestMethod = "POST"
            target.instanceFollowRedirects = false
            target.useCaches = false
            target.connectTimeout = 15_000
            target.readTimeout = 60_000
            target.doOutput = true
            target.setFixedLengthStreamingMode(bytes.size)
            target.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            target.setRequestProperty("Accept", "application/json")
            target.setRequestProperty("Authorization", "Bearer " + String(apiKey))
            timer = deadlines.schedule({ expired.set(true); target.disconnect() }, deadlineMillis, TimeUnit.MILLISECONDS)
            target.outputStream.use { it.write(bytes) }
            val status = target.responseCode
            if (expired.get()) throw DeepSeekException(DeepSeekFailure.TIMEOUT)
            // Error bodies can echo private input. Do not read or persist them.
            if (status != 200) return DeepSeekHttpResponse(status, byteArrayOf())
            if (target.contentLengthLong > 262_144) throw DeepSeekException(DeepSeekFailure.RESPONSE)
            val result = ByteArrayOutputStream()
            target.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    if (expired.get()) throw DeepSeekException(DeepSeekFailure.TIMEOUT)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > 262_144) throw DeepSeekException(DeepSeekFailure.RESPONSE)
                    result.write(buffer, 0, count)
                }
            }
            if (expired.get()) throw DeepSeekException(DeepSeekFailure.TIMEOUT)
            return DeepSeekHttpResponse(status, result.toByteArray())
        } catch (_: SocketTimeoutException) { throw DeepSeekException(DeepSeekFailure.TIMEOUT) }
        catch (_: IOException) { throw DeepSeekException(if (expired.get()) DeepSeekFailure.TIMEOUT else DeepSeekFailure.NETWORK) }
        finally { timer?.cancel(false); connection?.disconnect() }
    }

    companion object {
        internal const val ENDPOINT = "https://api.deepseek.com/chat/completions"
        private val deadlines = Executors.newSingleThreadScheduledExecutor { action -> Thread(action, "deepseek-request-deadline").apply { isDaemon = true } }
    }
}
