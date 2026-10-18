package com.wuming.tv.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PrimaryMediaNavigationTest {

    @Test
    fun `vod precedes live like the Android home actions`() {
        assertEquals(
            listOf(PrimaryMediaDestination.VOD, PrimaryMediaDestination.LIVE_TV),
            PrimaryMediaAvailability().destinations(),
        )
    }

    @Test
    fun `unavailable destinations are omitted without changing the remaining identity`() {
        assertEquals(
            listOf(PrimaryMediaDestination.VOD),
            PrimaryMediaAvailability(liveTv = false).destinations(),
        )
        assertEquals(
            listOf(PrimaryMediaDestination.LIVE_TV),
            PrimaryMediaAvailability(vod = false).destinations(),
        )
    }
}
