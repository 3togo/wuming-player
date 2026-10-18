package com.wuming.android.tv.server.process

import com.wuming.android.tv.utils.AppLog
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Dedicated OkHttp client and upstream fetch helpers for cast media relay. */
internal class RelayHttpClient private constructor() {

    internal companion object {
        private const val TAG = "CastMediaRelay"

        @Volatile
        private var relayClient: OkHttpClient? = null

        @JvmStatic
        fun client(): OkHttpClient {
            relayClient?.let { return it }
            synchronized(RelayHttpClient::class.java) {
                relayClient?.let { return it }
                val client = OkHttpClient.Builder()
                    .connectionPool(ConnectionPool(16, 30, TimeUnit.MINUTES))
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(true)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                    .build()
                relayClient = client
                return client
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun fetchUpstream(url: String, headers: Map<String, String>): okhttp3.Response =
            fetchUpstream(url, headers, null, null)

        @JvmStatic
        @Throws(IOException::class)
        fun fetchUpstream(
            url: String,
            headers: Map<String, String>,
            method: String?,
            range: String?,
        ): okhttp3.Response = upstreamCall(url, headers, method, range).execute()

        @JvmStatic
        fun upstreamCall(
            url: String,
            headers: Map<String, String>,
            method: String?,
            range: String?,
        ): okhttp3.Call {
            val request = Request.Builder().url(url)
            if ("HEAD" == method) request.head()
            for ((key, value) in headers) {
                try {
                    request.header(key, value)
                } catch (e: IllegalArgumentException) {
                    AppLog.w(TAG, "Ignoring invalid upstream header: $key")
                }
            }
            if (range != null) request.header("Range", range)
            return client().newCall(request.build())
        }
    }
}
