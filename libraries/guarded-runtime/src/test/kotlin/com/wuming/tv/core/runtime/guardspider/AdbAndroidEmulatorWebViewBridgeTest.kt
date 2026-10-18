package com.wuming.tv.core.runtime.guardspider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AdbAndroidEmulatorWebViewBridgeTest {
    @Test
    fun noSerialMeansUnavailableAndReturnsNullPlayback() {
        val spec = GuardedCatVodSiteSpec("demo", "Demo", "csp_Demo")
        val request = GuardedCatVodHostRequest("play", id = "episodeId", flag = "flag")
        val bridge = AdbAndroidEmulatorWebViewBridge(serial = null, adbExecutable = "/nonexistent/adb")
        assertFalse(bridge.available)
        assertNull(bridge.playOnEmulator(spec, request))
    }
}
