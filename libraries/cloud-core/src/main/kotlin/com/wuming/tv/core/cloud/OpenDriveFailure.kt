package com.wuming.tv.core.cloud

import java.io.IOException

/** A failure description, never permission to retry, switch routes, or delete credentials. */
open class OpenDriveFailure : IOException {
    enum class Reason {
        AUTH_REQUIRED, QUOTA_EXHAUSTED, TRANSCODE_UNAVAILABLE, RESOURCE_UNAVAILABLE,
        ACCESS_DENIED, NETWORK, PROVIDER_ERROR
    }

    private val provider: String
    val reason: Reason
    val httpStatus: Int
    val apiCode: Long

    constructor(provider: String?, reason: Reason) : this(provider, reason, 0, 0, null)

    constructor(provider: String?, reason: Reason, httpStatus: Int, apiCode: Long, cause: Throwable?) :
            super(PREFIX + safeProvider(provider) + ":" + reason.name, cause) {
        this.provider = safeProvider(provider)
        this.reason = reason
        this.httpStatus = httpStatus
        this.apiCode = apiCode
    }

    fun provider(): String = provider
    fun reason(): Reason = reason
    fun httpStatus(): Int = httpStatus
    fun apiCode(): Long = apiCode

    companion object {
        private const val PREFIX = "OpenDriveFailure:"

        @JvmStatic
        fun fromMessage(message: String?): OpenDriveFailure? {
            if (message == null || !message.startsWith(PREFIX)) return null
            val parts = message.substring(PREFIX.length).split(":")
            if (parts.size != 2 || safeProvider(parts[0]) != parts[0]) return null
            try { return OpenDriveFailure(parts[0], Reason.valueOf(parts[1])) }
            catch (_: IllegalArgumentException) { return null }
        }

        private fun safeProvider(provider: String?): String {
            if (provider == null) return "other"
            return when (provider) {
                "quark", "uc", "alipan", "baidu", "123pan", "tianyi", "webdav",
                "115", "xunlei", "pikpak" -> provider
                else -> "other"
            }
        }
    }
}
