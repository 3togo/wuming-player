package com.wuming.tv.core.runtime.guardspider

/**
 * Shared type constants for the WebView bridge protocol spoken between
 * the desktop host's AdbWebViewEngine (client) and the on-device WebView
 * helper (server).
 *
 * Both [guarded-catvod-host] and [guarded-webview-helper] modules must use
 * these constants to guarantee the protocol stays in sync without manual
 * duplication. The JSON library choice (Gson vs org.json) remains
 * module-specific; only the type identifiers are shared here.
 */
interface WebViewBridgeTypes {
    companion object {
        const val T_LOAD = "load"
        const val T_PAGE_STARTED = "page_started"
        const val T_PAGE_FINISHED = "page_finished"
        const val T_LOAD_RESOURCE = "load_resource"
        const val T_OVERRIDE_REQ = "override_req"
        const val T_OVERRIDE_RESP = "override_resp"
        const val T_INTERCEPT_REQ = "intercept_req"
        const val T_INTERCEPT_RESP = "intercept_resp"
        const val T_ERROR = "error"
        const val T_BYE = "bye"

        const val DEFAULT_PORT = 8765
    }
}
