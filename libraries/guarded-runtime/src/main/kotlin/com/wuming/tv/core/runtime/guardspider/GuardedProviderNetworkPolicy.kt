package com.wuming.tv.core.runtime.guardspider

/** Narrow provider exceptions for hosts which reject the configured desktop proxy path. */
internal object GuardedProviderNetworkPolicy {

    fun nonProxyHosts(providerClassName: String): List<String> = when (providerClassName) {
        "com.github.catvod.spider.NewCz" -> NEW_CZ_DIRECT_HOSTS
        "com.github.catvod.spider.Libvio" -> LIBVIO_DIRECT_HOSTS
        else -> emptyList()
    }

    private val NEW_CZ_DIRECT_HOSTS = listOf(
        "www.4kcz.com",
        "4kcz.com",
        "t02.cz01.org",
    )

    private val LIBVIO_DIRECT_HOSTS = listOf(
        "www.libvio.io",
        "libvio.io",
        "www.libvio.pw",
        "libvio.pw",
    )
}
