package com.wuming.tv.guardhost.webview

/** Abstraction over a WebView rendering backend used by the desktop guarded host.  */
interface WebViewEngine {
    /**
     * Load `url` and forward navigation/resource events to `bridge`. Implementations that
     * cannot render on the desktop throw [WebViewRequiresEmulatorException].
     */
    @Throws(WebViewRequiresEmulatorException::class)
    fun loadUrl(
        url: String?,
        extraHeaders: MutableMap<String?, String?>?,
        bridge: WebViewClientBridge?
    )

    fun destroy()
}
