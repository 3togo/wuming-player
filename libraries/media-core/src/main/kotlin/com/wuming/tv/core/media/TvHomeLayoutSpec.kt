package com.wuming.tv.core.media

/** Platform-neutral home actions derived from the Leanback TV shell. */
enum class TvHomeAction {
    VOD,
    LIVE_TV,
    SEARCH,
    KEEP,
    PUSH,
    SETTINGS,
    CLOUD_ACCOUNTS,
}

data class TvHomeLayoutSpec(
    val actions: List<TvHomeAction>,
    val toolbarHeightRatio: Float = 0.125f,
    val toolbarIconRatio: Float = 0.45f,
    val toolbarTextRatio: Float = 0.30f,
    val actionTileEdge: Int = 100,
) {
    init {
        require(toolbarHeightRatio in 0.05f..0.30f)
        require(toolbarIconRatio in 0.1f..1.0f)
        require(toolbarTextRatio in 0.1f..1.0f)
        require(actionTileEdge >= 64)
        require(actions.distinct().size == actions.size)
    }

    companion object {
        /** The stable primary action row used by the Android Leanback home screen. */
        fun leanback(
            media: PrimaryMediaAvailability = PrimaryMediaAvailability(),
        ): TvHomeLayoutSpec = TvHomeLayoutSpec(buildList {
            if (media.vod) add(TvHomeAction.VOD)
            if (media.liveTv) add(TvHomeAction.LIVE_TV)
            if (media.vod) add(TvHomeAction.SEARCH)
            if (media.vod) add(TvHomeAction.KEEP)
            add(TvHomeAction.PUSH)
            add(TvHomeAction.SETTINGS)
        })

        /** Leanback's primary row followed only by desktop-specific extensions. */
        fun desktop(
            media: PrimaryMediaAvailability = PrimaryMediaAvailability(),
        ): TvHomeLayoutSpec = leanback(media).let { leanback ->
            leanback.copy(actions = leanback.actions + TvHomeAction.CLOUD_ACCOUNTS)
        }
    }
}
