package com.wuming.android.tv.api

import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.bean.Flag
import com.wuming.android.tv.bean.Vod
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class DetailPlaybackAvailabilityTest {
    @Test fun metadataOnlyDetailMustBeRefetched() {
        val vod = Vod().apply { setName("天空制造") }
        assertFalse(DetailPlaybackAvailability.hasPlayableEpisodes(vod))
        assertFalse(DetailPlaybackAvailability.canCache(vod))
        assertFalse(DetailPlaybackAvailability.canCache(null))
    }

    @Test fun namedRouteWithOnlyBlankUrlsIsNotPlayable() {
        val vod = Vod()
        val route = Flag("direct")
        route.getEpisodes().add(Episode().apply { setUrl("  ") })
        vod.getFlags().add(route)
        assertFalse(DetailPlaybackAvailability.canCache(vod))
        route.getEpisodes().add(Episode().apply { setUrl("https://example.test/video.m3u8") })
        assertTrue(DetailPlaybackAvailability.hasPlayableEpisodes(vod))
        assertTrue(DetailPlaybackAvailability.canCache(vod))
    }

    @Test fun browsableFoldersAndActionsKeepTheirMetadataCache() {
        val folder = Gson().fromJson("""{"vod_tag":"folder"}""", Vod::class.java)
        val action = Gson().fromJson("""{"action":"refresh"}""", Vod::class.java)
        assertFalse(DetailPlaybackAvailability.hasPlayableEpisodes(folder))
        assertTrue(DetailPlaybackAvailability.canCache(folder))
        assertTrue(DetailPlaybackAvailability.canCache(action))
    }
}
