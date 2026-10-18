package com.wuming.tv.desktop.catalog

import com.wuming.tv.core.maccms.MacCmsTransport
import com.wuming.tv.core.catalog.RemoteSpiderTransport
import com.wuming.tv.desktop.net.DesktopResourceLoader
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** OkHttp-backed transport for `type` 0/1 collection APIs, keeping the core library HTTP-free. */
class MacCmsHttpTransport(
    private val loader: DesktopResourceLoader = DesktopResourceLoader(),
) : MacCmsTransport, RemoteSpiderTransport {

    override fun get(api: String, headers: Map<String, String>, params: Map<String, String>): String =
        loader.load(url(api, params), headers).toString(StandardCharsets.UTF_8)

    private fun url(api: String, params: Map<String, String>): String {
        if (params.isEmpty()) return api
        val query = params.entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return api + (if (api.contains('?')) '&' else '?') + query
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
