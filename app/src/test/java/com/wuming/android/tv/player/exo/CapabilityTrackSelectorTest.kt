package com.wuming.android.tv.player.exo

import org.junit.Assert.*
import org.junit.Test

class CapabilityTrackSelectorTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun ignoresMedia3UnusedNullCapacityAndCopiesActiveTracks() {
        val video = arrayOfNulls<IntArray>(4)
        video[0] = intArrayOf(4, 3)
        val emptyRenderer = arrayOfNulls<IntArray>(4)
        val unmapped = arrayOfNulls<IntArray>(4)
        val supports = arrayOf(video, emptyRenderer, unmapped) as Array<Array<IntArray>>

        val copy = CapabilityTrackSelector.copyMappedSupports(supports, intArrayOf(1, 0))
        assertEquals(2, copy.size)
        assertEquals(1, copy[0].size)
        assertEquals(0, copy[1].size)
        assertArrayEquals(intArrayOf(4, 3), copy[0][0])
        copy[0][0][0] = 0
        assertEquals(4, video[0]!![0])
    }
}
