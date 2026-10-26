package com.wuming.tv.core.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pCachePolicyTest {
    @Test fun fixedBudgetAndLowSpaceTriggerPressure() {
        val max = P2pCachePolicy.MAX_CACHE_BYTES
        val min = P2pCachePolicy.MIN_FREE_BYTES
        assertFalse(P2pCachePolicy.underPressure(max - 1, min))
        assertTrue(P2pCachePolicy.underPressure(max, min))
        assertTrue(P2pCachePolicy.underPressure(0, min - 1))
        assertTrue(P2pCachePolicy.underPressure(0, 0))
        assertFalse(P2pCachePolicy.underPressure(0, null))
        assertFalse(P2pCachePolicy.canStart(min - 1))
        assertTrue(P2pCachePolicy.canStart(min))
        assertTrue(P2pCachePolicy.canStart(null))
    }
}
