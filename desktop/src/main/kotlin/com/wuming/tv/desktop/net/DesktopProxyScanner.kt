package com.wuming.tv.desktop.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.tv.core.source.GitHubMirror
import com.wuming.tv.core.source.GitHubProxyProbe
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy

/**
 * Desktop port of the Android `GitHubProxySpeedTest`: probe each GitHub proxy mirror by issuing a small
 * ranged GET against a reference raw.githubusercontent.com file and pick the fastest mirror that returns a
 * usable payload. Useful on networks where raw.githubusercontent.com is blocked or throttled. The probe
 * algorithm and outcome classification are shared with the Android client via [GitHubProxyProbe].
 */
object DesktopProxyScanner {

    private const val RESOURCE_NAME = "/github_proxies.json"

    data class Candidate(val name: String, val host: String, val note: String) {
        val isDirect: Boolean get() = host.isEmpty()
    }

    data class Result(
        val candidate: Candidate,
        val elapsedMs: Int,
        val status: Int,
        val contentBytes: Int,
        val error: String?,
    ) {
        val success: Boolean get() = elapsedMs >= 0 && contentBytes >= GitHubProxyProbe.PROBE_MIN_CONTENT_BYTES
        /** Value to pass as `tvbox.ghproxy`: the mirror URL, or `off` for direct access. */
        val recommendedValue: String get() = if (candidate.isDirect) "off" else "https://${candidate.host}"
    }

    data class Report(val testUrl: String, val results: List<Result>, val best: Result?)

    fun scan(): Report {
        val (testUrl, candidates) = loadCandidates()
        return scan(testUrl, candidates)
    }

    fun scan(testUrl: String, candidates: List<Candidate>): Report {
        val client = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofMillis(GitHubProxyProbe.PROBE_CONNECT_TIMEOUT_MS.toLong()))
            .readTimeout(java.time.Duration.ofMillis(GitHubProxyProbe.PROBE_READ_TIMEOUT_MS.toLong()))
            .followRedirects(true)
            .followSslRedirects(true)
            // Bypass any JVM/system proxy so the mirror host itself is measured directly.
            .proxy(Proxy.NO_PROXY)
            .build()
        val transport = GitHubProxyProbe.Transport { url, withRange -> okHttpProbe(client, url, withRange) }
        val count = candidates.size
        val out = arrayOfNulls<Result>(count)
        val latch = java.util.concurrent.CountDownLatch(count)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(count, 5).coerceAtLeast(1))
        try {
            candidates.forEachIndexed { index, candidate ->
                pool.submit {
                    try {
                        out[index] = testOne(transport, candidate, testUrl)
                    } catch (e: Throwable) {
                        out[index] = Result(candidate, -1, 0, 0, e.message ?: e.javaClass.simpleName)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
        } finally {
            pool.shutdownNow()
        }
        val results = out.filterNotNull()
        val best = GitHubProxyProbe.best(results.map { it.toCore() })?.let { coreBest ->
            results.firstOrNull { it.elapsedMs == coreBest.elapsedMs && it.status == coreBest.status }
        }
        return Report(testUrl, results, best)
    }

    private fun testOne(transport: GitHubProxyProbe.Transport, candidate: Candidate, testUrl: String): Result {
        val target = buildProxiedUrl(candidate, testUrl)
        return GitHubProxyProbe.probeOne(transport, target).let { core ->
            Result(candidate, core.elapsedMs, core.status, core.contentBytes, core.error)
        }
    }

    private fun okHttpProbe(client: OkHttpClient, url: String, withRange: Boolean): GitHubProxyProbe.Outcome {
        val request = Request.Builder().url(url).get()
            .header("User-Agent", "TVBox-Desktop/0.1")
        if (withRange) {
            request.header("Range", "bytes=0-${GitHubProxyProbe.PROBE_DOWNLOAD_LIMIT - 1}")
        }
        val response = client.newCall(request.build()).execute()
        try {
            val code = response.code
            var bytes = 0
            if (response.isSuccessful) {
                val stream = response.body.byteStream()
                val buffer = ByteArray(4096)
                while (bytes < GitHubProxyProbe.PROBE_DOWNLOAD_LIMIT) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    bytes += read
                }
            }
            return GitHubProxyProbe.Outcome(code, bytes)
        } finally {
            response.close()
        }
    }

    private fun Result.toCore(): GitHubProxyProbe.Result =
        GitHubProxyProbe.Result(elapsedMs, status, contentBytes, error)

    private fun buildProxiedUrl(candidate: Candidate, testUrl: String): String =
        GitHubMirror.resolve(testUrl, candidate.host)

    private fun loadCandidates(): Pair<String, List<Candidate>> =
        runCatching { parseResource() }.getOrNull()
            ?.takeIf { (testUrl, candidates) -> candidates.isNotEmpty() && testUrl.isNotBlank() }
            ?: (BUILTIN_TEST_URL to BUILTIN)

    private fun parseResource(): Pair<String, List<Candidate>> {
        val stream = DesktopProxyScanner::class.java.getResourceAsStream(RESOURCE_NAME)
            ?: throw IllegalStateException("missing $RESOURCE_NAME")
        val json = stream.bufferedReader().use { JsonParser.parseReader(it) }.asJsonObject
        val testUrl = json.optString("test_url") ?: throw IllegalStateException("missing test_url")
        val proxies = json.getAsJsonArray("proxies").map { element ->
            val obj = element.asJsonObject
            Candidate(
                name = obj.optString("name") ?: obj.optString("host") ?: "proxy",
                host = obj.optString("host")?.trim().orEmpty(),
                note = obj.optString("note").orEmpty(),
            )
        }
        return testUrl to proxies
    }

    private fun JsonObject.optString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private val BUILTIN_TEST_URL =
        "https://raw.githubusercontent.com/amule-project/amule/refs/heads/master/README.md"

    private val NOTES = mapOf(
        "" to "Direct GitHub access",
        "ghproxy.net" to "Fast, good for small files",
        "gh-proxy.com" to "Reliable",
        "v6.gh-proxy.org" to "IPv6 optimized",
        "gh.idayer.com" to "Backup option",
        "gh-proxy.org" to "General purpose",
        "v4.gh-proxy.org" to "IPv4 optimized",
        "cdn.gh-proxy.org" to "CDN accelerated",
        "axisnow.gh-proxy.org" to "AxisNow mirror",
    )

    private val BUILTIN: List<Candidate> = GitHubMirror.HOSTS.map { host ->
        Candidate(
            name = if (host.isEmpty()) "None" else host,
            host = host,
            note = NOTES[host].orEmpty(),
        )
    }
}
