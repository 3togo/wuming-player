package com.wuming.tv.core.runtime.guardspider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsMarkerTest {
    @Test
    fun detectsGmsClassNotFoundInHostErrorMessage() {
        assertTrue(
            GmsMarker.matches(
                "IllegalArgumentException: ClassNotFoundException: com.google.android.gms.common.api.Status",
            ),
        )
    }

    @Test
    fun detectsGmsNoClassDefFound() {
        assertTrue(
            GmsMarker.matches(
                "NoClassDefFoundError: com/google/android/gms/common/internal/A",
            ),
        )
    }

    @Test
    fun ignoresOrdinaryErrors() {
        assertFalse(GmsMarker.matches("IllegalArgumentException: provider does not implement play"))
        assertFalse(GmsMarker.matches(null))
        assertFalse(GmsMarker.matches(""))
        // GMS package mentioned but not a linkage error is not a GMS-route signal
        assertFalse(GmsMarker.matches("some message about com.google.android.gms being unavailable"))
    }

    @Test
    fun disabledBridgeIsUnavailable() {
        assertFalse(DisabledAndroidRuntimeProviderBridge.available)
    }
}
