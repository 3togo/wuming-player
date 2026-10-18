package com.wuming.android.tv.ui.custom

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.net.http.SslError
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.wuming.android.tv.App
import com.wuming.android.tv.Constant
import com.wuming.android.tv.api.config.RuleConfig
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.impl.ParseCallback
import com.wuming.android.tv.setting.PlayerSettings
import com.wuming.android.tv.ui.dialog.WebDialog
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.Sniffer
import com.wuming.android.tv.utils.UiTaskScope
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderDebug
import com.github.catvod.utils.Util
import com.google.common.net.HttpHeaders
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

open class CustomWebView private constructor(
    context: Context,
) : WebView(context),
    DialogInterface.OnDismissListener {
    companion object {
        private val TAG = CustomWebView::class.java.simpleName
        private val PLAYER = Pattern.compile("player.*https?://")
        private const val BLANK = "about:blank"
        private const val MAX_URLS = 5

        @JvmStatic fun create(context: Context): CustomWebView = CustomWebView(context)
    }

    private val callbackRef = AtomicReference<ParseCallback?>()
    private val urls = LinkedHashSet<String>()
    private val children = ArrayList<CustomWebView>()
    private val uiTasks = UiTaskScope()
    private var destroyed = false
    private lateinit var empty: WebResourceResponse
    private var dialog: WebDialog? = null
    private var timer: Runnable? = null

    @Volatile private var stopped = false
    private var detect = false
    private var click: String? = null
    private var from: String? = null
    private var key: String? = null
    private var url: String? = null

    init {
        initSettings()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initSettings() {
        timer = TimeoutRunnable(this)
        empty = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        val setting = settings
        setting.setSupportZoom(true)
        setting.useWideViewPort = true
        setting.domStorageEnabled = true
        setting.javaScriptEnabled = true
        setting.builtInZoomControls = true
        setting.displayZoomControls = false
        setting.loadWithOverviewMode = true
        setting.userAgentString = PlayerSettings.getUa()
        setting.mediaPlaybackRequiresUserGesture = false
        setting.javaScriptCanOpenWindowsAutomatically = false
        setting.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webViewClient = CustomWebViewClient(this)
    }

    open fun start(
        key: String?,
        from: String?,
        headers: Map<String, String>,
        url: String,
        click: String?,
        callback: ParseCallback?,
        detect: Boolean,
    ): CustomWebView {
        SpiderDebug.log(TAG, "key=%s, from=%s, click=%s, url=%s, headers=%s", key, from, click, url, headers)
        App.post(timer, Constant.TIMEOUT_PARSE_WEB)
        callbackRef.set(callback)
        this.detect = detect
        this.click = click
        this.from = from
        this.key = key
        this.url = url
        start(headers)
        return this
    }

    private fun start(headers: Map<String, String>) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        checkHeader(url!!, headers)
        loadUrl(url!!, headers)
    }

    private fun checkHeader(
        url: String,
        headers: Map<String, String>,
    ) {
        for (key in headers.keys) {
            if (HttpHeaders.USER_AGENT.equals(key, ignoreCase = true)) {
                settings.userAgentString = headers[key]
            } else if (HttpHeaders.COOKIE.equals(key, ignoreCase = true)) {
                CookieManager.getInstance().setCookie(url, headers[key])
            }
        }
    }

    private class CustomWebViewClient(
        view: CustomWebView,
    ) : WebViewClient() {
        private val viewRef = WeakReference(view)

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val wv = viewRef.get()
            if (wv == null || wv.stopped) return super.shouldInterceptRequest(view, request)
            val url = request.url.toString()
            val host = request.url.host
            if (BeanUtils.isEmpty(host) || wv.isAd(host!!)) return wv.empty
            val headers = request.requestHeaders
            if (url.contains("/cdn-cgi/challenge-platform/")) wv.uiTasks.post({ wv.showDialog() }, 0)
            if (wv.detect && PLAYER.matcher(url).find() && wv.addUrl(url)) {
                wv.onParseAdd(headers, url)
            } else if (wv.isVideoFormat(url)) {
                wv.onParseSuccess(headers, url)
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageFinished(
            view: WebView,
            url: String,
        ) {
            super.onPageFinished(view, url)
            val wv = viewRef.get() ?: return
            if (wv.stopped || url == BLANK) return
            wv.evaluate(wv.getScript(url), 0)
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            AppLog.w(TAG, "Rejected invalid TLS certificate")
            handler.cancel()
            viewRef.get()?.stop(true)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = false
    }

    private class TimeoutRunnable(
        view: CustomWebView,
    ) : Runnable {
        private val viewRef = WeakReference(view)

        override fun run() {
            viewRef.get()?.stop(true)
        }
    }

    @Synchronized private fun addUrl(url: String): Boolean = !stopped && urls.size < MAX_URLS && urls.add(url)

    private fun showDialog() {
        val activity = App.activeActivity()
        if (stopped || dialog != null || activity == null || activity.isFinishing || activity.isDestroyed) return
        if (parent != null) (parent as ViewGroup).removeView(this)
        dialog = WebDialog.create(activity, this).show()
        App.removeCallbacks(timer)
    }

    private fun hideDialog() {
        dialog?.dismiss()
        dialog = null
    }

    override fun onDismiss(dialog: DialogInterface) {
        stop(true)
    }

    private fun getScript(url: String): List<String> {
        val script = ArrayList(Sniffer.getScript(Uri.parse(url)))
        val click = click
        if (BeanUtils.isEmpty(click) || script.contains(click)) return script
        script.add(0, click!!)
        return script
    }

    private fun evaluate(
        script: List<String>,
        index: Int,
    ) {
        if (stopped || index >= script.size) return
        val js = script[index]
        if (BeanUtils.isEmpty(js)) evaluate(script, index + 1) else evaluateJavascript(js) { evaluate(script, index + 1) }
    }

    private fun isAd(host: String): Boolean {
        for (ad in RuleConfig.get().ads) if (Util.containOrMatch(host, ad)) return true
        return false
    }

    private fun isVideoFormat(url: String): Boolean {
        return try {
            if (!detect && url == this.url) return false
            val spider = VodConfig.get().getSite(key).spider()
            if (spider.manualVideoCheck()) spider.isVideoFormat(url) else Sniffer.isVideoFormat(url)
        } catch (ignored: Exception) {
            Sniffer.isVideoFormat(url)
        }
    }

    private fun onParseAdd(
        headers: Map<String, String>,
        url: String,
    ) {
        uiTasks.post({
            val callback = callbackRef.get()
            if (!stopped && callback != null && children.size < MAX_URLS) {
                val child = create(App.get())
                children.add(child)
                child.start(key, from, headers, url, click, callback, false)
            }
        }, 0)
    }

    private fun onParseSuccess(
        headers: Map<String, String>,
        url: String,
    ) {
        callbackRef.getAndSet(null)?.onParseSuccess(headers, url, from)
        uiTasks.post({ stop(false) }, 0)
    }

    private fun onParseError() {
        callbackRef.getAndSet(null)?.onParseError()
    }

    open fun stop(error: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            App.post { stop(error) }
            return
        }
        if (stopped) return
        stopped = true
        uiTasks.close()
        try {
            if (error) onParseError() else callbackRef.set(null)
        } finally {
            cleanupCommon()
        }
    }

    override fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            App.post { destroy() }
            return
        }
        if (destroyed) return
        destroyed = true
        stop(false)
        callbackRef.set(null)
        webViewClient = WebViewClient()
        webChromeClient = null
        (parent as? ViewGroup)?.removeView(this)
        removeAllViews()
        click = null
        from = null
        key = null
        url = null
        super.destroy()
    }

    private fun cleanupCommon() {
        hideDialog()
        for (child in children) child.destroy()
        children.clear()
        stopLoading()
        loadUrl(BLANK)
        App.removeCallbacks(timer)
        timer = null
        synchronized(this) { urls.clear() }
    }
}
