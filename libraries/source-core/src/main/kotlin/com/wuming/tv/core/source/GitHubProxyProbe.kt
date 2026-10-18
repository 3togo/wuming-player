package com.wuming.tv.core.source

import java.io.IOException

/**
 * GitHub proxy/mirror speed-probe algorithm shared by the Android and desktop clients.
 *
 * The two ports probe the same way: open a small ranged GET against a reference
 * `raw.githubusercontent.com` file, accept 200/206, retry without the Range header when the mirror
 * rejects it (400/501), cap the download, and classify the outcome as success, truncated, or a
 * transport error (timeout / DNS / other). Only the HTTP stack differs, so it is injected through
 * [Transport] and the policy lives here instead of being copied into each port.
 */
object GitHubProxyProbe {

    const val PROBE_CONNECT_TIMEOUT_MS = 5_000
    const val PROBE_READ_TIMEOUT_MS = 15_000
    const val PROBE_MIN_CONTENT_BYTES = 1_000
    const val PROBE_DOWNLOAD_LIMIT = 16_384

    /** Status code plus how many bytes were read (capped at [PROBE_DOWNLOAD_LIMIT]). */
    data class Outcome(val status: Int, val bytes: Int)

    /**
     * One ranged/direct GET for a single URL. Implementations supply the platform HTTP stack
     * (Android [java.net.HttpURLConnection], desktop OkHttp). The outcome downloads the body up to
     * [PROBE_DOWNLOAD_LIMIT] when the response is 200/206; otherwise [Outcome.bytes] is 0.
     */
    fun interface Transport {
        @Throws(IOException::class)
        fun probe(url: String, withRange: Boolean): Outcome
    }

    data class Result(
        val elapsedMs: Int,
        val status: Int,
        val contentBytes: Int,
        val error: String?,
    ) {
        val success: Boolean get() = elapsedMs >= 0 && contentBytes >= PROBE_MIN_CONTENT_BYTES
    }

    @JvmStatic
    fun probeOne(transport: Transport, url: String): Result {
        val started = System.nanoTime()
        return try {
            var (status, bytes) = transport.probe(url, withRange = true)
            if (status == 400 || status == 501) {
                val retry = transport.probe(url, withRange = false)
                status = retry.status
                bytes = retry.bytes
            }
            if (status != 200 && status != 206) {
                return Result(-1, status, bytes, null)
            }
            val elapsed = ((System.nanoTime() - started) / 1_000_000).toInt()
            if (bytes < PROBE_MIN_CONTENT_BYTES) {
                return Result(elapsed, status, bytes, "truncated")
            }
            Result(elapsed, status, bytes, null)
        } catch (e: java.net.SocketTimeoutException) {
            Result(-1, 0, 0, "timeout")
        } catch (e: java.net.UnknownHostException) {
            Result(-1, 0, 0, "DNS fail")
        } catch (e: Throwable) {
            Result(-1, 0, 0, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Fastest successful probe, or null when none succeeded. */
    @JvmStatic
    fun best(results: List<Result>): Result? = results.filter { it.success }.minByOrNull { it.elapsedMs }
}
