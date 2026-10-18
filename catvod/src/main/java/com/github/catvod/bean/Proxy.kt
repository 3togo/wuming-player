package com.github.catvod.bean

import android.net.Uri
import android.text.TextUtils
import com.github.catvod.utils.Json
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.Objects
import java.util.function.Function
import java.util.function.Predicate

open class Proxy : Comparable<com.github.catvod.bean.Proxy> {
    @SerializedName("name")
    private var name: String? = null

    @SerializedName("hosts")
    private var hosts: MutableList<String>? = null

    @SerializedName("urls")
    private var urls: MutableList<String>? = null
    open fun getName(): String = if (TextUtils.isEmpty(name)) "" else name!!
    open fun getHosts(): MutableList<String> = hosts ?: Collections.emptyList()
    open fun getUrls(): MutableList<String> = urls ?: Collections.emptyList()

    private var proxies: MutableList<Proxy>? = null
    private var wildcard = false

    open fun init() {
        wildcard = getHosts().stream().anyMatch { host: String? -> host!!.contains("*") }
        proxies =
            getUrls().stream().map<Proxy?> { url: String? -> this.create(url) }
                .filter { obj: Proxy? -> Objects.nonNull(obj) }.map { it!! }.toList()
    }

    open fun getProxies(): MutableList<Proxy> =
        (if (proxies == null) Collections.emptyList<java.net.Proxy>() else proxies)!!

    private fun create(url: String?): Proxy? {
        val uri: Uri = Uri.parse(url)
        if (uri.getScheme() == null || uri.getHost() == null || uri.getPort() <= 0) return null
        val host: String? = IDN.toASCII(uri.getHost())
        if (uri.getScheme()!!.startsWith("http")) {
            return Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(host, uri.getPort())
            )
        }
        if (uri.getScheme()!!.startsWith("socks")) {
            return Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved(host, uri.getPort())
            )
        }
        return null
    }

    override fun compareTo(other: com.github.catvod.bean.Proxy): Int =
        java.lang.Boolean.compare(this.wildcard, other.wildcard)

    // Keep generated static forwarders non-final so existing Java subclasses can hide them.
    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        @JvmStatic
        open fun arrayFrom(element: JsonElement?): MutableList<com.github.catvod.bean.Proxy?> {
            try {
                val listType: Type? =
                    object : TypeToken<MutableList<com.github.catvod.bean.Proxy?>?>() {}.getType()
                val items: MutableList<com.github.catvod.bean.Proxy?>? =
                    Json.GSON.fromJson<MutableList<com.github.catvod.bean.Proxy?>?>(
                        element,
                        listType
                    )
                return if (items ==
                    null
                ) {
                    Collections.emptyList<com.github.catvod.bean.Proxy?>()
                } else {
                    items
                }
            } catch (e: Exception) {
                return Collections.emptyList<com.github.catvod.bean.Proxy?>()
            }
        }
    }
}
