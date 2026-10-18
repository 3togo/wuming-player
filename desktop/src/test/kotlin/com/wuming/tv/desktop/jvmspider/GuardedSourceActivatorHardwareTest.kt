package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.catalog.CatalogPlayback
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * End-to-end hardware test for the desktop emulator-fallback vertical. It only runs when
 * `-Dtvbox.guarded.hardware.test.serial=<serial>` is supplied and the `guarded-webview-helper` app
 * is installed on that device.
 *
 * It activates a synthetic WebView-using guarded provider through the real [GuardedSourceActivator],
 * which wires [com.wuming.tv.core.runtime.guardspider.AdbAndroidEmulatorWebViewBridge] from the serial and
 * (proactively) sets up the `adb forward` tunnel + helper. The provider's `play()` then runs on the
 * real device WebView reached through the host child's [com.wuming.tv.guardhost.webview.AdbWebViewEngine],
 * and the resolved URL must round-trip back to the desktop as a supported [CatalogPlayback].
 */
class GuardedSourceActivatorHardwareTest {

    @Test
    fun desktopRoutesWebViewProviderPlayToRealDevice() {
        val serial = System.getProperty("tvbox.guarded.hardware.test.serial")
        Assume.assumeNotNull(
            "Set -Dtvbox.guarded.hardware.test.serial and install guarded-webview-helper to run this hardware test",
            serial,
        )
        System.setProperty("tvbox.guarded.emulator.serial", serial!!)
        System.clearProperty("ANDROID_SERIAL")

        val resource = checkNotNull(javaClass.getResource("/guarded/WebViewProbe-dexed.jar")) {
            "WebViewProbe fixture jar missing from test resources"
        }
        val jarPath = Path.of(URI(resource.toString()))
        val sha256 = sha256(jarPath)

        val activator = GuardedSourceActivator()
        val site = JvmSpiderSiteSpec(
            key = "csp_WebViewProbe",
            name = "WebViewProbe",
            api = "csp_WebViewProbe",
            artifactLocation = jarPath.toAbsolutePath().toString(),
        )
        val executor = activator.activate(site, sha256, GuardedSpiderAbi.ARMEABI_V7A)
        try {
            val playback = executor.play("", "probe-id")
            assertFalse("device WebView play must not report requiresEmulator when a device is wired", playback.requiresEmulator)
            assertTrue("playback should be supported via the device WebView", playback.supported)
            assertEquals("https://www.example.com/", playback.url)
        } finally {
            executor.close()
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { digest.update(it.readAllBytes()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
