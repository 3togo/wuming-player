package com.wuming.android.tv.utils

import com.github.catvod.utils.Prefers
import com.wuming.tv.core.design.update.UpdateArtifact
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit

/** Android HTTP port. Update traffic uses ordinary TLS and bounded, independent calls. */
object UpdateClient {
    private const val HOST_KEY = "update_mirror_host"
    @JvmField val feedHttp = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    @JvmField val apkHttp = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).build()

    @JvmStatic fun remember(url: String) { Prefers.put(HOST_KEY, URI(url).host) }

    @JvmStatic
    @JvmOverloads
    @Throws(Exception::class)
    fun check(feeds: List<String>, repository: String, channel: String, mode: String, abi: String,
              packageName: String, installedCode: Long, http: OkHttpClient = feedHttp): UpdateArtifact? {
        return resolver.check(feeds, repository, channel, mode, abi, packageName, installedCode, http)
    }
    private val resolver = com.wuming.tv.core.design.update.UpdateFeedClient(
        { Prefers.getString(HOST_KEY) },
        object : com.wuming.tv.core.design.update.UpdateFeedClient.Diagnostics {
            override fun debug(message: String) { AppLog.d("UpdateClient", message) }
            override fun warning(message: String) { AppLog.w("UpdateClient", message) }
        }
    )
}
