package com.wuming.tv.guardwebview

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.Volatile
import org.json.JSONObject

/**
 * Foreground service that exposes a real Android [WebView] to the desktop guarded host over an
 * `adb forward` TCP tunnel. The host's `AdbWebViewEngine` connects to `127.0.0.1:8765`
 * (forwarded to the device) and drives `loadUrl`; this service relays the WebViewClient callbacks
 * back so a provider can derive its playback URL on actual Android WebView code.
 */
open class WebViewHelperService : Service() {
    private var server: ServerSocket? = null
    private var webViewThread: HandlerThread? = null
    private var webViewHandler: Handler? = null

    @Volatile
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("guardwebview-looper")
        webViewThread = thread
        thread.start()
        webViewHandler = Handler(thread.looper)
        startForegroundNotification()
        try {
            server = ServerSocket(PORT)
        } catch (e: IOException) {
            stopSelf()
            return
        }
        Thread({ this.acceptLoop() }, "guardwebview-accept").start()
    }

    private fun acceptLoop() {
        val currentServer = server
        while (!stopped && currentServer != null && !currentServer.isClosed()) {
            try {
                val socket: Socket = currentServer.accept()
                Connection(socket).start()
            } catch (e: IOException) {
                break
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ bounds dataSync foreground-service time for targets 35+.
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        try {
            server?.close()
        } catch (ignored: IOException) {
            // best effort
        }
        webViewThread?.quitSafely()
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val nm: NotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel: NotificationChannel = NotificationChannel(
                CHANNEL_ID,
                "WebView Helper",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WebView Helper")
            .setContentText("Rendering WebViews for the desktop guarded host")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** One per desktop connection; owns a single WebView created on the shared WebView Looper.  */
    private inner class Connection(private val socket: Socket) : Thread() {
        private val `in`: BufferedReader
        private val out: PrintWriter
        private val pending: ConcurrentHashMap<String?, Pending?> =
            ConcurrentHashMap<String?, Pending?>()

        @Volatile
        private var webView: WebView? = null

        init {
            `in` =
                BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            out = PrintWriter(
                OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                true
            )
        }

        override fun run() {
            try {
                var line: String? = null
                while (!stopped && (`in`.readLine().also { line = it }) != null) {
                    val currentLine = line ?: continue
                    val message: JSONObject? = WebViewBridgeProtocol.parse(currentLine)
                    if (message != null) dispatch(message)
                }
            } catch (ignored: IOException) {
                // connection closed
            } finally {
                closeQuietly()
            }
        }

        fun dispatch(message: JSONObject) {
            when (WebViewBridgeProtocol.type(message)) {
                com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_LOAD -> handleLoad(message)

                com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_OVERRIDE_RESP -> complete(
                    message,
                    false
                )

                com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_INTERCEPT_RESP -> complete(
                    message,
                    true
                )

                com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_BYE -> closeQuietly()

                else -> {}
            }
        }

        fun handleLoad(message: JSONObject) {
            val url: String = WebViewBridgeProtocol.str(message, "url")
            val headers: MutableMap<String?, String?> = parseHeaders(message)
            val handler = webViewHandler ?: return
            handler.post(
                Runnable {
                    if (webView == null) {
                        val wv = WebView(this@WebViewHelperService)
                        webView = wv
                        wv.setWebViewClient(BridgeClient())
                        wv.getSettings().setJavaScriptEnabled(true)
                        wv.getSettings().setDomStorageEnabled(true)
                        wv.getSettings().setLoadWithOverviewMode(true)
                    }
                    webView?.loadUrl(url, headers)
                }
            )
        }

        fun send(message: JSONObject) {
            synchronized(out) {
                out.println(message.toString())
            }
        }

        fun requestOverride(url: String?): Boolean {
            val id: String = nextId()
            val p: Pending = Pending()
            pending.put(id, p)
            send(WebViewBridgeProtocol.overrideReq(id, url))
            await(p)
            val removed: Pending? = pending.remove(id)
            return removed != null && removed.boolValue
        }

        fun requestIntercept(url: String?, mainFrame: Boolean): WebResourceResponse? {
            val id: String = nextId()
            val p: Pending = Pending()
            pending.put(id, p)
            send(WebViewBridgeProtocol.interceptReq(id, url, mainFrame))
            await(p)
            val removed: Pending? = pending.remove(id)
            if (removed == null || !removed.hasBody) return null
            val data: ByteArray? = if (removed.body != null) {
                Base64.decode(removed.body, Base64.DEFAULT)
            } else {
                ByteArray(0)
            }
            return WebResourceResponse(removed.mime, removed.encoding, ByteArrayInputStream(data))
        }

        fun emit(type: String?, url: String?) {
            if (com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_PAGE_STARTED == type) {
                send(
                    WebViewBridgeProtocol.pageStarted(
                        url
                    )
                )
            } else if (com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_PAGE_FINISHED ==
                type
            ) {
                send(
                    WebViewBridgeProtocol.pageFinished(
                        url
                    )
                )
            } else if (com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_LOAD_RESOURCE ==
                type
            ) {
                send(
                    WebViewBridgeProtocol.loadResource(
                        url
                    )
                )
            }
        }

        fun complete(message: JSONObject, intercept: Boolean) {
            val p: Pending? = pending.get(WebViewBridgeProtocol.str(message, "id"))
            if (p == null) return
            if (intercept) {
                p.mime = WebViewBridgeProtocol.str(message, "mime")
                p.encoding = WebViewBridgeProtocol.str(message, "encoding")
                p.body = if (message.has("body")) message.optString("body") else null
                p.hasBody = true
            } else {
                p.boolValue = WebViewBridgeProtocol.bool(message, "override")
            }
            p.latch.countDown()
        }

        fun await(p: Pending) {
            try {
                p.latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (ignored: InterruptedException) {
                // treat as timeout: return default
            }
        }

        fun closeQuietly() {
            val wv = webView
            webView = null
            val handler = webViewHandler
            if (wv != null && handler != null) {
                handler.post {
                    try {
                        wv.stopLoading()
                        wv.removeJavascriptInterface("AndroidBridge")
                        wv.setWebViewClient(WebViewClient())
                        wv.destroy()
                    } catch (ignored: Throwable) {
                    }
                }
            }
            pending.clear()
            try {
                socket.close()
            } catch (ignored: IOException) {
                // best effort
            }
        }

        /** Forwards the device WebView's client callbacks to the desktop host.  */
        private inner class BridgeClient : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                emit(com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_PAGE_STARTED, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                emit(com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_PAGE_FINISHED, url)
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                emit(com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_LOAD_RESOURCE, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest
            ): Boolean {
                val url: String? =
                    if (request.getUrl() != null) request.getUrl().toString() else null
                return requestOverride(url)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url: String? =
                    if (request.getUrl() != null) request.getUrl().toString() else null
                return requestIntercept(url, request.isForMainFrame())
            }
        }

        private fun nextId(): String = ID_GENERATOR.incrementAndGet().toString()

        private fun parseHeaders(message: JSONObject): MutableMap<String?, String?> {
            val map: MutableMap<String?, String?> = HashMap<String?, String?>()
            if (message.has("headers")) {
                val headers: JSONObject? = message.optJSONObject("headers")
                if (headers != null) {
                    val keys: MutableIterator<String?> = headers.keys()
                    while (keys.hasNext()) {
                        val key: String? = keys.next()
                        map.put(key, headers.optString(key))
                    }
                }
            }
            return map
        }
    }

    private class Pending {
        val latch: CountDownLatch = CountDownLatch(1)

        @Volatile
        var boolValue: Boolean = false

        @Volatile
        var hasBody: Boolean = false

        @Volatile
        var mime: String? = null

        @Volatile
        var encoding: String? = null

        @Volatile
        var body: String? = null
    }

    companion object {
        private val PORT = WebViewBridgeProtocol.DEFAULT_PORT
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "guardwebview"
        private const val TIMEOUT_SECONDS: Long = 30
        private val ID_GENERATOR = AtomicLong()
    }
}
