package com.github.catvod.net

import com.github.catvod.bean.Proxy
import com.github.catvod.utils.Util
import java.io.IOException
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

class OkProxySelector : ProxySelector() {

    @Volatile private var rules: List<Proxy> = emptyList()
    private val system: ProxySelector? = ProxySelector.getDefault()

    @Synchronized
    fun addAll(items: List<Proxy>) {
        items.forEach { it.init() }
        rules = (rules + items).sorted()
    }

    @Synchronized
    fun clear() {
        rules = emptyList()
    }

    private fun fallback(uri: URI): List<java.net.Proxy> =
        system?.select(uri) ?: listOf(java.net.Proxy.NO_PROXY)

    override fun select(uri: URI): List<java.net.Proxy> {
        val snapshot = rules
        val host = uri.host
        if (snapshot.isEmpty() || host == null || host == "127.0.0.1") return fallback(uri)
        if (!isGithubHost(host)) return listOf(java.net.Proxy.NO_PROXY)

        val rule = snapshot.firstOrNull { item ->
            item.getHosts().any { Util.containOrMatch(host, it) }
        }
        return rule?.getProxies()?.takeIf { it.isNotEmpty() } ?: fallback(uri)
    }

    private fun isGithubHost(host: String): Boolean =
        host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com")

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, e: IOException) {
        system?.connectFailed(uri, socketAddress, e)
    }
}
