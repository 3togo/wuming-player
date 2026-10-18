package com.wuming.tv.core.runtime.guardspider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorMarkerTest {
    @Test
    fun detectsMarkerInHostErrorMessage() {
        assertTrue(
            EmulatorMarker.matches(
                "WebViewRequiresEmulatorException: WEBVIEW_REQUIRES_EMULATOR: no desktop renderer for https://x",
            ),
        )
    }

    @Test
    fun ignoresOrdinaryErrors() {
        assertFalse(EmulatorMarker.matches("IllegalArgumentException: provider does not implement play"))
        assertFalse(EmulatorMarker.matches(null))
        assertFalse(EmulatorMarker.matches(""))
    }

    @Test
    fun disabledBridgeIsUnavailable() {
        assertFalse(DisabledEmulatorWebViewBridge.available)
        assertFalse(EmulatorMarker.matches("unrelated failure"))
    }
}
