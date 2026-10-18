package com.wuming.tv.guardhost.webview

import android.webkit.SimpleWebResourceRequest
import android.webkit.WebResourceRequest
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Desktop WebView backend backed by Java Chromium Embedded (JCEF). JCEF is loaded entirely via
 * reflection so the host module compiles and runs without JCEF on the classpath; the engine is only
 * active at runtime when `org.cef.JCefApp` is present. Any initialisation or load failure throws
 * [WebViewRequiresEmulatorException], handing the provider back to the emulator fallback.
 */
open class JcefWebViewEngine
@Throws(WebViewRequiresEmulatorException::class)
constructor() :
    WebViewEngine {
    private val lock: Any = Any()
    private var cefApp: Any? = null
    private var client: Any? = null

    @Volatile
    private var currentBridge: WebViewClientBridge? = null

    init {
        try {
            Class.forName(CEF_APP)
        } catch (t: Throwable) {
            throw WebViewRequiresEmulatorException("JCEF not on classpath", t)
        }
    }

    @Throws(WebViewRequiresEmulatorException::class)
    private fun ensureInitialised() {
        synchronized(lock) {
            if (cefApp != null) return
            try {
                val appCls: Class<*> = Class.forName(CEF_APP)
                val getInstance: Method = appCls.getMethod("getInstance", Array<String>::class.java)
                val app: Any = getInstance.invoke(
                    null,
                    arrayOf<String>(
                        "--headless",
                        "--disable-gpu",
                        "--no-sandbox",
                        "--disable-software-rasterizer",
                        "--disable-dev-shm-usage"
                    ) as Any
                )
                val createClient: Method = app.javaClass.getMethod("createClient")
                val cli: Any = createClient.invoke(app)

                val requestHandler: Class<*> = Class.forName("org.cef.handler.CefRequestHandler")
                val requestProxy: Any? = Proxy.newProxyInstance(
                    requestHandler.getClassLoader(),
                    arrayOf<Class<*>?>(requestHandler),
                    RequestHandlerInvocation()
                )
                cli.javaClass.getMethod("addRequestHandler", requestHandler)
                    .invoke(cli, requestProxy)

                val loadHandler: Class<*> = Class.forName("org.cef.handler.CefLoadHandler")
                val loadProxy: Any? = Proxy.newProxyInstance(
                    loadHandler.getClassLoader(),
                    arrayOf<Class<*>?>(loadHandler),
                    LoadHandlerInvocation()
                )
                cli.javaClass.getMethod("addLoadHandler", loadHandler).invoke(cli, loadProxy)

                this.cefApp = app
                this.client = cli
            } catch (t: Throwable) {
                throw WebViewRequiresEmulatorException("Failed to initialise JCEF", t)
            }
        }
    }

    private inner class RequestHandlerInvocation : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val name: String? = method.getName()
            if ("onBeforeResourceLoad".equals(name) && args != null && args.size >= 2 &&
                currentBridge != null
            ) {
                try {
                    val cefRequest: Any = args[1]!!
                    val url: String? =
                        cefRequest.javaClass.getMethod("getURL").invoke(cefRequest) as String?
                    val request: WebResourceRequest = SimpleWebResourceRequest(url, null)
                    currentBridge!!.onLoadResource(url)
                    if (currentBridge!!.shouldOverrideUrlLoading(request)) return true
                } catch (ignored: Throwable) {
                    // fall through to default
                }
            }
            return defaultReturn(method)
        }
    }

    private inner class LoadHandlerInvocation : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            if ("onLoadEnd".equals(method.getName()) && currentBridge != null) {
                currentBridge!!.onPageFinished("")
            }
            return defaultReturn(method)
        }
    }

    @Throws(WebViewRequiresEmulatorException::class)
    override fun loadUrl(
        url: String?,
        extraHeaders: MutableMap<String?, String?>?,
        bridge: WebViewClientBridge?
    ) {
        ensureInitialised()
        this.currentBridge = bridge
        try {
            val createBrowser: Method = client!!.javaClass.getMethod(
                "createBrowser",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            createBrowser.invoke(client, url, false, false)
        } catch (t: Throwable) {
            throw WebViewRequiresEmulatorException("JCEF loadUrl failed for " + url, t)
        } finally {
            this.currentBridge = null
        }
    }

    override fun destroy() {
        synchronized(lock) {
            try {
                if (cefApp != null) cefApp!!.javaClass.getMethod("dispose").invoke(cefApp)
            } catch (ignored: Throwable) {
                // best effort
            }
        }
    }

    companion object {
        private val CEF_APP = "org.cef.JCefApp"

        private fun defaultReturn(method: Method): Any? {
            val rt: Class<*> = method.getReturnType()
            if (rt.equals(Boolean::class.javaPrimitiveType)) return false
            if (rt.equals(Int::class.javaPrimitiveType)) return 0
            if (rt.equals(Long::class.javaPrimitiveType)) return 0L
            if (rt.equals(Float::class.javaPrimitiveType)) return 0f
            if (rt.equals(Double::class.javaPrimitiveType)) return 0.0
            return null
        }
    }
}
