package com.wuming.tv.guardhost.webview

/**
 * Selects the WebView backend. By default it prefers JCEF (when present on the runtime classpath) and
 * otherwise degrades to the emulator-fallback engine. Tests may inject a custom engine.
 */
object WebViewEngineFactory {
    private var instance: WebViewEngine? = null

    @Synchronized
    @JvmStatic
    fun get(): WebViewEngine {
        if (instance == null) instance = createDefault()
        return instance!!
    }

    @Synchronized
    @JvmStatic
    fun set(engine: WebViewEngine?) {
        instance = engine
    }

    @Synchronized
    @JvmStatic
    fun reset() {
        instance = null
    }

    private fun createDefault(): WebViewEngine {
        if (System.getProperty("tvbox.guarded.webview.adbSerial") != null) {
            try {
                return AdbWebViewEngine()
            } catch (e: WebViewRequiresEmulatorException) {
                return EmulatorFallbackWebViewEngine()
            }
        }
        try {
            return JcefWebViewEngine()
        } catch (e: WebViewRequiresEmulatorException) {
            return EmulatorFallbackWebViewEngine()
        }
    }
}
