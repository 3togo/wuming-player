package com.wuming.tv.core.playback.renderer

/** Receiver declarations are useful hints, never successful playback tests or negative evidence. */
object RendererAdvertisements {
    fun parse(sink: String, actions: Set<String>): Set<RendererCapability> {
        require(sink.length <= 262144) { "Protocol information too large" }
        val result = linkedSetOf<RendererCapability>()
        for (entry in sink.split(',')) {
            val fields = entry.trim().split(':', limit = 4)
            if (fields.size != 4 || !fields[0].equals("http-get", true)) continue
            val mime = fields[2].substringBefore(';').trim().lowercase()
            when (mime) {
                "video/mp4", "audio/mp4" -> result.add(RendererCapability.MP4)
                "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> result.add(RendererCapability.HLS)
                "application/dash+xml" -> result.add(RendererCapability.DASH)
                "video/h264" -> result.add(RendererCapability.H264)
                "video/h265", "video/hevc" -> result.add(RendererCapability.HEVC)
                "audio/aac", "audio/aacp" -> result.add(RendererCapability.AAC)
            }
            val profile = fields[3].split(';').firstOrNull { it.startsWith("DLNA.ORG_PN=", true) }
                ?.substringAfter('=')?.uppercase().orEmpty()
            if (profile.startsWith("AVC_")) result.add(RendererCapability.H264)
            if (profile.startsWith("HEVC_")) result.add(RendererCapability.HEVC)
            if (profile.startsWith("AAC_") || profile.split('_').contains("AAC")) result.add(RendererCapability.AAC)
        }
        if ("Seek" in actions) result.add(RendererCapability.SEEK)
        if ("Stop" in actions) result.add(RendererCapability.STOP)
        // MIME wildcards, missing formats, and AVTransport Play do not prove codec/HTTP/recast support.
        return result
    }
}
