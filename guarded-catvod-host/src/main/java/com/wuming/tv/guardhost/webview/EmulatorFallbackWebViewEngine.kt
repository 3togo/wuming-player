package com.wuming.tv.guardhost.webview

/** Fallback engine used when no desktop renderer is available; signals the emulator path.  */
open class EmulatorFallbackWebViewEngine : WebViewEngine {

    override fun loadUrl(
        url: String?,
        extraHeaders: MutableMap<String?, String?>?,
        bridge: WebViewClientBridge?
    ): Unit = throw WebViewRequiresEmulatorException(
        "No desktop WebView rendering engine available for " + url
    )

    override fun destroy() {
    }
}
