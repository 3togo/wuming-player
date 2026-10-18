package com.wuming.tv.desktop.live

import com.wuming.tv.core.live.LivePlaylist
import com.wuming.tv.core.live.LivePlaylistReference
import com.wuming.tv.desktop.config.DesktopTvBoxConfiguration
import com.wuming.tv.desktop.config.DesktopTvBoxConfigurationLoader
import com.wuming.tv.desktop.net.resolveResourceLocation

data class DesktopLiveSourceSmokeReport(
    val reference: LivePlaylistReference,
    val playlist: LivePlaylist,
    val skippedSources: List<String> = emptyList(),
)

class DesktopLiveSourceSmoke(
    private val loadConfiguration: (String) -> DesktopTvBoxConfiguration =
        DesktopTvBoxConfigurationLoader()::load,
    private val loadPlaylist: (String) -> LivePlaylist = DesktopLivePlaylistLoader()::load,
    private val hasReachableStream: (LivePlaylist) -> Boolean = DesktopLiveStreamProbe()::hasReachableStream,
) {
    fun verify(configurationLocation: String, requestedName: String? = null): DesktopLiveSourceSmokeReport {
        val configuration = loadConfiguration(configurationLocation)
        val candidates = if (requestedName.isNullOrBlank()) {
            configuration.lives
        } else {
            listOfNotNull(configuration.lives.firstOrNull { it.name == requestedName })
        }
        require(candidates.isNotEmpty()) {
            requestedName?.let { "Configuration has no Live TV playlist named $it" }
                ?: "Configuration has no Live TV playlists"
        }
        var lastFailure: Throwable? = null
        val skipped = mutableListOf<String>()
        candidates.forEach { reference ->
            runCatching {
                val location = resolveResourceLocation(reference.location, configurationLocation)
                val playlist = loadPlaylist(location)
                require(playlist.channelCount > 0) { "Live TV playlist contains no channels" }
                require(hasReachableStream(playlist)) { "Live TV playlist streams are unreachable" }
                return DesktopLiveSourceSmokeReport(reference, playlist, skipped.toList())
            }.onFailure {
                lastFailure = it
                skipped += reference.name
            }
        }
        throw IllegalArgumentException(
            "No configured Live TV playlist could be loaded: ${lastFailure?.message.orEmpty()}",
            lastFailure,
        )
    }
}
