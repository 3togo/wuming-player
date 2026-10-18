package com.github.catvod.net.interceptor

import com.wuming.tv.core.source.HttpConnectionPolicy
import okhttp3.Interceptor
import okhttp3.Response

/** Runs on every network hop, including redirects that bypass application interceptors. */
class RedirectConnectionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val outgoing = if (HttpConnectionPolicy.shouldClose(url.scheme, url.host)) {
            // The resolver advertises an empty redirect body but writes HTML afterwards.
            // Retiring its connection prevents those bytes becoming the next status line.
            request.newBuilder().header("Connection", "close").build()
        } else request
        return chain.proceed(outgoing)
    }
}
