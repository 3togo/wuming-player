package com.wuming.tv.core.hongguo

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

fun interface HongGuoTransport {
    fun get(url: String, headers: Map<String, String>): String
    fun cancel() = Unit
}

/** Bounded website requests; the platforms supply routing and TLS configuration. */
class HongGuoHttpTransport internal constructor(client: OkHttpClient, private val allowed: (String) -> Boolean) : HongGuoTransport {
    constructor(client: OkHttpClient) : this(client, HongGuoSiteSpec::isSiteUrl)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(minOf(client.callTimeoutMillis.takeIf { it > 0 } ?: 30_000, 30_000).toLong(), TimeUnit.MILLISECONDS).build()

    override fun get(url: String, headers: Map<String, String>): String {
        var target = url
        repeat(6) {
            require(allowed(target)) { "HongGuo request left the supported website" }
            val request = Request.Builder().url(target).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            val call = http.newCall(request)
            calls.add(call)
            try {
                call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        target = requireNotNull(response.header("Location")?.let { request.url.resolve(it)?.toString() })
                    } else {
                        require(response.isSuccessful) { "HongGuo HTTP ${response.code}" }
                        val output = ByteArrayOutputStream()
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 2 * 1024 * 1024) { "HongGuo page exceeds size limit" }
                                output.write(buffer, 0, count)
                            }
                        }
                        return output.toString(Charsets.UTF_8.name())
                    }
                }
            } finally { calls.remove(call) }
        }
        error("HongGuo exceeded redirect limit")
    }

    override fun cancel() { calls.forEach(Call::cancel) }
}
