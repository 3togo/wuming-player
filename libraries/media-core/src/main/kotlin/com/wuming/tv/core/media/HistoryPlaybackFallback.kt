package com.wuming.tv.core.media

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

    @Synchronized
    fun consume(): Attempt? {
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
