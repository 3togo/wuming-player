package com.wuming.tv.desktop.live

import com.wuming.tv.core.live.LivePlaylist
import com.wuming.tv.core.live.LivePlaylistParser
import com.wuming.tv.core.live.LivePlaylistEncoding
import com.wuming.tv.desktop.net.DesktopResourceLoader
import com.wuming.tv.desktop.cache.ResourceDiskCache
import java.net.URI
import java.util.logging.Logger

class DesktopLivePlaylistLoader internal constructor(
    private val loadResource: ((String) -> ByteArray)?,
) {
    private val logger = Logger.getLogger("tvbox.live.playlist")

    constructor() : this(null)

    @Volatile var cacheStatus: String = "network"
        private set

    constructor(maximumBytes: Int) : this(DesktopResourceLoader(maximumBytes = maximumBytes)::load)

    fun load(value: String, forceRefresh: Boolean = false): LivePlaylist {
        val candidates = livePlaylistCandidates(value)
        var lastFailure: Throwable? = null
        candidates.forEachIndexed { index, candidate ->
            try {
                val bytes = loadResource?.invoke(candidate) ?: ResourceDiskCache.playlists.load(
                    candidate, forceRefresh = forceRefresh,
                ) { content ->
                    LivePlaylistParser.parse(LivePlaylistEncoding.decode(content))
                    true
                }.also { cacheStatus = it.origin.name.lowercase() }.bytes
                val text = LivePlaylistEncoding.decode(bytes)
                return LivePlaylistParser.parse(text).also {
                    if (candidates.size > 1) {
                        logger.info("playlist location selected candidate=${index + 1}/${candidates.size} host=${safeHost(candidate)}")
                    }
                }
            } catch (failure: Throwable) {
                lastFailure = failure
                if (index + 1 < candidates.size) {
                    logger.info(
                        "playlist location failed candidate=${index + 1}/${candidates.size} " +
                            "host=${safeHost(candidate)} error=${failure.javaClass.simpleName}; trying fallback",
                    )
                }
            }
        }
        throw lastFailure ?: IllegalArgumentException("Playlist could not be loaded")
    }

    private fun safeHost(value: String): String = runCatching { URI(value).host }.getOrNull() ?: "local"
}

internal fun livePlaylistCandidates(value: String): List<String> =
    com.wuming.tv.desktop.net.embeddedHttpCandidates(value)
