package com.wuming.tv.core.media

/** Platform-neutral destinations exposed by the Android home screen and desktop shell. */
enum class PrimaryMediaDestination(val stableKey: String) {
    VOD("vod"),
    LIVE_TV("live"),
}

data class PrimaryMediaAvailability(
    val vod: Boolean = true,
    val liveTv: Boolean = true,
) {
    fun destinations(): List<PrimaryMediaDestination> = buildList {
        if (vod) add(PrimaryMediaDestination.VOD)
        if (liveTv) add(PrimaryMediaDestination.LIVE_TV)
    }
}
