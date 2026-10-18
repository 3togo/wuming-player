package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.Callable

class LocalMediaUrlWaiterTest {
    @Test
    fun acceptsTheFirstPublishedUrl() {
        var polls = 0
        var sleeps = 0
        val url = LocalMediaUrlWaiter.await(4, Callable {
            polls++
            if (polls == 3) "http://127.0.0.1:8080/video" else null
        }) { sleeps++ }

        assertEquals("http://127.0.0.1:8080/video", url)
        assertEquals(3, polls)
        assertEquals(2, sleeps)
    }

    @Test
    fun timeoutDoesNotSleepAfterTheFinalProbe() {
        var sleeps = 0
        assertNull(LocalMediaUrlWaiter.await(3, Callable { " " }) { sleeps++ })
        assertEquals(2, sleeps)
    }

    @Test
    fun probeFailureStopsPolling() {
        var sleeps = 0
        assertThrows(IllegalStateException::class.java) {
            LocalMediaUrlWaiter.await(3, Callable<String?> { error("native task failed") }) { sleeps++ }
        }
        assertEquals(0, sleeps)
    }
}
