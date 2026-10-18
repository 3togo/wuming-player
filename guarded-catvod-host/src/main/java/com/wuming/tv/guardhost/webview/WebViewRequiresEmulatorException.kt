package com.wuming.tv.guardhost.webview

/**
 * Thrown by a [WebViewEngine] when it cannot render a URL on the desktop (no JCEF on the
 * classpath, or JCEF failed to initialise). The desktop orchestration treats this as a signal to
 * route the provider to an Android emulator/device instead, keeping the emulator as a fallback.
 */
open class WebViewRequiresEmulatorException : RuntimeException {
    constructor(message: String?) : super(MARKER.toString() + ": " + message)

    constructor(
        message: String?,
        cause: Throwable?
    ) : super(MARKER.toString() + ": " + message, cause)

    companion object {
        @JvmField val MARKER: String = "WEBVIEW_REQUIRES_EMULATOR"

        @JvmStatic
        fun isEmulatorRequired(throwable: Throwable?): Boolean {
            var current: Throwable? = throwable
            while (current != null) {
                if (current is WebViewRequiresEmulatorException) return true
                current = current.cause
            }
            return false
        }
    }
}
