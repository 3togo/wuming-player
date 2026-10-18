package com.wuming.tv.desktop.live

import com.wuming.tv.core.base.HttpTokens
import com.wuming.tv.core.runtime.js.EnvironmentProxySelector
import com.wuming.tv.core.live.LiveLineMeasurement
import com.wuming.tv.core.live.LiveLineProbeMemory
import com.wuming.tv.core.live.LiveLineSelectionPolicy
import com.wuming.tv.core.live.LivePlaylist
import com.wuming.tv.core.live.LiveStream
import com.wuming.tv.core.live.LiveStreamProbePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Logger

/**
 * Reachability probe for live streams.
 *
 * Duplicate lines for the same channel are the norm in live playlists and most of them are dead, so
 * the choice of which line to attempt is `live-core`'s [LiveLineSelectionPolicy] -- the same rule the
 * app uses. This class only performs the HTTP attempts and remembers how each line answered.
 */
class DesktopLiveStreamProbe(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .proxy(EnvironmentProxySelector())
        .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(8),
    private val candidateLimit: Int = 3,
    private val selectedChannelLimit: Int = 6,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = Logger.getLogger("tvbox.live.probe")

    /** Remembered outcomes, shared with the app prober via `live-core`. */
    private val memory = LiveLineProbeMemory(maxRemembered = MAX_REMEMBERED, clock = clock)

    /** Smoke check on a freshly configured list: is any of its first few streams alive at all? */
    fun firstReachableStream(playlist: LivePlaylist): LiveStream? {
        val candidates = playlist.groups.asSequence()
            .flatMap { it.channels.asSequence() }
            .flatMap { it.streams.asSequence() }
            .take(candidateLimit)
            .toList()
        return firstReachable(candidates, preferredIndex = 0, limit = candidateLimit, scope = "playlist")
    }

    fun hasReachableStream(playlist: LivePlaylist): Boolean = firstReachableStream(playlist) != null

    /** The line to play for a channel, honouring [preferredIndex] unless a probe argues against it. */
    fun selectStream(streams: List<LiveStream>, preferredIndex: Int = 0): LiveStream? =
        firstReachable(streams, preferredIndex, selectedChannelLimit, "channel")

    private fun firstReachable(streams: List<LiveStream>, preferredIndex: Int, limit: Int, scope: String): LiveStream? {
        val candidates = probeOrder(streams, preferredIndex, limit)
        return candidates.firstOrNull(::reachable).also { stream ->
            logger.info(
                "$scope probe lines=${streams.size} candidates=${candidates.size} " +
                    "skipped=${streams.size - candidates.size} reachable=${stream != null}",
            )
        }
    }

    /**
     * The policy's line first, then the playlist's own order.
     *
     * Lines already known dead are dropped instead of re-attempted, except the policy's choice: a
     * route the user selected by hand still gets one honest attempt, and a channel whose every line
     * is known dead has to pay for that one.
     */
    private fun probeOrder(streams: List<LiveStream>, preferredIndex: Int, limit: Int): List<LiveStream> {
        if (streams.isEmpty()) return emptyList()
        val measurements = measuredLines()
        val chosen = LiveLineSelectionPolicy.select(streams.map { it.url }, preferredIndex, measurements)
        val order = (listOf(chosen) + (0..streams.lastIndex).filter { it != chosen }).take(limit)
        val unproven = order.drop(1).filterNot { measurements[streams[it].url]?.reachable == false }
        return (listOf(streams[order.first()]) + unproven.map { streams[it] }).distinctBy { it.url }
    }

    /** Lines whose answer still stands; a dead line past the reprobe window reads as unknown. */
    private fun measuredLines(): Map<String, LiveLineMeasurement> = memory.measuredLines()

    internal fun reachable(stream: LiveStream): Boolean = measure(stream).reachable

    private fun measure(stream: LiveStream): LiveLineMeasurement =
        attempt(stream).also { measurement -> memory.remember(stream.url, measurement) }

    private fun attempt(stream: LiveStream): LiveLineMeasurement {
        val uri = runCatching { URI(stream.url) }.getOrNull() ?: return LiveLineMeasurement(false)
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return LiveLineMeasurement(false)
        val request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Range", "bytes=0-4095")
        safeHeaders(stream.headers).forEach(request::header)
        if (stream.headers.keys.none { it.equals("User-Agent", true) }) {
            request.header("User-Agent", "TVBox-Desktop/0.1")
        }
        val startedAt = clock()
        return runCatching {
            val response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                val accepted = LiveStreamProbePolicy.hasPayload(response.statusCode(), body)
                val latencyMillis = clock() - startedAt
                logger.info(
                    "stream probe target=${uri.scheme}://${uri.host}${uri.path.orEmpty()} " +
                        "status=${response.statusCode()} latency=$latencyMillis",
                )
                LiveLineMeasurement(accepted, if (accepted) latencyMillis else 0L)
            }
        }.onFailure {
            logger.info("stream probe target=${uri.scheme}://${uri.host}${uri.path.orEmpty()} failed=${it.javaClass.simpleName}")
        }.getOrDefault(LiveLineMeasurement(false))
    }

    private fun safeHeaders(headers: Map<String, String>): Map<String, String> = headers.filter { (name, value) ->
        HttpTokens.isToken(name) && name.lowercase() !in FORBIDDEN_HEADERS &&
            value.isNotBlank() && HttpTokens.isHeaderValue(value)
    }

    private companion object {
        val FORBIDDEN_HEADERS = setOf("connection", "content-length", "host", "range")

        /** Zapping revisits the same channels; enough room for a session's worth of lines. */
        const val MAX_REMEMBERED = 200
    }
}
