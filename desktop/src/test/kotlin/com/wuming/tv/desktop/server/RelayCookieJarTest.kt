package com.wuming.tv.desktop.server

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class RelayCookieJarTest {
    @Test fun retainsProvidedAndResponseCookiesWithinExactOrigin() {
        val origin = "https://one.example.org/master.m3u8".toHttpUrl()
        val jar = RelayCookieJar(origin, mapOf("Cookie" to "auth=provided"))
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "session=fresh; Domain=example.org; Path=/")!!))
        assertEquals(setOf("auth", "session"), jar.loadForRequest(origin.resolve("segment.ts")!!).map { it.name }.toSet())
        assertTrue(jar.loadForRequest("https://two.example.org/segment.ts".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://one.example.org/segment.ts".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://one.example.org:444/segment.ts".toHttpUrl()).isEmpty())
        assertTrue(RelayCookieJar(origin, emptyMap()).loadForRequest(origin).isEmpty())
    }
    @Test fun respectsPathExpiryAndCookieReplacement() {
        val origin = "https://one.example.org/master.m3u8".toHttpUrl()
        val jar = RelayCookieJar(origin, emptyMap())
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "key=first; Path=/keys; Secure")!!))
        assertTrue(jar.loadForRequest(origin.resolve("segment.ts")!!).isEmpty())
        assertEquals("first", jar.loadForRequest(origin.resolve("/keys/one.bin")!!).single().value)
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "key=second; Path=/keys; Secure")!!))
        assertEquals("second", jar.loadForRequest(origin.resolve("/keys/one.bin")!!).single().value)
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "key=gone; Path=/keys; Max-Age=0")!!))
        assertTrue(jar.loadForRequest(origin.resolve("/keys/one.bin")!!).isEmpty())
    }
}
