package com.wuming.android.tv.cloud.opendrive

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import com.wuming.tv.core.playback.OpenDriveStreamCheck as SharedCheck

/** Android proxy-address binding for the shared bounded checker. */
class OpenDriveStreamCheck : AutoCloseable {
    enum class State { MEDIA_BYTES, HLS_SEGMENT, HTTP_ERROR, TEXT_RESPONSE, INVALID_PLAYLIST, LIMIT, CANCELLED, NETWORK_ERROR, UNSUPPORTED }
    @JvmRecord data class Result(val state: State?, val httpStatus: Int, val bytes: Int)
    private val shared: SharedCheck
    constructor(client: OkHttpClient) { shared = SharedCheck(client, ::localProxy) }
    constructor() : this(OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build())
    fun check(value: String?, headers: Map<String, String>?): Result {
        val result = shared.check(value, headers)
        return Result(result.state?.let { State.valueOf(it.name) }, result.httpStatus, result.bytes)
    }
    override fun close() = shared.close()
    companion object {
        private fun localProxy(url: HttpUrl): Boolean {
            if (url.scheme != "http" || url.encodedPath != "/proxy") return false
            return try {
                val proxy = com.wuming.android.tv.server.Server.get().getAddress("/proxy").toHttpUrlOrNull()
                proxy != null && proxy.host == url.host && proxy.port == url.port &&
                    url.username.isEmpty() && url.password.isEmpty()
            } catch (unavailable: RuntimeException) { false }
        }
        @JvmStatic fun forwarded(from: HttpUrl, to: HttpUrl, headers: Map<String, String>): Map<String, String> = SharedCheck.forwarded(from, to, headers)
    }
}
