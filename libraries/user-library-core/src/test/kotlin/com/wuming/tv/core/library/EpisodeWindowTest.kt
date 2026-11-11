package com.wuming.tv.core.library

import org.junit.Assert.*
import org.junit.Test

class EpisodeWindowTest {
    @Test fun crossesOldPageBoundaryAndMapsDisplayedOrder() {
        val window = EpisodeWindow.around(400, 120)
        assertEquals(EpisodeWindow(60,181,60), window)
        assertEquals(119, window.absoluteIndex(59))
        assertNull(window.absoluteIndex(-1))
        assertNull(window.absoluteIndex(121))
        val reversed = (399 downTo 0).toList()
        assertEquals(279, reversed[window.absoluteIndex(window.selected)!!])
    }
    @Test fun emptyEndsAndLargeRadius() {
        assertEquals(EpisodeWindow(0,0,-1), EpisodeWindow.around(0,5))
        assertEquals(EpisodeWindow(0,61,0), EpisodeWindow.around(400,-1))
        assertEquals(EpisodeWindow(339,400,60), EpisodeWindow.around(400,999))
        assertEquals(EpisodeWindow(0,400,120), EpisodeWindow.around(400,120,Int.MAX_VALUE))
    }
}
