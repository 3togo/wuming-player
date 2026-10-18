package com.wuming.tv.core.live

/** A named playlist advertised by a platform configuration. Loading remains a platform concern. */
data class LivePlaylistReference(
    val name: String,
    val location: String,
) {
    init {
        require(name.isNotBlank()) { "Live playlist name is required" }
        require(location.isNotBlank()) { "Live playlist location is required" }
    }
}
