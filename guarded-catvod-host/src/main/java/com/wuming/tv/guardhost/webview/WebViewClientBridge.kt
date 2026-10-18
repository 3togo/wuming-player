package com.wuming.tv.guardhost.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * Callbacks the engine invokes as the provider's `WebViewClient` would on Android. The
 * `android.webkit.WebView` implementation forwards these to the provider's WebViewClient
 * subclass (which may capture the resolved media URL).
 */
interface WebViewClientBridge {
    fun shouldOverrideUrlLoading(request: WebResourceRequest?): Boolean

    fun onPageStarted(url: String?)

    fun onPageFinished(url: String?)

    fun onLoadResource(url: String?)

    fun shouldInterceptRequest(request: WebResourceRequest?): WebResourceResponse?
}
