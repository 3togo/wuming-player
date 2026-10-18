package com.wuming.android.tv.utils

import com.github.catvod.net.OkHttp
import com.wuming.tv.core.live.LiveLineMeasurement
import com.wuming.tv.core.live.LiveLineProbeMemory
import com.wuming.tv.core.live.LiveLineSelectionPolicy
import com.wuming.tv.core.live.LiveStreamProbePolicy
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Measures the duplicate lines of a live channel so playback can start on one that answers.
 *
 * Live playlists list the same channel on several URLs and most of them are dead; which line to
 * pick is a rule in `live-core` so the desktop client can share it. This object only performs the
 * requests and remembers their outcome for the lifetime of the process.
 */
object LiveLineProber {

    /** Long enough for a slow CDN, short enough that zapping is never held up by it. */
    private const val TIMEOUT_MILLIS = 5000L

    /** A few lines at a time: a channel's list is short, and zapping replaces the work constantly. */
    private const val MAX_LINES = 8

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val counter = AtomicInteger(0)

    private val executor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "TV-live-line-" + counter.incrementAndGet()).apply { isDaemon = true }
    }

    /** Remembered outcomes, shared with the desktop prober via `live-core`. */
    private val memory = LiveLineProbeMemory()

    /** The line to play, using whatever has been measured so far. */
    @JvmStatic
    fun select(lines: List<String>, requested: Int): Int {
        val measurements = memory.measuredLines()
        return LiveLineSelectionPolicy.select(lines, requested, measurements)
    }

    /**
     * Measures the channel's lines that are not already known to answer. Returns at once: a
     * probe must never sit between the key press and the player.
     */
    @JvmStatic
    fun probe(lines: List<String>, headers: Map<String, String>) {
        if (lines.size < 2) return // A lone line has nothing to be ranked against.
        val pending = lines.distinct().filter { memory.shouldReprobe(it) }.take(MAX_LINES)
        val probeHeaders = headers.toMap()
        pending.forEach { url ->
            if (inFlight.add(url)) {
                try {
                    executor.submit {
                        try {
                            measure(url, probeHeaders)
                        } finally {
                            inFlight.remove(url)
                        }
                    }
                } catch (e: RuntimeException) {
                    inFlight.remove(url)
                    throw e
                }
            }
        }
    }

    private fun measure(url: String, headers: Map<String, String>) {
        val start = System.currentTimeMillis()
        val measurement = try {
            val builder = Request.Builder().url(url.substringBefore('$')).tag(LiveLineProber::class.java.name)
            headers.forEach { (name, value) -> builder.header(name, value) }
            OkHttp.client(TIMEOUT_MILLIS).newCall(builder.build()).execute().use { response ->
                val reachable = LiveStreamProbePolicy.hasPayload(response.code, response.body.byteStream())
                LiveLineMeasurement(reachable, if (reachable) System.currentTimeMillis() - start else 0L)
            }
        } catch (e: Exception) {
            if (Thread.currentThread().isInterrupted || "Canceled" == e.message) return
            LiveLineMeasurement(false)
        }
        memory.remember(url, measurement)
    }
}
