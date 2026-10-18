package com.wuming.tv.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class TvHomeLayoutSpecTest {

    @Test
    fun `desktop preserves leanback primary action order and proportions`() {
        val spec = TvHomeLayoutSpec.desktop()
        val leanback = TvHomeLayoutSpec.leanback()

        assertEquals(
            listOf(
                TvHomeAction.VOD,
                TvHomeAction.LIVE_TV,
                TvHomeAction.SEARCH,
                TvHomeAction.KEEP,
                TvHomeAction.PUSH,
                TvHomeAction.SETTINGS,
            ),
            leanback.actions,
        )
        assertEquals(leanback.actions + TvHomeAction.CLOUD_ACCOUNTS, spec.actions)
        assertEquals(0.125f, spec.toolbarHeightRatio)
        assertEquals(100, spec.actionTileEdge)
    }
}
