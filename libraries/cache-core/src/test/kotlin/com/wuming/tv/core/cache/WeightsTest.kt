package com.wuming.tv.core.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class WeightsTest {

    @Test
    fun byteArrayWeightIsItsLength() {
        assertEquals(0L, Weights.bytes(null))
        assertEquals(0L, Weights.bytes(ByteArray(0)))
        assertEquals(12L, Weights.bytes(ByteArray(12)))
    }

    @Test
    fun textWeightMatchesUtf8Encoding() {
        assertEquals(0L, Weights.utf8(null))
        assertEquals(0L, Weights.utf8(""))
        assertEquals(12L, Weights.utf8("#EXTM3U\na.ts"))
        assertEquals(12L, Weights.utf8("中文名称"))
        assertEquals(4L, Weights.utf8("éü"))
        assertEquals(4L, Weights.utf8("😀"))
        assertEquals(5L, Weights.utf8("a😀"))
    }

    @Test
    fun unpairedSurrogateFallsBackToThreeBytes() {
        assertEquals(3L, Weights.utf8("\uD800"))
        assertEquals(4L, Weights.utf8("\uD800\uDC00"))
        assertEquals(6L, Weights.utf8("\uD800\uD800"))
    }
}
