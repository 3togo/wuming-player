package com.wuming.tv.core.source

import com.wuming.tv.core.source.config.TvBoxSiteConfig
import org.junit.Assert.*
import org.junit.Test

class TvBoxBrowseVisibilityTest {
    @Test fun searchAndIndexFlagsDoNotDisableHomeBrowsing() {
        assertTrue(TvBoxSiteConfig(key = "education", api = "csp_BiliGuard", searchable = 0).browsable)
        assertTrue(TvBoxSiteConfig(key = "music", api = "csp_MusicGuard", searchable = 0, indexs = "").browsable)
    }

    @Test fun hiddenAndPushHandlersAreNotCatalogues() {
        assertFalse(TvBoxSiteConfig(key = "hidden", hide = true).browsable)
        for (site in listOf(TvBoxSiteConfig(key = "push_agent"),
            TvBoxSiteConfig(key = "custom-push", api = "csp_PushGuard"),
            TvBoxSiteConfig(key = "custom-push", api = "csp_Push"))) {
            assertTrue(site.inputOnly)
            assertFalse(site.browsable)
        }
    }
}
