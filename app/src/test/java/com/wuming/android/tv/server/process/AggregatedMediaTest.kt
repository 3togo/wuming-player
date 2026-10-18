package com.wuming.android.tv.server.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AggregatedMediaTest {

    @Test
    fun computesStartsAndTotal() {
        val media = AggregatedMedia(listOf("a", "b", "c"), longArrayOf(10, 20, 30))
        assertEquals(4, media.starts.size)
        assertEquals(0L, media.starts[0])
        assertEquals(10L, media.starts[1])
        assertEquals(30L, media.starts[2])
        assertEquals(60L, media.starts[3])
        assertEquals(60L, media.total)
    }

    @Test
    fun singleSegment() {
        val media = AggregatedMedia(listOf("only"), longArrayOf(100))
        assertEquals(longArrayOf(0L, 100L).toList(), media.starts.toList())
        assertEquals(100L, media.total)
        assertEquals(0, media.segmentFor(0))
        assertEquals(0, media.segmentFor(50))
        assertEquals(0, media.segmentFor(100)) // at end -> last segment
    }

    @Test
    fun segmentForMapsToContainingSegment() {
        val media = AggregatedMedia(listOf("s0", "s1", "s2"), longArrayOf(10, 20, 30))
        // starts = [0, 10, 30, 60]
        assertEquals(0, media.segmentFor(0))
        assertEquals(0, media.segmentFor(9))
        assertEquals(1, media.segmentFor(10)) // exact start of segment 1
        assertEquals(1, media.segmentFor(29))
        assertEquals(2, media.segmentFor(30)) // exact start of segment 2
        assertEquals(2, media.segmentFor(59))
        assertEquals(2, media.segmentFor(60)) // total -> last segment
    }

    @Test
    fun segmentForBelowZeroClampsToFirst() {
        val media = AggregatedMedia(listOf("s0", "s1"), longArrayOf(10, 20))
        assertEquals(0, media.segmentFor(-5))
    }

    @Test
    fun segmentForFarBeyondTotalClampsToLast() {
        val media = AggregatedMedia(listOf("s0", "s1", "s2"), longArrayOf(10, 20, 30))
        assertEquals(2, media.segmentFor(10_000))
    }

    @Test
    fun zeroLengthSegmentsAllowed() {
        val media = AggregatedMedia(listOf("s0", "s1"), longArrayOf(0, 0))
        assertEquals(0L, media.total)
        assertEquals(longArrayOf(0L, 0L, 0L).toList(), media.starts.toList())
        // All starts are 0, so position 0 maps to the last index whose start <= 0.
        assertEquals(1, media.segmentFor(0))
    }

    @Test
    fun segmentsListExposed() {
        val segments = listOf("x", "y")
        val media = AggregatedMedia(segments, longArrayOf(5, 5))
        assertEquals(segments, media.segments)
        assertTrue(media.total > 0)
    }
}
