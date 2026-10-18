package com.wuming.tv.core.runtime.js

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

class EnvironmentHttpProxyTest {

    @Test
    fun selectsSchemeProxyAndHonorsNoProxy() {
        val selector = EnvironmentProxySelector(mapOf(
            "HTTPS_PROXY" to "http://127.0.0.1:7897",
            "HTTP_PROXY" to "http://proxy.example:8080",
            "NO_PROXY" to "localhost,.internal.example",
        ))

        val secure = selector.proxyFor(URI("https://media.example/video"))
        assertEquals(Proxy.Type.HTTP, secure.type())
        assertEquals(7897, (secure.address() as InetSocketAddress).port)
        assertEquals(false, (secure.address() as InetSocketAddress).isUnresolved)
        assertEquals(Proxy.NO_PROXY, selector.proxyFor(URI("https://service.internal.example/video")))
        assertEquals(8080, (selector.proxyFor(URI("http://media.example/video")).address() as InetSocketAddress).port)
    }
}
