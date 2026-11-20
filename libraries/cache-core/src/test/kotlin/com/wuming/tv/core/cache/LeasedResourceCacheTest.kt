package com.wuming.tv.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeasedResourceCacheTest {
    @Test fun preservesActiveAndPlaybackResourcesWhileRetiringIdleOnes() {
        val disposed = mutableListOf<String>()
        val cache = LeasedResourceCache<String, String>(1, disposed::add)
        fun visit(key: String, pin: Boolean = false) = cache.use(key, { key }) { value, keep ->
            if (pin) keep()
            value
        }
        visit("playing", pin = true)
        visit("old")
        cache.use("active", { "active" }) { _, _ ->
            visit("replacement")
            assertEquals(listOf("old"), disposed)
            cache.trim()
            assertEquals(listOf("old", "replacement"), disposed)
            assertEquals("playing", cache.useExisting(null, { true }) { it })
            cache.clear()
            assertEquals(listOf("old", "replacement", "playing"), disposed)
        }
        assertEquals(listOf("old", "replacement", "playing", "active"), disposed)
        assertNull(cache.useExisting(null, { true }) { it })
    }

    @Test fun earlierLeaseCannotPinAnEntryOfAReplacementGeneration() {
        val disposed = mutableListOf<String>()
        val cache = LeasedResourceCache<String, String>(1, disposed::add)
        cache.use("shared", { "old" }) { _, pinOld ->
            cache.clear()
            cache.use("shared", { "current" }) { _, pinCurrent -> pinCurrent() }
            pinOld()
            assertEquals("current", cache.useExisting(null, { true }) { it })
        }
        assertEquals(listOf("old"), disposed)
    }
}
