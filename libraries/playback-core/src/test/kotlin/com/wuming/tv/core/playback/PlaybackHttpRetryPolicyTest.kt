package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackHttpRetryPolicyTest {
    @Test fun deniedOrExpiredRequestsStopWhileTransientFailuresCanRetry() {
        for (status in listOf(400, 401, 403, 404, 405, 410, 416)) assertFalse(PlaybackHttpRetryPolicy.shouldRetry(status))
        for (status in listOf(408, 429, 500, 502, 503, 504)) assertTrue(PlaybackHttpRetryPolicy.shouldRetry(status))
    }
}
