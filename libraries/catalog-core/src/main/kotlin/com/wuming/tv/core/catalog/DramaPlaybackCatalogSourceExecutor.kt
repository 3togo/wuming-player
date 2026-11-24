package com.wuming.tv.core.catalog

import com.wuming.tv.core.playback.DramaVideoResolver

/** Keeps the configured catalogue, while resolving its supported video protocol independently. */
class DramaPlaybackCatalogSourceExecutor(
    private val delegate: CatalogSourceExecutor,
    private val api: String,
    private val resolver: DramaVideoResolver = DramaVideoResolver(),
) : CatalogSourceExecutor by delegate, AutoCloseable {
    override fun play(flag: String, id: String): CatalogPlayback =
        if (DramaVideoResolver.supports(api, id)) CatalogPlayback(true, resolver.resolve(id), "video/mp4")
        else delegate.play(flag, id)
    override fun playbackPreparationSafety(flag: String, id: String): PlaybackPreparationSafety =
        if (DramaVideoResolver.supports(api, id)) PlaybackPreparationSafety.UNKNOWN
        else delegate.playbackPreparationSafety(flag, id)
    override fun prewarm(flag: String, id: String): CatalogPlayback? =
        if (DramaVideoResolver.supports(api, id)) null else delegate.prewarm(flag, id)
    override fun close() { (delegate as? AutoCloseable)?.close() }
}
