package com.wuming.tv.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.LinkedHashMap

class BoundedCachePolicyTest {

    @Test
    fun `expiry treats future writes and over-age writes as expired`() {
        assertTrue(BoundedCachePolicy.isExpired(1000, 500, 10_000))
        assertTrue(BoundedCachePolicy.isExpired(0, 10_001, 10_000))
        assertFalse(BoundedCachePolicy.isExpired(0, 5_000, 10_000))
    }

    @Test
    fun `evictToLimit removes eldest entries only`() {
        val map = LinkedHashMap<String, Int>()
        (1..5).forEach { map["k$it"] = it }
        BoundedCachePolicy.evictToLimit(map, 3)
        assertEquals(listOf("k3", "k4", "k5"), map.keys.toList())
    }

    @Test
    fun `evictToLimit is a no-op under the limit`() {
        val map = LinkedHashMap<String, Int>()
        map["only"] = 1
        BoundedCachePolicy.evictToLimit(map, 5)
        assertEquals(mapOf("only" to 1), map)
    }
}
