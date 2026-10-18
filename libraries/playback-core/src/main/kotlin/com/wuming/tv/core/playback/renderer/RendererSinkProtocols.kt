package com.wuming.tv.core.playback.renderer

/** Container/manifest declarations only; they do not claim codec profiles or measured playback. */
object RendererSinkProtocols {
    @JvmStatic
    fun forFormats(formats: Set<RendererCapability>): String {
        val mimeTypes = linkedSetOf<String>()
        if (RendererCapability.MP4 in formats) mimeTypes.add("video/mp4")
        if (RendererCapability.HLS in formats) {
            mimeTypes.add("application/vnd.apple.mpegurl")
            mimeTypes.add("application/x-mpegURL")
        }
        if (RendererCapability.DASH in formats) mimeTypes.add("application/dash+xml")
        return mimeTypes.joinToString(",") { "http-get:*:$it:*" }
    }
}
