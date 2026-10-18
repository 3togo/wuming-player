package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException

class PlaybackLoadRetryPolicyTest {
    @Test fun httpRetryUsesOneBoundedScheduleAndHonorsServerDelay() {
        val policy = PlaybackHttpRetryPolicy
        assertEquals(1_000L, policy.retryDelayMillis(503, 1, null, 0L))
        assertEquals(4_000L, policy.retryDelayMillis(503, 3, null, 0L))
        assertEquals(8_000L, policy.retryDelayMillis(503, 4, null, 0L))
        assertEquals(16_000L, policy.retryDelayMillis(503, 5, null, 0L))
        assertNull(policy.retryDelayMillis(503, 6, null, 0L))
        assertEquals(12_000L, policy.retryDelayMillis(429, 1, "12", 0L))
        assertEquals(8_000L, policy.retryDelayMillis(408, 4, null, 0L))
        assertNull(policy.retryDelayMillis(403, 1, null, 0L))
    }

    @Test fun retryAfterAcceptsHttpDatesAndRejectsUnboundedWaits() {
        val policy = PlaybackHttpRetryPolicy
        val now = java.time.Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
        assertEquals(10_000L, policy.retryDelayMillis(429, 1,
            "Mon, 12 Oct 2026 00:00:10 GMT", now))
        assertNull(policy.retryDelayMillis(429, 1, "120", now))
        assertEquals(1_000L, policy.retryDelayMillis(429, 1, "invalid", now))
    }

    @Test fun wrappedConnectionFailuresStopAfterOneRetry() {
        for (failure in listOf(SocketTimeoutException(), ConnectException(),
            UnknownHostException(), NoRouteToHostException())) {
            val wrapped = IOException(ExecutionException(failure))
            assertEquals(1000L, PlaybackLoadRetryPolicy.retryDelayMillis(wrapped, 1))
            assertNull(PlaybackLoadRetryPolicy.retryDelayMillis(wrapped, 2))
            assertNull(PlaybackLoadRetryPolicy.retryDelayMillis(wrapped, 20))
        }
    }

    @Test fun aLaterUserAttemptHasAFreshRetryBudget() {
        val error = SocketTimeoutException()
        assertNull(PlaybackLoadRetryPolicy.retryDelayMillis(error, 2))
        assertEquals(1000L, PlaybackLoadRetryPolicy.retryDelayMillis(error, 1))
    }

    @Test fun otherLoadFailuresKeepTheirExistingBudget() {
        val error = IOException()
        assertEquals(1000L, PlaybackLoadRetryPolicy.retryDelayMillis(error, 1))
        assertEquals(2000L, PlaybackLoadRetryPolicy.retryDelayMillis(error, 2))
        assertEquals(4000L, PlaybackLoadRetryPolicy.retryDelayMillis(error, 3))
        assertNull(PlaybackLoadRetryPolicy.retryDelayMillis(error, 4))
    }
}
