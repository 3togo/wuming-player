package com.wuming.android.tv.server.process

import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.server.Nano
import com.wuming.android.tv.server.impl.Process
import com.github.catvod.utils.Prefers
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response

open class Cache : Process {
    override fun isRequest(
        session: IHTTPSession,
        url: String,
    ): Boolean = url.startsWith("/cache")

    private fun getKey(
        rule: String?,
        key: String?,
    ): String = "cache_" + (if (BeanUtils.isEmpty(rule)) "" else rule + "_") + key

    @Suppress("DEPRECATION")
    override fun doResponse(
        session: IHTTPSession,
        url: String,
        files: Map<String, String>,
    ): Response {
        val params = session.parms
        val action = params["do"]
        val rule = params["rule"]
        val key = params["key"]
        if ("get" == action) return Nano.ok(Prefers.getString(getKey(rule, key)))
        if ("set" == action) Prefers.put(getKey(rule, key), params["value"])
        if ("del" == action) Prefers.remove(getKey(rule, key))
        return Nano.ok()
    }
}
