package com.wuming.tv.core.runtime.guardspider

import com.wuming.tv.core.catalog.CatalogPlayback

/**
 * Detects the emulator-fallback signal emitted by the guarded host's WebView shim.
 *
 * The shim ([com.wuming.tv.guardhost.webview.WebViewRequiresEmulatorException], in the
 * `guarded-catvod-host` module) throws when no desktop Chromium renderer (JCEF) is on the runtime
 * classpath. This marker string MUST stay in sync with that exception's `MARKER` constant.
 */
object EmulatorMarker {
    const val MARKER = "WEBVIEW_REQUIRES_EMULATOR"

    fun matches(message: String?): Boolean = message != null && MARKER in message
}

/**
 * Pluggable bridge used when a guarded spider's `play()` needs an Android WebView that the desktop
 * host cannot render. The default [DisabledEmulatorWebViewBridge] is a no-op; a real implementation
 * would drive an Android device/emulator (for example via adb plus a minimal WebView helper activity)
 * and return the resolved [CatalogPlayback].
 *
 * The emulator stays the fallback: desktop JCEF runs first when present, and only when it is absent
 * does [EmulatorMarker] fire and this bridge get consulted.
 */
interface AndroidEmulatorWebViewBridge {
    val available: Boolean

    fun playOnEmulator(spec: GuardedCatVodSiteSpec, request: GuardedCatVodHostRequest): CatalogPlayback?
}

object DisabledEmulatorWebViewBridge : AndroidEmulatorWebViewBridge {
    override val available: Boolean get() = false

    override fun playOnEmulator(spec: GuardedCatVodSiteSpec, request: GuardedCatVodHostRequest): CatalogPlayback? = null
}
