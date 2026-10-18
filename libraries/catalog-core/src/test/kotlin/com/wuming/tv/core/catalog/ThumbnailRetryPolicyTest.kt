package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test

class ThumbnailRetryPolicyTest {
    @Test fun retriesArePacedBoundedAndSuppressedAcrossRebinds() {
        var now = 100L
        val policy = ThumbnailRetryPolicy<String> { now }
        assertEquals(15_000L, policy.failed("first", true, false))
        assertEquals(20_000L, policy.failed("second", true, false))
        assertNull(policy.failed("first", true, false))
        assertNull(policy.failed("first", true, true))
        assertTrue(policy.isBlocked("first"))
        for (i in 0..5) assertNotNull(policy.failed("queued-$i", true, false))
        assertNull(policy.failed("overflow", true, false))
        now += ThumbnailRetryPolicy.COOLDOWN_MS
        assertFalse(policy.isBlocked("first"))
        assertEquals(15_000L, policy.failed("first", true, false))
        policy.succeeded("first")
        assertFalse(policy.isBlocked("first"))
    }

    @Test fun permanentAndUnknownFailuresDoNotRetry() {
        for (status in listOf(400, 401, 403, 404, 410, 429)) {
            assertFalse(ThumbnailRetryPolicy.isTransient(status, true))
        }
        assertTrue(ThumbnailRetryPolicy.isTransient(503, false))
        assertTrue(ThumbnailRetryPolicy.isTransient(408, false))
        assertTrue(ThumbnailRetryPolicy.isTransient(null, true))
        assertFalse(ThumbnailRetryPolicy.isTransient(null, false))
        val policy = ThumbnailRetryPolicy<String> { 0L }
        assertNull(policy.failed("missing", false, false))
        assertTrue(policy.isBlocked("missing"))
        assertNull(policy.failed("retry-exhausted", true, true))
        policy.clear()
        assertFalse(policy.isBlocked("missing"))
    }
}
