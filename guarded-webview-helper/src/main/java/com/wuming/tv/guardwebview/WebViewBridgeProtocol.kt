package com.wuming.tv.guardwebview

import com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes
import org.json.JSONException
import org.json.JSONObject

/**
 * Line-framed JSON protocol between the desktop host's `AdbWebViewEngine` (client) and this
 * on-device helper (server). MUST stay in sync with
 * `guarded-catvod-host/.../webview/WebViewBridgeProtocol.java`.
 */
object WebViewBridgeProtocol : WebViewBridgeTypes {
    val DEFAULT_PORT: Int = WebViewBridgeTypes.DEFAULT_PORT

    @JvmStatic
    fun load(url: String?, headers: MutableMap<String?, String?>?): JSONObject {
        val m: JSONObject = base(WebViewBridgeTypes.T_LOAD)
        put(m, "url", url)
        val h: JSONObject = JSONObject()
        if (headers != null) {
            for (e: MutableMap.MutableEntry<String?, String?> in headers.entries) {
                WebViewBridgeProtocol.put(
                    h,
                    e.key!!,
                    e.value
                )
            }
        }
        put(m, "headers", h)
        return m
    }

    @JvmStatic
    fun pageStarted(url: String?): JSONObject {
        val m: JSONObject = base(WebViewBridgeTypes.T_PAGE_STARTED)
        put(m, "url", url)
        return m
    }

    @JvmStatic
    fun pageFinished(url: String?): JSONObject {
        val m: JSONObject = base(WebViewBridgeTypes.T_PAGE_FINISHED)
        put(m, "url", url)
        return m
    }

    @JvmStatic
    fun loadResource(url: String?): JSONObject {
        val m: JSONObject = base(WebViewBridgeTypes.T_LOAD_RESOURCE)
        put(m, "url", url)
        return m
    }

    @JvmStatic
    fun overrideReq(id: String?, url: String?): JSONObject {
        val m: JSONObject = base(WebViewBridgeTypes.T_OVERRIDE_REQ)
        put(m, "id", id)
        put(m, "url", url)
        return m
    }

    @JvmStatic
    fun overrideResp(id: String?, override: Boolean): JSONObject {
        val m: JSONObject = base(WebViewBridgeTypes.T_OVERRIDE_RESP)
        put(m, "id", id)
        put(m, "override", override)
        return m
    }

    @JvmStatic
    fun interceptReq(id: String?, url: String?, mainFrame: Boolean): JSONObject {
        val m: JSONObject = base(WebViewBridgeTypes.T_INTERCEPT_REQ)
        put(m, "id", id)
        put(m, "url", url)
        put(m, "isMainFrame", mainFrame)
        return m
    }

    @JvmStatic
    fun interceptResp(
        id: String?,
        mime: String?,
        encoding: String?,
        bodyBase64: String?
    ): JSONObject {
        val m: JSONObject = base(WebViewBridgeTypes.T_INTERCEPT_RESP)
        put(m, "id", id)
        put(m, "mime", mime)
        put(m, "encoding", encoding)
        if (bodyBase64 != null) put(m, "body", bodyBase64)
        return m
    }

    @JvmStatic
    fun error(message: String?): JSONObject {
        val m: JSONObject = base(WebViewBridgeTypes.T_ERROR)
        put(m, "message", message)
        return m
    }

    @JvmStatic
    fun bye(): JSONObject = base(WebViewBridgeTypes.T_BYE)

    @JvmStatic
    fun parse(line: String): JSONObject? {
        try {
            return JSONObject(line)
        } catch (e: JSONException) {
            return null
        }
    }

    @JvmStatic
    fun type(m: JSONObject?): String = if (m != null && m.has("t")) m.optString("t") else ""

    @JvmStatic
    fun str(m: JSONObject?, key: String?): String =
        if (m != null && m.has(key)) m.optString(key) else ""

    @JvmStatic
    fun bool(m: JSONObject?, key: String?): Boolean = m != null && m.has(key) && m.optBoolean(key)

    private fun base(type: String?): JSONObject {
        val m: JSONObject = JSONObject()
        put(m, "t", type)
        return m
    }

    private fun put(target: JSONObject, key: String, value: Any?) {
        try {
            target.put(key, value)
        } catch (ignored: JSONException) {
            // primitive values never fail; keep the protocol resilient
        }
    }
}
