package com.wuming.tv.desktop.server

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Ephemeral upstream cookies stay with one stream and one exact origin. */
internal class RelayCookieJar(origin: HttpUrl, headers: Map<String, String>) : CookieJar {
    private val origins = LinkedHashMap<String, MutableList<Cookie>>()
    init {
        val supplied = headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value.orEmpty()
        saveFromResponse(origin, supplied.split(';').mapNotNull { Cookie.parse(origin, it.trim() + "; Path=/") })
    }
    private fun key(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val values = origins.getOrPut(key(url)) { mutableListOf() }
        cookies.forEach { cookie ->
            values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis() && cookie.name.length + cookie.value.length <= 8192) values.add(cookie)
        }
        while (origins.size > 16 || origins.values.sumOf { it.size } > 128 ||
            origins.values.flatten().sumOf { it.name.length + it.value.length } > 16384) {
            val eldest = origins.entries.first()
            if (eldest.value.isNotEmpty()) eldest.value.removeAt(0)
            if (eldest.value.isEmpty()) origins.remove(eldest.key)
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val values = origins[key(url)] ?: return emptyList()
        values.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return values.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }
}
