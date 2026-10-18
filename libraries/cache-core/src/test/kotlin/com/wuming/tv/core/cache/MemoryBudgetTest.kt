package com.wuming.tv.core.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MemoryBudgetTest {
    private val MEGABYTE = 1024L * 1024L

    @Test fun tiersMatchTheHistoricalAndroidLadder() {
        val low = MemoryBudget(512L * MEGABYTE)
        val mid = MemoryBudget(1024L * MEGABYTE)
        val high = MemoryBudget(4096L * MEGABYTE)
        assertEquals(100, low.scale(100, 200, 400))
        assertEquals(200, mid.scale(100, 200, 400))
        assertEquals(400, high.scale(100, 200, 400))
    }

    @Test fun tierBoundariesMatchThePreviousIntegerDivision() {
        assertEquals(200, MemoryBudget(2048L * MEGABYTE - 1).scale(100, 200, 400))
        assertEquals(400, MemoryBudget(2048L * MEGABYTE).scale(100, 200, 400))
        assertEquals(100, MemoryBudget(1024L * MEGABYTE - 1).scale(100, 200, 400))
        assertEquals(200, MemoryBudget(1024L * MEGABYTE).scale(100, 200, 400))
    }

    @Test fun weightedBytesIsAFloorOfTheRequestedRatio() {
        assertEquals(64L * MEGABYTE, MemoryBudget(512L * MEGABYTE).weightedBytes(0.125))
        assertEquals(512L * MEGABYTE, MemoryBudget(512L * MEGABYTE).weightedBytes(1.0))
        assertEquals(333L, MemoryBudget(1000L).weightedBytes(0.3339))
    }

    @Test fun budgetsRejectNonPositiveOrOutOfBoundsInputs() {
        assertThrows(IllegalArgumentException::class.java) { MemoryBudget(0L) }
        assertThrows(IllegalArgumentException::class.java) { MemoryBudget(512L * MEGABYTE).weightedBytes(0.0) }
        assertThrows(IllegalArgumentException::class.java) { MemoryBudget(512L * MEGABYTE).weightedBytes(1.5) }
    }

    @Test fun currentReadsTheLiveHeapCeiling() {
        assertEquals(Runtime.getRuntime().maxMemory(), MemoryBudget.current().maxMemoryBytes())
    }
}
