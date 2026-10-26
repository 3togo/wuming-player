package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeRuntimeLeaseTest {
    @Test
    fun releaseWaitsForPlaybackAndParserLeases() {
        var releases = 0
        val runtime = NativeRuntimeLease { releases++ }
        runtime.acquire() // playback task
        runtime.acquire() // episode parser
        runtime.requestRelease()
        assertEquals(0, releases)
        runtime.release()
        assertEquals(0, releases)
        runtime.release()
        assertEquals(1, releases)
    }
}
