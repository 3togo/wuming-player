package com.wuming.tv.core.runtime.js

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException

/** Honors the conventional HTTP(S)_PROXY and NO_PROXY process environment used by desktop tools. */
class EnvironmentProxySelector(private val environment: Map<String, String> = System.getenv()) : ProxySelector() {

    fun proxyFor(target: URI): Proxy {
        if (bypassed(target)) return Proxy.NO_PROXY
        val value = when (target.scheme.lowercase()) {
            "https" -> variable("https_proxy") ?: variable("http_proxy")
            "http" -> variable("http_proxy")
            else -> null
        }?.trim().orEmpty()
        if (value.isEmpty()) return Proxy.NO_PROXY
        val uri = runCatching { URI(if ("://" in value) value else "http://$value") }.getOrNull()
            ?: return Proxy.NO_PROXY
        if (!uri.scheme.equals("http", true) || uri.host.isNullOrBlank() || uri.userInfo != null) {
            return Proxy.NO_PROXY
        }
        val port = if (uri.port > 0) uri.port else 80
        // java.net.http does not resolve an unresolved ProxySelector address before opening its
        // socket on all supported JDK 17 builds. That surfaces as UnresolvedAddressException even
        // for a numeric loopback proxy such as 127.0.0.1. Resolve the proxy endpoint here.
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, port))
    }

    override fun select(uri: URI): List<Proxy> = listOf(proxyFor(uri))

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    private fun bypassed(target: URI): Boolean {
        val host = target.host?.lowercase().orEmpty()
        if (host.isEmpty()) return true
        return (variable("no_proxy") ?: "").split(',').map(String::trim).filter(String::isNotEmpty).any { rule ->
            val value = rule.substringBefore(':').lowercase()
            value == "*" || host == value.removePrefix(".") || host.endsWith(".$value".replace("..", "."))
        }
    }

    private fun variable(lowercase: String): String? =
        environment[lowercase] ?: environment[lowercase.uppercase()]
}
