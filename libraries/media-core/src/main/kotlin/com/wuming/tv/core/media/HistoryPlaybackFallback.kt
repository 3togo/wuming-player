package com.wuming.tv.core.media

import com.wuming.tv.core.cloud.OpenDriveFailure

/** One-shot raw-route recovery when a history catalog identity cannot be resolved. */
class HistoryPlaybackFallback {
    private var flag: String = ""
    private var rawUrl: String = ""
    private var headers: Map<String, String> = emptyMap()

    class Attempt(val flag: String, val rawUrl: String, val headers: Map<String, String>) {
        @JvmName("flag")
        fun flagAccessor(): String = flag
        @JvmName("rawUrl")
        fun rawUrlAccessor(): String = rawUrl
        @JvmName("headers")
        fun headersAccessor(): Map<String, String> = headers
    }

    @Synchronized
    fun arm(flag: String?, rawUrl: String?, headers: Map<String, String>? = null) {
        this.flag = flag ?: ""
        this.rawUrl = rawUrl ?: ""
        this.headers = headers ?: emptyMap()
    }

    @Synchronized
    fun updateHeaders(headers: Map<String, String>?) {
        this.headers = headers ?: emptyMap()
    }

    @JvmOverloads
    @Synchronized
    fun consume(failureMessage: String? = null): Attempt? {
        // A live account/provider failure does not mean the catalog identity is stale.
        // Keep the chosen resolver and require login/recovery there, rather than
        // retrying the raw episode through another provider's account state.
        if (OpenDriveFailure.fromMessage(failureMessage) != null) {
            clear()
            return null
        }
        if (flag.isBlank() || rawUrl.isBlank()) return null
        val attempt = Attempt(flag, rawUrl, headers)
        clear()
        return attempt
    }

    @Synchronized
    fun clear() {
        flag = ""
        rawUrl = ""
        headers = emptyMap()
    }
}
