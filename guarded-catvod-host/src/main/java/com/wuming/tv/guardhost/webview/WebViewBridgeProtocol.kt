package com.wuming.tv.guardhost.webview

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.wuming.tv.core.runtime.guardspider.WebViewBridgeTypes

/**
 * Line-framed JSON protocol spoken between the desktop host's [AdbWebViewEngine] (client) and
 * the on-device WebView helper (server). The device-side copy in the `guarded-webview-helper`
 * module MUST stay in sync with these type constants.
 */
object WebViewBridgeProtocol : WebViewBridgeTypes {
    private val GSON = Gson()

    @JvmStatic
    fun load(url: String?, headers: MutableMap<String?, String?>?): JsonObject {
        val m: JsonObject = base(WebViewBridgeTypes.T_LOAD)
        m.addProperty("url", url)
        val h: JsonObject = JsonObject()
        if (headers != null) headers.forEach(h::addProperty)
        m.add("headers", h)
        return m
    }

    @JvmStatic
    fun pageStarted(url: String?): JsonObject {
        val m: JsonObject = base(WebViewBridgeTypes.T_PAGE_STARTED)
        m.addProperty("url", url)
        return m
    }

    @JvmStatic
    fun pageFinished(url: String?): JsonObject {
        val m: JsonObject = base(WebViewBridgeTypes.T_PAGE_FINISHED)
        m.addProperty("url", url)
        return m
    }

    @JvmStatic
    fun loadResource(url: String?): JsonObject {
        val m: JsonObject = base(WebViewBridgeTypes.T_LOAD_RESOURCE)
        m.addProperty("url", url)
        return m
    }

    @JvmStatic
    fun overrideReq(id: String?, url: String?): JsonObject {
        val m: JsonObject = base(WebViewBridgeTypes.T_OVERRIDE_REQ)
        m.addProperty("id", id)
        m.addProperty("url", url)
        return m
    }

    @JvmStatic
    fun overrideResp(id: String?, override: Boolean): JsonObject {
        val m: JsonObject = base(WebViewBridgeTypes.T_OVERRIDE_RESP)
        m.addProperty("id", id)
        m.addProperty("override", override)
        return m
    }

    @JvmStatic
    fun interceptReq(id: String?, url: String?, mainFrame: Boolean): JsonObject {
        val m: JsonObject = base(WebViewBridgeTypes.T_INTERCEPT_REQ)
        m.addProperty("id", id)
        m.addProperty("url", url)
        m.addProperty("isMainFrame", mainFrame)
        return m
    }

    @JvmStatic
    fun interceptResp(
        id: String?,
        mime: String?,
        encoding: String?,
        bodyBase64: String?
    ): JsonObject {
        val m: JsonObject = base(WebViewBridgeTypes.T_INTERCEPT_RESP)
        m.addProperty("id", id)
        m.addProperty("mime", mime)
        m.addProperty("encoding", encoding)
        if (bodyBase64 != null) m.addProperty("body", bodyBase64)
        return m
    }

    @JvmStatic
    fun error(message: String?): JsonObject {
        val m: JsonObject = base(WebViewBridgeTypes.T_ERROR)
        m.addProperty("message", message)
        return m
    }

    @JvmStatic
    fun bye(): JsonObject = base(WebViewBridgeTypes.T_BYE)

    @JvmStatic
    fun parse(line: String?): JsonObject? = GSON.fromJson<JsonObject?>(line, JsonObject::class.java)

    @JvmStatic
    fun render(message: JsonObject?): String? = GSON.toJson(message)

    @JvmStatic
    fun type(message: JsonObject?): String? =
        if (message != null && message.has("t")) message.get("t").getAsString() else ""

    @JvmStatic
    fun str(message: JsonObject?, key: String?): String? =
        if (message != null && message.has(key)) message.get(key).getAsString() else ""

    @JvmStatic
    fun bool(message: JsonObject?, key: String?): Boolean =
        message != null && message.has(key) && message.get(key).getAsBoolean()

    private fun base(type: String?): JsonObject {
        val m: JsonObject = JsonObject()
        m.addProperty("t", type)
        return m
    }
}
