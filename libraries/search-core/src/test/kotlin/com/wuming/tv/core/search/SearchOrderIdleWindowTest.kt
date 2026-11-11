package com.wuming.tv.core.search

import org.junit.Assert.*
import org.junit.Test

class SearchOrderIdleWindowTest {
    @Test fun initialResultsSortAndNavigationDefersUntilExactIdleBoundary() {
        val window = SearchOrderIdleWindow()
        assertTrue(window.canSort(0))
        window.recordInput(100)
        assertFalse(window.canSort(2099))
        assertTrue(window.canSort(2100))
    }

    @Test fun repeatsAndKeyReleaseRestartWindowAndNewSearchResetsIt() {
        val window = SearchOrderIdleWindow()
        window.recordInput(100)
        window.recordInput(1800)
        assertFalse(window.canSort(2100))
        window.recordInput(2000)
        assertFalse(window.canSort(3999))
        assertTrue(window.canSort(4000))
        window.reset()
        assertTrue(window.canSort(4001))
    }
}
