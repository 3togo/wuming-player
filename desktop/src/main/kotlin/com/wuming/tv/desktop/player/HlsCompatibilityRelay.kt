package com.wuming.tv.desktop.player

import com.wuming.tv.core.playback.HlsAdFilterEngine
import com.wuming.tv.core.playback.HlsHttpProbeTransport
import com.wuming.tv.core.playback.HlsSegmentProbeSession
import com.wuming.tv.core.playback.HlsAdRule
import com.wuming.tv.core.playback.HlsHeaderRelayPolicy
import com.wuming.tv.core.playback.HlsManifestDetector
import com.wuming.tv.desktop.net.DesktopHttpStatusException
import com.wuming.tv.desktop.net.DesktopResourceLoader
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

internal data class PreparedPlaybackTarget(
    val location: String,
    val resource: AutoCloseable? = null,
)

/** An expected upstream playback rejection which should be shown without an application stack trace. */
internal class PlaybackPreparationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal fun interface PlaybackTargetPreparer {
    fun prepare(location: String, headers: Map<String, String>): PreparedPlaybackTarget
}

/** Adapts HLS providers which conceal MPEG-TS segments behind an image wrapper. */
internal class HlsCompatibilityPreparer(
    private val fetch: (String, Map<String, String>) -> ByteArray =
        DesktopResourceLoader(
            client = DesktopPlaybackHttpClient.client,
            maximumBytes = MAX_RESOURCE_BYTES,
        )::load,
    private val relayFetch: (String, Map<String, String>) -> ByteArray =
        DesktopResourceLoader(
            client = DesktopPlaybackHttpClient.client,
            maximumBytes = MAX_RESOURCE_BYTES,
            logRequests = false,
        )::load,
    private val progressivePreparer: PlaybackTargetPreparer = ProgressivePlaybackPreparer(),
    private val relayWorkers: Int = RelayExecutors.hlsWorkers(),
    private val probeTransportFactory: () -> HlsHttpProbeTransport = {
        HlsHttpProbeTransport(DesktopPlaybackHttpClient.client, "TVBox-Desktop/0.1")
    },
) : PlaybackTargetPreparer {
    private val logger = Logger.getLogger("tvbox.player.hls")
    private val adRules = AtomicReference<List<HlsAdRule>>(emptyList())

    fun updateAdRules(value: List<HlsAdRule>) {
        adRules.set(value.toList())
        logger.info("HLS ad rules updated count=${value.size}")
    }

    override fun prepare(location: String, headers: Map<String, String>): PreparedPlaybackTarget {
        val playlist = httpUri(location)?.takeIf { it.path.orEmpty().lowercase().endsWith(".m3u8") }
            ?: return progressivePreparer.prepare(location, headers)
        val preserveAllHeaders = requiresHeaderRelay(headers)
        val manifest = runCatching { fetch(location, headers) }.getOrElse {
            playbackHttpFailure(it)?.let { failure -> throw failure }
            logger.info(
                "HLS manifest recovery probe host=${playlist.host} " +
                    "protectedHeaders=$preserveAllHeaders error=${rootType(it)}",
            )
            recoverResource(location, headers, "HLS manifest").getOrElse { recoveryFailure ->
                playbackHttpFailure(recoveryFailure)?.let { failure -> throw failure }
                throw PlaybackPreparationException(
                    "Could not verify the HLS manifest because the media host is unreachable. " +
                        "Retry playback or switch network route.",
                    recoveryFailure,
                )
            }
        }
        if (!HlsManifestDetector.isManifest(manifest)) {
            if (HlsManifestDetector.looksLikeErrorDocument(manifest)) {
                throw PlaybackPreparationException(
                    "Media URL returned an error document instead of an HLS playlist. " +
                        "Reload details for a fresh link or choose another playback source.",
                )
            }
            return PreparedPlaybackTarget(location)
        }
        val initial = linkedMapOf(playlist.toASCIIString() to manifest)
        val proxyMedia = preserveAllHeaders || requiresMediaRelay(playlist, manifest, headers, initial)

        val relay = HlsCompatibilityRelay(
            headers,
            relayFetch,
            initial,
            adRules::get,
            proxyMedia,
            relayWorkers,
            probeTransportFactory,
        )
        logger.info(
            "HLS purification relay enabled manifestHost=${playlist.host} rules=${adRules.get().size} " +
                "wrappedMedia=$proxyMedia",
        )
        return PreparedPlaybackTarget(relay.location(playlist), relay)
    }

    private fun requiresMediaRelay(
        manifestUri: URI,
        manifest: ByteArray,
        headers: Map<String, String>,
        initial: MutableMap<String, ByteArray>,
        depth: Int = 0,
    ): Boolean {
        if (depth >= MAX_MANIFEST_DEPTH) return false
        val media = firstMediaUri(manifestUri, manifest) ?: return false
        val bytes = runCatching { fetch(media.toASCIIString(), headers) }.getOrElse {
            playbackMediaHttpFailure(it)?.let { failure -> throw failure }
            logger.info(
                "HLS media recovery probe host=${media.host} " +
                    "error=${rootType(it)}",
            )
            recoverResource(media.toASCIIString(), headers, "first HLS media segment")
                .getOrElse { recoveryFailure ->
                    playbackMediaHttpFailure(recoveryFailure)?.let { failure -> throw failure }
                    throw PlaybackPreparationException(
                        "Could not verify the first HLS media segment because its CDN is unreachable. " +
                            "Retry playback or choose another source.",
                        recoveryFailure,
                    )
                }
        }
        initial[media.toASCIIString()] = bytes
        return if (HlsManifestDetector.isManifest(bytes)) {
            requiresMediaRelay(media, bytes, headers, initial, depth + 1)
        } else {
            HlsManifestDetector.transportStreamOffset(bytes) > 0
        }
    }

    private fun firstMediaUri(base: URI, manifest: ByteArray): URI? =
        manifest.toString(StandardCharsets.UTF_8).lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
            ?.let { runCatching { requireHttp(base.resolve(it)) }.getOrNull() }

    private fun httpUri(value: String): URI? = runCatching { requireHttp(URI(value)) }.getOrNull()

    /** VLC exposes User-Agent and Referer switches, but not arbitrary HLS request headers. */
    private fun requiresHeaderRelay(headers: Map<String, String>): Boolean =
        HlsHeaderRelayPolicy.requiresHeaderRelay(headers)

    private fun rootType(error: Throwable): String {
        var current = error
        while (current.cause != null) current = current.cause!!
        return current.javaClass.simpleName
    }

    private fun recoverResource(
        location: String,
        headers: Map<String, String>,
        label: String,
    ): Result<ByteArray> = runCatching {
        var lastFailure: Throwable? = null
        repeat(RECOVERY_FETCH_ATTEMPTS) { index ->
            try {
                return@runCatching relayFetch(location, headers)
            } catch (failure: Throwable) {
                lastFailure = failure
                val attempt = index + 1
                if (attempt >= RECOVERY_FETCH_ATTEMPTS || !failure.isRetryableTransportFailure()) throw failure
                logger.info(
                    "$label retry attempt=$attempt/$RECOVERY_FETCH_ATTEMPTS error=${rootType(failure)}",
                )
                Thread.sleep(RECOVERY_RETRY_DELAY_MILLIS * attempt)
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun playbackHttpFailure(error: Throwable): PlaybackPreparationException? {
        val response = error.httpStatusFailure()
        if (response == null) {
            val sourceIpDenied = generateSequence(error) { it.cause }
                .mapNotNull(Throwable::message)
                .any { message -> message.contains("source ip is forbidden", ignoreCase = true) }
            return if (sourceIpDenied) PlaybackPreparationException(
                "Media CDN rejected the current network/proxy IP. Switch proxy route and retry playback.",
                error,
            ) else null
        }
        val deniedIp = response.responsePreview.contains("source ip is forbidden", ignoreCase = true)
        val guidance = when {
            deniedIp -> "Switch proxy route and retry playback."
            response.statusCode == 401 || response.statusCode == 403 ->
                "Reload details for a fresh signed link; if it still fails, choose another source or network route."
            response.statusCode == 404 || response.statusCode == 410 ->
                "The provider link is no longer available; reload details or choose another source."
            else -> "Retry later or choose another playback source."
        }
        return PlaybackPreparationException(
            "Media server rejected the HLS manifest (HTTP ${response.statusCode}). $guidance",
            error,
        )
    }

    private fun playbackMediaHttpFailure(error: Throwable): PlaybackPreparationException? {
        val response = error.httpStatusFailure() ?: return null
        val guidance = when (response.statusCode) {
            401, 403 -> "Reload details for a fresh link or choose another source."
            404, 410 -> "The provider returned a stale or removed segment; reload details or choose another source."
            else -> "Retry later or choose another playback source."
        }
        return PlaybackPreparationException(
            "The first HLS media segment is unavailable (HTTP ${response.statusCode}). $guidance",
            error,
        )
    }

    private companion object {
        const val MAX_RESOURCE_BYTES = 32 * 1024 * 1024
        const val MAX_MANIFEST_DEPTH = 4
        const val RECOVERY_FETCH_ATTEMPTS = 3
        const val RECOVERY_RETRY_DELAY_MILLIS = 200L
    }
}

private class HlsCompatibilityRelay(
    private val headers: Map<String, String>,
    private val fetch: (String, Map<String, String>) -> ByteArray,
    initial: Map<String, ByteArray>,
    private val rules: () -> List<HlsAdRule>,
    private val proxyMedia: Boolean,
    workers: Int,
    private val probeTransportFactory: () -> HlsHttpProbeTransport,
) : AutoCloseable {
    private val logger = Logger.getLogger("tvbox.player.hls")
    private val cached = ConcurrentHashMap(initial)
    private val activeProbes = ConcurrentHashMap.newKeySet<HlsHttpProbeTransport>()
    private val secret = UUID.randomUUID().toString().replace("-", "")
    private val executor = RelayExecutors.create("tvbox-hls-relay", workers)
    @Volatile private var closed = false
    private val server = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        0,
    ).apply {
        createContext("/$secret/") { exchange -> handle(exchange) }
        this.executor = this@HlsCompatibilityRelay.executor
        start()
    }

    fun location(upstream: URI): String =
        "http://127.0.0.1:${server.address.port}/$secret/${encode(upstream.toASCIIString())}"

    private fun handle(exchange: HttpExchange) {
        runCatching {
            require(exchange.requestMethod == "GET" || exchange.requestMethod == "HEAD") {
                "Only GET and HEAD are supported"
            }
            val token = exchange.requestURI.path.removePrefix("/$secret/")
            val upstream = requireHttp(URI(decode(token)))
            val original = cached.remove(upstream.toASCIIString())
                ?: fetchWithRetry(upstream)
            val manifest = HlsManifestDetector.isManifest(original)
            val offset = if (manifest) 0 else HlsManifestDetector.transportStreamOffset(original)
            val body = when {
                manifest -> rewrite(upstream, original).toByteArray(StandardCharsets.UTF_8)
                else -> original
            }
            val bodyOffset = if (manifest) 0 else offset.coerceAtLeast(0)
            exchange.responseHeaders.set(
                "Content-Type",
                when {
                    manifest -> "application/vnd.apple.mpegurl"
                    offset >= 0 -> "video/mp2t"
                    else -> "application/octet-stream"
                },
            )
            exchange.responseHeaders.set("Cache-Control", "no-store")
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(200, -1)
            } else {
                exchange.sendResponseHeaders(200, (body.size - bodyOffset).toLong())
                exchange.responseBody.use { it.write(body, bodyOffset, body.size - bodyOffset) }
            }
        }.onFailure { failure ->
            logger.log(Level.WARNING, "HLS relay request failed error=${failure.message}", failure)
            runCatching { exchange.sendResponseHeaders(502, -1) }
        }
        exchange.close()
    }

    private fun rewrite(base: URI, bytes: ByteArray): String {
        val original = bytes.toString(StandardCharsets.UTF_8)
        val transport = probeTransportFactory()
        activeProbes.add(transport)
        val session = HlsSegmentProbeSession(headers, transport, { closed })
        val prepared = try {
            if (closed) throw java.io.InterruptedIOException("Relay closed")
            HlsAdFilterEngine.purify(base.resolve(".").toASCIIString(),
                com.wuming.tv.core.playback.HlsPlaylistLocation.ruleBaseUrl(base.toASCIIString()),
                original, rules(), session) { b, r -> URI.create(b).resolve(r).toASCIIString() }
        } finally {
            activeProbes.remove(transport)
            transport.close()
        }
        if (prepared.legacyFailure != null) logger.warning(
            "HLS configured rules failed host=${base.host} error=${prepared.legacyFailure}")
        val filtered = prepared.content
        val removed = HlsManifestDetector.segmentCount(original) - HlsManifestDetector.segmentCount(filtered)
        logger.info("HLS preparation host=${base.host} removedSegments=$removed " +
            "resolutionBlocks=${prepared.resolution.removedBlocks} timestampBlocks=${prepared.insertion.removedBlocks} " +
            "segmentProbes=${session.requestCount}")

        return com.wuming.tv.core.playback.HlsReferenceRewriter.rewrite(filtered,
            { reference -> requireHttp(base.resolve(reference)).toASCIIString() },
            { resolved, manifest -> relayReference(URI(resolved), manifest) })
    }

    private fun fetchWithRetry(upstream: URI): ByteArray {
        var lastFailure: Throwable? = null
        repeat(RELAY_FETCH_ATTEMPTS) { index ->
            if (closed || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Relay closed")
            try {
                return fetch(upstream.toASCIIString(), headers)
            } catch (failure: Throwable) {
                lastFailure = failure
                val attempt = index + 1
                if (attempt >= RELAY_FETCH_ATTEMPTS || !failure.isRetryableTransportFailure()) throw failure
                logger.info(
                    "HLS relay transport retry host=${upstream.host} attempt=$attempt/$RELAY_FETCH_ATTEMPTS " +
                        "error=${failure.rootType()}",
                )
                Thread.sleep(RELAY_RETRY_DELAY_MILLIS * attempt)
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun relayReference(resolved: URI, manifestReference: Boolean): String {
        return if (proxyMedia || manifestReference || isManifestUri(resolved)) {
            location(resolved)
        } else {
            resolved.toASCIIString()
        }
    }

    override fun close() {
        closed = true
        activeProbes.forEach(HlsHttpProbeTransport::close)
        activeProbes.clear()
        server.stop(0)
        executor.shutdownNow()
        cached.clear()
        logger.info("wrapped HLS relay stopped")
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8,
    )

    private companion object {
        const val RELAY_FETCH_ATTEMPTS = 3
        const val RELAY_RETRY_DELAY_MILLIS = 150L
    }
}

private fun Throwable.isRetryableTransportFailure(): Boolean {
    val causes = generateSequence(this) { it.cause }.toList()
    return causes.none { it is DesktopHttpStatusException } && causes.any { it is IOException }
}

private fun Throwable.httpStatusFailure(): DesktopHttpStatusException? =
    generateSequence(this) { it.cause }.filterIsInstance<DesktopHttpStatusException>().firstOrNull()

private fun Throwable.rootType(): String =
    generateSequence(this) { it.cause }.last().javaClass.simpleName

private fun requireHttp(uri: URI): URI {
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "HLS URI must use HTTP(S)" }
    require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "HLS URI must have a safe host" }
    return uri
}

private fun isManifestUri(uri: URI): Boolean = uri.path.orEmpty().lowercase().endsWith(".m3u8")
