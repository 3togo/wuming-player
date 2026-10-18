package com.wuming.tv.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CacheTreeTest {
    @Test fun neverFollowsSymlinksOutsideCache() {
        val outside = Files.createTempDirectory("outside-cache")
        val cache = Files.createTempDirectory("p2p-cache")
        try {
            Files.write(outside.resolve("keep"), byteArrayOf(1, 2, 3))
            Files.write(cache.resolve("partial"), byteArrayOf(1, 2))
            Files.createSymbolicLink(cache.resolve("outside"), outside)
            assertEquals(2, CacheTree.size(cache.toFile()))
            CacheTree.delete(cache.toFile())
            assertFalse(Files.exists(cache))
            assertTrue(Files.exists(outside.resolve("keep")))
        } finally {
            CacheTree.delete(cache.toFile())
            CacheTree.delete(outside.toFile())
        }
    }
}
