package com.wuming.tv.core.playback.renderer

import java.net.URI
import java.util.UUID
import java.util.function.Consumer
import java.util.function.LongSupplier

/** Passive evidence only: acknowledgements and network/control errors never prove codec support. */
class RendererPlaybackObservation @JvmOverloads constructor(
    private val capability: RendererCapability?, private val version: String,
    private val sink: Consumer<RendererEvidence>,
    private val clock: LongSupplier = LongSupplier { System.currentTimeMillis() },
    private val sessionId: String = UUID.randomUUID().toString()
) {
    private var lastPosition = -1L
    private var advanced = 0L
    private var samples = 0
    private var recorded = false
    @Synchronized fun position(position: Long, playing: Boolean) {
        if (!playing) { seeking(); return }
        if (lastPosition >= 0) {
            val delta = position - lastPosition
            if (delta in 1..6000) { advanced += delta; samples++ }
            else { advanced = 0; samples = 0 }
        }
        lastPosition = position
        if (!recorded && advanced >= 6000 && samples >= 3 && capability != null) {
            recorded = true
            record(capability, EvidenceResult.PROGRESS, "renderer_position_advanced", -1, -1)
        }
    }
    @Synchronized fun seeking() { lastPosition = -1; advanced = 0; samples = 0 }
    fun failure(action: String, http: Int, upnp: Int) {
        val c = when(action) { "Stop" -> RendererCapability.STOP; "Seek" -> RendererCapability.SEEK; else -> capability } ?: return
        val safeAction = action.replace(Regex("[^A-Za-z0-9_]"), "_").take(40)
        record(c, EvidenceResult.INCONCLUSIVE, "action_${safeAction}_failed", http, upnp)
    }
    private fun record(capability: RendererCapability, result: EvidenceResult, reason: String, http: Int, upnp: Int) {
        try { sink.accept(RendererEvidence(capability, result, sessionId, clock.asLong, version.take(128), reason, http, upnp)) }
        catch (_: Exception) { /* Recording must not break playback. Storage ports report failures. */ }
    }
    companion object {
        @JvmStatic fun format(url: String): RendererCapability? {
            val uri = try { URI(url) } catch (_: Exception) { return null }
            val path = uri.path?.lowercase(java.util.Locale.ROOT) ?: ""
            return when {
                path.endsWith(".m3u8") -> RendererCapability.HLS
                path.endsWith(".mpd") || uri.rawQuery?.split('&')?.contains("type=mpd") == true -> RendererCapability.DASH
                path.endsWith(".mp4") -> RendererCapability.MP4
                else -> null
            }
        }
    }
}
