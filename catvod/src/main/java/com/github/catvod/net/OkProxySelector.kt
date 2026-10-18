package com.github.catvod.net

import com.github.catvod.bean.Proxy
import com.github.catvod.utils.Util
import android.util.Log
import java.io.IOException
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

class OkProxySelector : ProxySelector() {

    private val proxy = CopyOnWriteArrayList<Proxy>()
    private val system: ProxySelector? = ProxySelector.getDefault()

    @Synchronized
    fun addAll(items: List<Proxy>) {
        items.forEach { it.init() }
        val newList = (proxy + items).sorted()
        proxy.clear()
        proxy.addAll(newList)
    }

    fun clear() {
        proxy.clear()
    }

    private fun fallback(uri: URI): List<java.net.Proxy> {
        val result = if (system != null) system.select(uri) else listOf(java.net.Proxy.NO_PROXY)
        Log.d("OkProxySelector", "fallback() for " + uri.host + " -> " + result)
        return result
    }

    override fun select(uri: URI): List<java.net.Proxy> {
        val host = uri.host
        Log.d("OkProxySelector", "=== select() called ===")
        Log.d("OkProxySelector", "URI: " + uri)
        Log.d("OkProxySelector", "Host: " + host)
        Log.d("OkProxySelector", "Proxy list size: " + proxy.size)

        if (proxy.isEmpty() || host == null || "127.0.0.1" == host) {
            Log.d("OkProxySelector", "Early return: empty/null/localhost -> fallback")
            return fallback(uri)
        }

        if (!isGithubHost(host)) {
            Log.d("OkProxySelector", "Non-GitHub URL, using NO_PROXY: " + host)
            return listOf(java.net.Proxy.NO_PROXY)
        }

        Log.d("OkProxySelector", "GitHub URL detected, checking proxy rules")
        for (item in proxy) {
            for (proxyHost in item.getHosts()) {
                if (Util.containOrMatch(host, proxyHost)) {
                    Log.d("OkProxySelector", "Matched proxy rule for host: " + proxyHost)
                    return if (item.getProxies().isNotEmpty()) item.getProxies() else fallback(uri)
                }
            }
        }
        Log.d("OkProxySelector", "No proxy rule matched, using fallback")
        return fallback(uri)
    }

    private fun isGithubHost(host: String?): Boolean {
        if (host == null) return false
        val result = host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com")
        Log.d("OkProxySelector", "isGithubHost(" + host + ") = " + result)
        return result
    }

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, e: IOException) {
        system?.connectFailed(uri, socketAddress, e)
    }
}
