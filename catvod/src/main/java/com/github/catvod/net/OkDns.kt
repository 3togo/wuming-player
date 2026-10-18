package com.github.catvod.net

import com.github.catvod.bean.Doh
import com.github.catvod.utils.Util
import com.wuming.tv.core.source.DnsResolverCache
import java.net.IDN
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

open class OkDns internal constructor(
    systemDns: Dns,
    fallbackDns: () -> Dns,
    private val dohFactory: (Doh) -> Dns,
) : Dns {
    constructor() : this(Dns.SYSTEM, { defaultFallback }, ::buildDoh)

    private val map = ConcurrentHashMap<String, String>()
    private val cache = DnsResolverCache(systemDns::lookup, { hostname -> fallbackDns().lookup(hostname) })

    open fun setDoh(item: Doh) {
        // Build first: an invalid selection must not discard the working resolver.
        val resolver = item.getUrl().takeIf { it.isNotBlank() }?.let { dohFactory(item) }
        cache.select(resolver?.let { it::lookup })
    }

    open fun clear() {
        map.clear()
        cache.clear()
    }

    open fun addAll(hosts: MutableList<String?>) {
        var changed = false
        for (host in hosts.filterNotNull()) {
            val parts = host.split("=", limit = 2)
            if (parts.size != 2) continue
            val key = toAscii(parts[0].trim()) ?: continue
            val target = toAscii(parts[1].trim()) ?: continue
            if (key.isEmpty() || target.isEmpty()) continue
            if (map.put(key, target) != target) changed = true
        }
        if (changed) cache.clear()
    }

    private fun toAscii(host: String?): String? {
        try {
            return IDN.toASCII(host)
        } catch (e: Exception) {
            return host
        }
    }

    private fun get(hostname: String): String {
        val target: String? = map.get(hostname)
        if (target != null) return target
        for (entry in map.entries) {
            if (Util.containOrMatch(
                    hostname,
                    entry.key
                )
            ) {
                return entry.value
            }
        }
        return hostname
    }

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        val ascii = toAscii(hostname) ?: hostname
        val target = get(ascii)
        // Numeric addresses and configured literal overrides never need a DNS request.
        if (isIpAddressLiteral(target)) return InetAddress.getAllByName(target).toList()
        return cache.lookup(target)
    }

    companion object {
        private val defaultFallback: Dns by lazy {
            buildDoh(Doh().url("https://dns.alidns.com/dns-query"),
                listOf(InetAddress.getByAddress(byteArrayOf(223.toByte(), 5, 5, 5)),
                    InetAddress.getByAddress(byteArrayOf(223.toByte(), 6, 6, 6))))
        }

        private fun buildDoh(item: Doh): Dns = buildDoh(item, item.getHosts())

        private fun buildDoh(item: Doh, bootstrap: List<InetAddress>?): Dns =
            DnsOverHttps.Builder()
                .client(OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(8, TimeUnit.SECONDS).build())
                .url(item.getUrl().toHttpUrl())
                .bootstrapDnsHosts(bootstrap)
                .build()

        private fun isIpAddressLiteral(host: String): Boolean {
            if (host.contains(":")) return true // IPv6 literal

            // IPv4 dotted-decimal check
            val parts: Array<String> =
                host.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (parts.size != 4) return false
            for (part: String in parts) {
                try {
                    val `val`: Int = part.toInt()
                    if (`val` < 0 || `val` > 255) return false
                } catch (e: NumberFormatException) {
                    return false
                }
            }
            return true
        }
    }
}
