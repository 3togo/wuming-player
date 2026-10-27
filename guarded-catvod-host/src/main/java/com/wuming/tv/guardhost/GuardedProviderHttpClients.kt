package com.wuming.tv.guardhost

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Bounds JPJ's sequential discovery requests so dead mirrors cannot exhaust startup. */
object GuardedProviderHttpClients {
    @Volatile private var startingJpj = false
    private var base: OkHttpClient? = null
    private var startup: OkHttpClient? = null

    @JvmStatic
    @Synchronized
    fun configure(providerClass: String) {
        startingJpj = providerClass == "com.github.catvod.spider.JPJ"
        base = null
        startup = null
    }

    @JvmStatic
    fun initialized() { startingJpj = false }

    @JvmStatic
    @Synchronized
    fun client(client: OkHttpClient): OkHttpClient {
        if (!startingJpj) return client
        if (base !== client) {
            base = client
            val timeout = client.callTimeoutMillis.takeIf { it in 1..4000 } ?: 4000
            startup = client.newBuilder().callTimeout(timeout.toLong(), TimeUnit.MILLISECONDS).build()
        }
        return startup!!
    }
}
