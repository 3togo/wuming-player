package com.wuming.tv.desktop.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopDepotTest {

    @Test
    fun `loads the same bundled VOD and Live depot used by leanback`() {
        val depot = DesktopDepot.load()

        assertTrue(depot.vod.isEmpty())
        assertTrue(depot.live.isNotEmpty())
        assertTrue(depot.vod.none { it.location == "http://fty.888484.xyz/tv" })
        assertTrue(depot.live.any { it.location == "assets://live/public_samples.m3u8" })
        assertEquals(depot.vod.size, depot.vod.map { it.location }.distinct().size)
        assertEquals(depot.live.size, depot.live.map { it.location }.distinct().size)
    }

    @Test
    fun `recognizes explicit and named warehouses`() {
        val depot = DesktopDepot.load {
            """{"vod":[{"name":"Marked","url":"https://example.org/a.json","warehouse":true},
                {"name":"影视多仓","url":"https://example.org/b.json"}],"live":[]}"""
                .byteInputStream()
        }
        assertTrue(depot.vod.all { it.warehouse })
    }
}
