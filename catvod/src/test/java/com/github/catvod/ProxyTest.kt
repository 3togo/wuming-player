package com.github.catvod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyTest {

    @Test
    fun `default port is negative one`() {
        assertEquals(-1, Proxy.getPort())
    }

    @Test
    fun `set and get port`() {
        Proxy.set(8080)
        assertEquals(8080, Proxy.getPort())
        Proxy.set(-1)
    }

    @Test
    fun `getUrl local uses 127 dot 0 dot 0 dot 1`() {
        Proxy.set(9999)
        val url = Proxy.getUrl(true)
        assertTrue(url.startsWith("http://127.0.0.1:9999/proxy"))
        Proxy.set(-1)
    }

    @Test
    fun `getUrl local contains proxy path`() {
        Proxy.set(12345)
        val url = Proxy.getUrl(true)
        assertTrue(url.endsWith("/proxy"))
        Proxy.set(-1)
    }

    @Test
    fun `set negative one resets port`() {
        Proxy.set(5555)
        assertEquals(5555, Proxy.getPort())
        Proxy.set(-1)
        assertEquals(-1, Proxy.getPort())
    }
}
