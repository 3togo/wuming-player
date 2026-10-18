package com.wuming.tv.core.playback
import org.junit.Assert.*
import org.junit.Test

class ObservedWatchTimeTest {
    @Test fun `only bounded progressing observations count`() {
        val time = ObservedWatchTime()
        fun state(at: Long, position: Double, paused: Boolean = false, buffering: Boolean = false, speed: Double = 1.0) =
            time.observe(PlaybackState(positionSeconds = position, paused = paused, buffering = buffering, speed = speed), at)
        state(0, 0.0); state(1000, 1.0)
        assertEquals(1000, time.watchedMillis)
        state(2000, 1.0, paused = true); state(602000, 1.0, paused = true)
        state(603000, 1.0); state(604000, 60.0) // seek
        state(605000, 60.0, buffering = true); state(606000, 60.0)
        assertEquals(1000, time.watchedMillis)
        state(607000, 61.0); state(608000, 62.0, speed = 2.0); state(609000, 64.0, speed = 2.0)
        assertEquals(3000, time.watchedMillis)
        time.disconnected(); state(610000, 65.0); state(620000, 75.0)
        assertEquals(3000, time.watchedMillis)
    }
}
