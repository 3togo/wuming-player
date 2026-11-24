package com.wuming.tv.core.media

import com.wuming.tv.core.cloud.OpenDriveFailure
import org.junit.Assert.*
import org.junit.Test

class HistoryPlaybackFallbackTest {
    @Test fun cloudFailuresNeverSwitchToTheRawProviderOrLeaveALaterRetryArmed() {
        for (reason in OpenDriveFailure.Reason.entries) {
            val fallback = HistoryPlaybackFallback()
            fallback.arm("Quark", "external-episode", mapOf("Referer" to "https://example.invalid/"))
            assertNull(fallback.consume(OpenDriveFailure("quark", reason).message))
            assertNull(fallback.consume())
        }
    }

    @Test fun staleCatalogIdentityStillFallsBackOnceWithItsOriginalHeaders() {
        val fallback = HistoryPlaybackFallback()
        val headers = mapOf("Referer" to "https://example.invalid/")
        fallback.arm("Quark", "external-episode", headers)
        val attempt = fallback.consume("Catalog entry is no longer available")!!
        assertEquals("Quark", attempt.flag)
        assertEquals("external-episode", attempt.rawUrl)
        assertEquals(headers, attempt.headers)
        assertNull(fallback.consume())
    }
}
