package com.wuming.tv.desktop.net

import com.wuming.tv.core.base.HttpTokens
import com.wuming.tv.core.runtime.js.EnvironmentProxySelector
import java.net.URI
import java.net.IDN
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger
import okhttp3.OkHttpClient
import okhttp3.Request

internal class DesktopHttpStatusException(
    val statusCode: Int,
    val responsePreview: String,
) : IllegalArgumentException(buildString {
    append("Resource server returned HTTP $statusCode.")
    if (responsePreview.isNotEmpty()) append(" Response: $responsePreview")
})

data class DesktopResourceResponse(
    val bytes: ByteArray,
    val status: Int = 200,
    val finalLocation: String,
    val headers: Map<String, String> = emptyMap(),
)

class DesktopResourceLoader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        // Use the same HTTP stack as Android and the isolated CatVod host. Some signed media
        // CDNs reject JDK HttpClient's HTTP/2/TLS request even when its URL and headers are valid.
        .connectTimeout(Duration.ofSeconds(15))
        .callTimeout(Duration.ofSeconds(30))
        .proxySelector(EnvironmentProxySelector())
        .build(),
    private val maximumBytes: Int = 16 * 1024 * 1024,
    private val logRequests: Boolean = true,
) {
    fun forThumbnails(): DesktopResourceLoader {
        val budget = com.wuming.tv.core.catalog.ThumbnailRequestBudget
        return DesktopResourceLoader(
            client = client.newBuilder()
                .connectTimeout(Duration.ofMillis(budget.CONNECT_TIMEOUT_MS))
                .readTimeout(Duration.ofMillis(budget.READ_TIMEOUT_MS))
                .callTimeout(Duration.ofMillis(budget.CALL_TIMEOUT_MS))
                .retryOnConnectionFailure(false)
                .build(),
            maximumBytes = maximumBytes,
            logRequests = logRequests,
        )
    }
    private val logger = Logger.getLogger("tvbox.http")

    fun load(value: String, headers: Map<String, String> = emptyMap()): ByteArray =
        loadResponse(value, headers).also { require(it.status != 304) { "Unexpected HTTP 304 without cached body." } }.bytes

    fun loadResponse(value: String, headers: Map<String, String> = emptyMap()): DesktopResourceResponse {
        val input = value.trim()
        require(input.isNotEmpty()) { "Enter a URL or local file." }
        require(input.none(Character::isISOControl)) { "Resource location contains control characters." }
        val uri = runCatching { URI(input) }.getOrElse { throw IllegalArgumentException("Invalid resource location.") }
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> download(normalizeHttpUri(input, uri), headers)
            "assets" -> DesktopResourceResponse(readAsset(uri), finalLocation = input)
            "file" -> DesktopResourceResponse(read(uriToPath(uri)), finalLocation = input)
            null -> DesktopResourceResponse(read(Path.of(input).toAbsolutePath().normalize()), finalLocation = input)
            else -> throw IllegalArgumentException("Only HTTP(S), bundled assets and local files are supported.")
        }
    }

    private fun readAsset(uri: URI): ByteArray {
        val name = listOfNotNull(uri.authority?.takeIf(String::isNotBlank), uri.path.trimStart('/'))
            .filter(String::isNotBlank)
            .joinToString("/")
        require(name.isNotEmpty() && name.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Invalid bundled asset location."
        }
        val stream = javaClass.getResourceAsStream("/$name")
            ?: throw IllegalArgumentException("Bundled asset does not exist: $name")
        return stream.use { limited(it.readNBytes(maximumBytes + 1)) }
    }

    private fun download(uri: URI, headers: Map<String, String>): DesktopResourceResponse {
        require(!uri.host.isNullOrBlank()) { "Resource URL has no host." }
        require(uri.userInfo == null) { "Resource URL must not contain credentials." }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Resource request cancelled")
        val request = Request.Builder().url(uri.toURL())
        val acceptedHeaders = safeHeaders(headers)
        acceptedHeaders.forEach { (name, value) -> request.header(name, value) }
        if (acceptedHeaders.keys.none { it.equals("User-Agent", true) }) {
            request.header("User-Agent", "TVBox-Desktop/0.1")
        }
        val built = request.get().build()
        val started = System.nanoTime()
        if (logRequests) logger.info("GET start target=${safe(uri)} limit=$maximumBytes")
        return try {
            client.newCall(built).execute().use { response ->
                val body = response.body
                val responseHeaders = response.headers.names().associate { it.lowercase() to response.headers.values(it).joinToString(", ") }
                if (response.code == 304) return DesktopResourceResponse(
                    byteArrayOf(), 304, response.request.url.toString(), responseHeaders,
                )
                if (!response.isSuccessful) {
                    val preview = body.byteStream().readNBytes(ERROR_PREVIEW_BYTES)
                        .toString(Charsets.UTF_8).sanitizedPreview()
                    throw DesktopHttpStatusException(response.code, preview)
                }
                require(body.contentLength() <= maximumBytes || body.contentLength() < 0) {
                    "Resource exceeds the ${maximumBytes / 1024 / 1024} MiB limit."
                }
                val bytes = limited(body.byteStream().readNBytes(maximumBytes + 1)).also { bytes ->
                    if (logRequests) {
                        logger.info(
                            "GET done target=${safe(uri)} status=${response.code} bytes=${bytes.size} " +
                                "elapsedMs=${(System.nanoTime() - started) / 1_000_000}",
                        )
                    }
                }
                DesktopResourceResponse(bytes, response.code, response.request.url.toString(), responseHeaders)
            }
        } catch (failure: Throwable) {
            val cancelled = failure is InterruptedException || Thread.currentThread().isInterrupted
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (logRequests && !cancelled) {
                val message =
                    "GET failed target=${safe(uri)} elapsedMs=${(System.nanoTime() - started) / 1_000_000} error=${failure.message}"
                if (failure is DesktopHttpStatusException) logger.warning(message)
                else logger.log(Level.WARNING, message, failure)
            }
            throw failure
        }
    }

    private fun safeHeaders(values: Map<String, String>): Map<String, String> = values.filter { (name, value) ->
        HttpTokens.isToken(name) && name.lowercase() !in FORBIDDEN_REQUEST_HEADERS &&
            HttpTokens.isHeaderValue(value)
    }

    private fun safe(uri: URI): String = "${uri.scheme}://${uri.host}${uri.path.orEmpty()}"

    private fun String.sanitizedPreview(): String = replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), "")
        .replace(Regex("\\s+"), " ").trim().take(ERROR_PREVIEW_CHARACTERS)

    private fun read(path: Path): ByteArray {
        require(Files.isRegularFile(path)) { "Resource file does not exist: $path" }
        require(Files.size(path) <= maximumBytes) { "Resource exceeds the ${maximumBytes / 1024 / 1024} MiB limit." }
        return limited(Files.readAllBytes(path))
    }

    private fun limited(bytes: ByteArray): ByteArray {
        require(bytes.size <= maximumBytes) { "Resource exceeds the ${maximumBytes / 1024 / 1024} MiB limit." }
        return bytes
    }

    private fun uriToPath(uri: URI): Path = runCatching { Path.of(uri) }
        .getOrElse { throw IllegalArgumentException("Invalid local resource URL.") }

    internal fun normalizeHttpUri(input: String, parsed: URI = URI(input)): URI {
        if (!parsed.host.isNullOrBlank()) return parsed
        val url = runCatching { URL(input) }
            .getOrElse { throw IllegalArgumentException("Resource URL has no host.") }
        val host = runCatching { IDN.toASCII(url.host) }
            .getOrElse { throw IllegalArgumentException("Resource URL has an invalid international host name.") }
        require(host.isNotBlank()) { "Resource URL has no host." }
        return URI(parsed.scheme, parsed.userInfo, host, parsed.port, parsed.path, parsed.query, parsed.fragment)
    }

    private companion object {
        const val ERROR_PREVIEW_BYTES = 1_024
        const val ERROR_PREVIEW_CHARACTERS = 240
        val FORBIDDEN_REQUEST_HEADERS = setOf(
            "connection", "content-length", "expect", "host", "proxy-authorization", "upgrade",
        )
    }
}
