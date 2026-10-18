package com.wuming.tv.guardhost.webview

import android.webkit.SimpleWebResourceRequest
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop WebView backend that renders on a real Android WebView running in the `guarded-webview-helper` app, reached through an `adb forward` TCP tunnel. The host process is
 * launched with `-Dtvbox.guarded.webview.adbSerial=<serial>` (set by the desktop from
 * `tvbox.guarded.emulator.serial`); the rendering therefore only engages when an emulator/device
 * is wired up, keeping the emulator as a fallback behind desktop JCEF.
 *
 *
 * The engine opens one socket per `WebView`, sends `load`, and forwards the device's
 * WebViewClient callbacks to the provider's [WebViewClientBridge]. Synchronous provider queries
 * (`shouldOverrideUrlLoading`, `shouldInterceptRequest`) are answered locally from the
 * device's forwarded request and the result is sent back.
 */
open class AdbWebViewEngine
@Throws(WebViewRequiresEmulatorException::class)
constructor() :
    WebViewEngine {
    private val host = "127.0.0.1"
    private val port: Int
    private val socket: Socket
    private val `in`: BufferedReader
    private val out: PrintWriter
    private val reader: Thread
    private val closed: AtomicBoolean = AtomicBoolean(false)

    @Volatile
    private var currentBridge: WebViewClientBridge? = null

    init {
        this.port = portFromProperty()
        try {
            this.socket = Socket(host, port)
            this.`in` =
                BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            this.out = PrintWriter(
                OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                true
            )
        } catch (e: Exception) {
            throw WebViewRequiresEmulatorException(
                "Cannot reach WebView helper on " + host + ":" + port + " (is the device wired up?)",
                e
            )
        }
        this.reader = Thread({ this.readLoop() }, "guarded-webview-adb-client")
        this.reader.setDaemon(true)
        this.reader.start()
    }

    override fun loadUrl(
        url: String?,
        extraHeaders: MutableMap<String?, String?>?,
        bridge: WebViewClientBridge?
    ) {
        this.currentBridge = bridge
        send(WebViewBridgeProtocol.load(url, extraHeaders))
    }

    private fun readLoop() {
        try {
            var line: String? = null
            while (!closed.get() && (`in`.readLine().also { line = it }) != null) {
                val message: JsonObject? = WebViewBridgeProtocol.parse(line)
                if (message != null) dispatch(message)
            }
        } catch (ignored: Exception) {
            // connection closed by the device helper
        }
    }

    private fun dispatch(message: JsonObject?) {
        val type: String? = WebViewBridgeProtocol.type(message)
        val bridge: WebViewClientBridge? = currentBridge
        when (type) {
            com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_PAGE_STARTED -> {
                if (bridge != null) bridge.onPageStarted(WebViewBridgeProtocol.str(message, "url"))
            }

            com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_PAGE_FINISHED -> {
                if (bridge != null) bridge.onPageFinished(WebViewBridgeProtocol.str(message, "url"))
            }

            com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_LOAD_RESOURCE -> {
                if (bridge != null) bridge.onLoadResource(WebViewBridgeProtocol.str(message, "url"))
            }

            com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_OVERRIDE_REQ -> {
                val override: Boolean = bridge != null && bridge.shouldOverrideUrlLoading(
                    SimpleWebResourceRequest(WebViewBridgeProtocol.str(message, "url"), null)
                )
                send(
                    WebViewBridgeProtocol.overrideResp(
                        WebViewBridgeProtocol.str(message, "id"),
                        override
                    )
                )
            }

            com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_INTERCEPT_REQ -> {
                val request: WebResourceRequest = SimpleWebResourceRequest(
                    WebViewBridgeProtocol.str(message, "url"),
                    null
                )
                val response: WebResourceResponse? =
                    if (bridge != null) bridge.shouldInterceptRequest(request) else null
                send(
                    WebViewBridgeProtocol.interceptResp(
                        WebViewBridgeProtocol.str(message, "id"),
                        if (response != null) response.getMimeType() else null,
                        if (response != null) response.getEncoding() else null,
                        if (response != null) base64(response.getData()) else null
                    )
                )
            }

            com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes.T_ERROR -> {
                // The device helper reports an unrecoverable load error; nothing to surface here.
            }

            else -> {}
        }
    }

    @Synchronized
    private fun send(message: JsonObject?) {
        if (closed.get()) return
        out.println(WebViewBridgeProtocol.render(message))
    }

    override fun destroy() {
        if (!closed.compareAndSet(false, true)) return
        send(WebViewBridgeProtocol.bye())
        try {
            reader.interrupt()
        } catch (ignored: Exception) {
            // best effort
        }
        try {
            socket.close()
        } catch (ignored: Exception) {
            // best effort
        }
    }

    companion object {
        const val DEFAULT_PORT: Int = 8765

        private fun portFromProperty(): Int {
            val p: String? = System.getProperty("tvbox.guarded.webview.adbPort")
            if (p != null) {
                try {
                    return Integer.parseInt(p)
                } catch (ignored: NumberFormatException) {
                    // fall through to default
                }
            }
            return DEFAULT_PORT
        }

        private fun base64(data: java.io.InputStream?): String? {
            if (data == null) return null
            try {
                return java.util.Base64.getEncoder().encodeToString(data.readAllBytes())
            } catch (ignored: Exception) {
                return null
            }
        }
    }
}
