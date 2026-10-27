package com.wuming.tv.core.libvio

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

interface LibvioTransport {
    fun get(url: String, headers: Map<String, String>): String
    fun setCookie(url: String, cookie: String)
    fun cancel() = Unit
}

/** The platforms supply their client configuration; this adapter owns a separate, bounded site session. */
class LibvioHttpTransport internal constructor(client: OkHttpClient, private val allowed: (String) -> Boolean) : LibvioTransport {
    constructor(client: OkHttpClient) : this(client, LibvioSiteSpec::isSiteUrl)
    private val cookies = ConcurrentHashMap<String, Cookie>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) {
            values.forEach { cookie ->
                if (cookies.size < 64 || cookies.containsKey(key(cookie))) cookies[key(cookie)] = cookie
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            cookies.entries.removeIf { it.value.expiresAt < now }
            return cookies.values.filter { it.matches(url) }.sortedByDescending { it.path.length }
        }
    }
    private val http = client.newBuilder().cookieJar(jar).followRedirects(false).followSslRedirects(false).build()
    override fun setCookie(url: String, cookie: String) {
        val target = url.toHttpUrl()
        jar.saveFromResponse(target, listOf(requireNotNull(Cookie.parse(target, "$cookie; Path=/" + if (target.isHttps) "; Secure" else ""))))
    }
    override fun get(url: String, headers: Map<String, String>): String {
        var target = url
        repeat(6) {
            require(allowed(target)) { "Libvio redirected outside its supported site" }
            val request = Request.Builder().url(target).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            val call = http.newCall(request)
            calls.add(call)
            try {
                call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        target = requireNotNull(response.header("Location")?.let { request.url.resolve(it)?.toString() })
                    } else {
                        val output = ByteArrayOutputStream()
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 2 * 1024 * 1024) { "Libvio response exceeds size limit" }
                                output.write(buffer, 0, count)
                            }
                        }
                        val body = output.toString(Charsets.UTF_8.name())
                        require(response.isSuccessful || "var POW =" in body) { "Libvio HTTP ${response.code}" }
                        return body
                    }
                }
            } finally { calls.remove(call) }
        }
        error("Libvio exceeded redirect limit")
    }
    override fun cancel() { calls.forEach(Call::cancel) }
    private fun key(cookie: Cookie) = "${cookie.domain}|${cookie.path}|${cookie.name}"
}
