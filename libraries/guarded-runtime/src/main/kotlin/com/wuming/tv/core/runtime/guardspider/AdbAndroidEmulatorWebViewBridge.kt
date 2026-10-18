package com.wuming.tv.core.runtime.guardspider

import com.wuming.tv.core.catalog.CatalogPlayback
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Real [AndroidEmulatorWebViewBridge] that wires a guarded provider's WebView to a real Android
 * WebView running in the `guarded-webview-helper` app on a connected device/emulator.
 *
 * The bridge is constructed (and [available] evaluated) only when a serial is configured via
 * `tvbox.guarded.emulator.serial` (or `ANDROID_SERIAL`). On first use it sets up the `adb forward`
 * tunnel and launches the helper; the desktop host process was launched with
 * `-Dtvbox.guarded.webview.adbSerial`, so its [com.wuming.tv.guardhost.webview.AdbWebViewEngine]
 * renders the WebView on the device and `play()` returns the resolved [CatalogPlayback] directly.
 *
 * When no device is wired up this bridge is not installed (the executor keeps
 * [DisabledEmulatorWebViewBridge]), so only the typed `requiresEmulator` signal is produced.
 */
class AdbAndroidEmulatorWebViewBridge(
    private val serial: String? = System.getProperty("tvbox.guarded.emulator.serial")
        ?: System.getenv("ANDROID_SERIAL"),
    private val adbExecutable: String = AdbDeviceTunnel.resolveAdb(),
) : AndroidEmulatorWebViewBridge {
    private val logger = Logger.getLogger("tvbox.guarded.emulator")

    override val available: Boolean by lazy { serial != null && AdbDeviceTunnel.deviceOnline(adbExecutable, serial) }
    private val ready = AtomicBoolean(false)

    /**
     * Proactively establishes the `adb forward` tunnel and launches the helper so the guarded host
     * child (launched with `-Dtvbox.guarded.webview.adbSerial`) can render on the device on its first
     * `play()`. Without this, the tunnel would only be set up after a provider throws the emulator
     * marker, which is too late for the first playback.
     */
    fun prepare(): Boolean = ensureReady()

    override fun playOnEmulator(spec: GuardedCatVodSiteSpec, request: GuardedCatVodHostRequest): CatalogPlayback? {
        if (!ensureReady()) return null
        // Rendering happens in the host's AdbWebViewEngine (it was launched with the serial); play()
        // re-runs there and returns the real CatalogPlayback, so there is nothing to synthesise here.
        logger.info("playOnEmulator key=${spec.key} device=$serial helperReady=true")
        return null
    }

    private fun ensureReady(): Boolean {
        if (ready.get()) return true
        val serial = serial ?: return false
        if (!AdbDeviceTunnel.deviceOnline(adbExecutable, serial)) return false
        AdbDeviceTunnel.setupForward(adbExecutable, serial, WEBVIEW_BRIDGE_PORT)
        AdbDeviceTunnel.launchHelper(adbExecutable, serial, HELPER_COMPONENT, forceStop = false)
        if (!AdbDeviceTunnel.waitForSocket(WEBVIEW_BRIDGE_PORT, SOCKET_POLL_ATTEMPTS, SOCKET_POLL_INTERVAL_MS)) return false
        ready.set(true)
        logger.info("playOnEmulator device=$serial tunnelReady=true")
        return true
    }

    private companion object {
        const val WEBVIEW_BRIDGE_PORT = 8765
        const val HELPER_COMPONENT = "com.wuming.tv.guardwebview.publicedition/com.wuming.tv.guardwebview.MainActivity"
        const val SOCKET_POLL_ATTEMPTS = 20
        const val SOCKET_POLL_INTERVAL_MS = 250L
    }
}
