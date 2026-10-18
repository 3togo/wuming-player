package com.wuming.tv.core.playback

import com.wuming.tv.core.base.ByteRanges

/** Transport metadata stays on the sender; renderers receive only an opaque relay URL. */
object CastRelayTransfer {
    @JvmStatic
    fun remoteHttp(url: String): Boolean = try {
        val uri = java.net.URI.create(url)
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: ""
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && host.isNotEmpty() &&
            host != "localhost" && !host.endsWith(".localhost") && !host.startsWith("127.") &&
            host != "::1" && host != "0:0:0:0:0:0:0:1"
    } catch (_: IllegalArgumentException) { false }

    @JvmStatic
    fun responseLength(head: Boolean, bodyLength: Long, declared: String?): Long =
        if (head) declared?.toLongOrNull()?.takeIf { it >= 0 } ?: bodyLength else bodyLength

    data class Slice(val start: Long, val length: Long, val total: Long)

    /** Only replace byte-exact, range-capable media bodies, never manifests or encoded bodies. */
    @JvmStatic
    fun slice(status: Int, length: Long, contentRange: String?, acceptRanges: String?,
              encoding: String?): Slice? {
        if (length <= 0 || (!encoding.isNullOrBlank() && !encoding.equals("identity", true))) return null
        if (status == 200) {
            return if (acceptRanges?.trim().equals("bytes", true)) Slice(0, length, length) else null
        }
        if (status != 206) return null
        val range = ByteRanges.parseResponse(contentRange)?.takeIf { it.total != null } ?: return null
        val total = range.total!!
        if (range.endInclusive >= total || range.endInclusive - range.start != length - 1) return null
        return Slice(range.start, length, total)
    }

    @JvmStatic
    fun plan(original: PlaybackTransportPlan, slice: Slice): PlaybackTransportPlan? {
        if (original.kind != PlaybackTransportPlan.Kind.PROGRESSIVE) return null
        // A renderer's tiny header/tail probe should not start a parallel download window.
        if (slice.length < 1024L * 1024L) return null
        val profile = if (original.parallelRanges()) original.parallelProfile
            else OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP
        if (profile == OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP && slice.length < 10L * 1024L * 1024L) return null
        return PlaybackTransportPlan(original.kind, PlaybackTransportPlan.CachePolicy.NONE,
            original.networkHeaders.filterKeys { !it.startsWith("X-OpenDrive-", true) },
            original.explicitCookie || original.networkHeaders.keys.any { it.equals("Cookie", true) },
            original.explicitCookie || original.networkHeaders.keys.any { it.equals("Cookie", true) },
            slice.total, profile, original.refreshUri, original.expiresAtEpochMs, "")
    }
}
