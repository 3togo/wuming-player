package com.wuming.tv.core.playback

import java.util.LinkedHashMap

/** Internal playback capabilities; these values must never reach provider HTTP servers. */
object OpenDrivePlaybackHints {

    const val COPY_LEASE = "X-OpenDrive-Copy-Lease"
    const val FALLBACK_REASON = "X-OpenDrive-Fallback-Reason"

    enum class FallbackReason {
        NONE, QUARK_NOT_VIDEO, UC_QUOTA, ALI_SHARED_ORIGINAL_FORBIDDEN, BAIDU_PREVIEW_REJECTED
    }

    @JvmStatic
    fun fallback(headers: Map<String, String>?, reason: FallbackReason): Map<String, String> {
        val result = copyWithout(headers, FALLBACK_REASON)
        if (reason != FallbackReason.NONE) result[FALLBACK_REASON] = reason.name
        return result
    }

    @JvmStatic
    fun fallbackReason(headers: Map<String, String>?): FallbackReason {
        if (headers == null) return FallbackReason.NONE
        for ((key, value) in headers) {
            if (FALLBACK_REASON.equals(key, ignoreCase = true)) {
                return try { FallbackReason.valueOf(value) }
                catch (_: IllegalArgumentException) { FallbackReason.NONE }
                catch (_: NullPointerException) { FallbackReason.NONE }
            }
        }
        return if (enabled(headers, QUARK_SMART_ORIGINAL_FALLBACK))
            FallbackReason.QUARK_NOT_VIDEO else FallbackReason.NONE
    }

    const val CONTENT_LENGTH = "X-OpenDrive-Content-Length"
    const val PARALLEL_RANGE_PROFILE = "X-OpenDrive-Parallel-Range"
    const val HLS = "X-OpenDrive-Hls"
    const val EXPLICIT_COOKIE = "X-OpenDrive-Explicit-Cookie"
    const val REFRESH_URI = "X-OpenDrive-Refresh-Uri"
    const val EXPIRES_AT = "X-OpenDrive-Expires-At"
    const val QUARK_SMART_ORIGINAL_FALLBACK = "X-OpenDrive-Quark-Smart-Original-Fallback"
    const val PROFILE_ALIPAN = "alipan-4x4m"
    const val PROFILE_BAIDU_ORIGINAL = "baidu-original-3x4m"
    const val PROFILE_GENERIC_HTTP = "http-range-5x2m"
    const val PROFILE_UC_PDS = "uc-pds-downloadmt"
    const val PROFILE_QUARK_ORIGINAL = "quark-downloadmt"

    @JvmStatic
    fun parallelRanges(headers: Map<String, String>?, profile: String?, contentLength: Long): Map<String, String> {
        val result = copyWithout(headers, CONTENT_LENGTH, PARALLEL_RANGE_PROFILE)
        if (contentLength > 0L && validProfile(profile)) {
            result[CONTENT_LENGTH] = contentLength.toString()
            result[PARALLEL_RANGE_PROFILE] = profile!!
        }
        return result
    }

    @JvmStatic
    fun hls(headers: Map<String, String>?, preserveExplicitCookie: Boolean): Map<String, String> {
        val result = copyWithout(headers, HLS, EXPLICIT_COOKIE)
        result[HLS] = "true"
        if (preserveExplicitCookie) result[EXPLICIT_COOKIE] = "true"
        return result
    }

    @JvmStatic
    fun refreshable(headers: Map<String, String>?, stableUri: String?, expiresAtEpochMs: Long): Map<String, String> {
        val result = copyWithout(headers, REFRESH_URI, EXPIRES_AT)
        if (stableUri != null && stableUri.startsWith("opendrive://")) {
            result[REFRESH_URI] = stableUri
            if (expiresAtEpochMs > 0L) {
                result[EXPIRES_AT] = expiresAtEpochMs.toString()
            }
        }
        return result
    }

    @JvmStatic
    fun without(headers: Map<String, String>?, vararg names: String): Map<String, String> {
        return copyWithout(headers, *names)
    }

    private fun copyWithout(headers: Map<String, String>?, vararg reservedNames: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        if (headers == null) return result
        outer@ for ((key, value) in headers) {
            for (reserved in reservedNames) {
                if (reserved.equals(key, ignoreCase = true)) continue@outer
            }
            result[key] = value
        }
        return result
    }

    @JvmStatic
    fun enabled(headers: Map<String, String>?, name: String?): Boolean {
        if (headers == null || name == null) return false
        for ((key, value) in headers) {
            if (name.equals(key, ignoreCase = true)) {
                return value.toBoolean()
            }
        }
        return false
    }

    @JvmStatic
    fun validProfile(profile: String?): Boolean {
        return PROFILE_ALIPAN == profile || PROFILE_BAIDU_ORIGINAL == profile
            || PROFILE_GENERIC_HTTP == profile || PROFILE_UC_PDS == profile
            || PROFILE_QUARK_ORIGINAL == profile
    }
}
