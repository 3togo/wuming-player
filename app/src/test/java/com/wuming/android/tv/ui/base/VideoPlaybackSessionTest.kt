package com.wuming.android.tv.ui.base

import com.wuming.tv.core.cloud.OpenDriveFailure
import org.junit.Assert.*
import org.junit.Test

class VideoPlaybackSessionTest {
    @Test fun accountFailureReachesTheSharedFallbackPolicyBeforeItCanRetryCatVod() {
        val session = VideoPlaybackSession()
        session.armFallback("Quark", "external-episode")
        assertNull(session.consumeFallback(OpenDriveFailure("quark", OpenDriveFailure.Reason.AUTH_REQUIRED).message))
        assertNull(session.consumeFallback())
        session.armFallback("Quark", "fresh-episode")
        assertEquals("fresh-episode", session.consumeFallback()!!.rawUrl)
        assertNull(session.consumeFallback())
    }
}
