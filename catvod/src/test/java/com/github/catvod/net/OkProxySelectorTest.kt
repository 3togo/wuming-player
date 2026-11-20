package com.github.catvod.net

import com.github.catvod.bean.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy as NetworkProxy
import java.net.URI

class OkProxySelectorTest {
    @Test fun retainsGithubOnlyRoutingAcrossRuleChanges() {
        val proxy = NetworkProxy(NetworkProxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080))
        val rule = object : Proxy() {
            override fun init() = Unit
            override fun getHosts() = mutableListOf("github.com")
            override fun getProxies() = mutableListOf(proxy)
        }
        val selector = OkProxySelector()
        val github = URI("https://github.com/example/project")
        val original = selector.select(github)
        selector.addAll(listOf(rule))
        assertEquals(listOf(proxy), selector.select(github))
        assertEquals(listOf(NetworkProxy.NO_PROXY), selector.select(URI("https://github.com.example.org/")))
        selector.clear()
        assertEquals(original, selector.select(github))
    }
}
