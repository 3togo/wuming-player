package com.wuming.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortNameCachePolicyTest {

    @Test
    fun `normalize trims and handles null`() {
        assertEquals("", ShortNameCachePolicy.normalize(null))
        assertEquals("", ShortNameCachePolicy.normalize(""))
        assertEquals("hello", ShortNameCachePolicy.normalize("  hello  "))
    }

    @Test
    fun `evictIfNeeded removes oldest entries when over limit`() {
        val map = LinkedHashMap<String, String>()
        map["a"] = "1"
        map["b"] = "2"
        map["c"] = "3"
        ShortNameCachePolicy.evictIfNeeded(map, 2)
        assertEquals(2, map.size)
        assertEquals("2", map["b"])
        assertEquals("3", map["c"])
    }

    @Test
    fun `evictIfNeeded does nothing when under limit`() {
        val map = LinkedHashMap<String, String>()
        map["a"] = "1"
        ShortNameCachePolicy.evictIfNeeded(map, 5)
        assertEquals(1, map.size)
    }
}
