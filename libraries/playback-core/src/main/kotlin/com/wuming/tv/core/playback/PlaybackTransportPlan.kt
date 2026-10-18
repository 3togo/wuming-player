package com.wuming.tv.core.playback

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Immutable, protocol-aware transport decisions for one media item.
 *
 * OpenDrive capability values travel through the legacy header bundle, but they are control
 * data rather than HTTP headers. Parsing the plan in one place guarantees that these values are
 * consumed before a request reaches a provider or CDN.
 */
class PlaybackTransportPlan(
    val kind: Kind,
    val cachePolicy: CachePolicy,
    networkHeaders: Map<String, String>?,
    val explicitCookie: Boolean,
    val credentials: Boolean,
    contentLength: Long,
    parallelProfile: String?,
    refreshUri: String?,
    expiresAtEpochMs: Long,
    cacheKey: String?
) {
    enum class Kind { PROGRESSIVE, HLS, DASH, SMOOTH_STREAMING, RTSP, RTMP, P2P, LOCAL, UNKNOWN }
    enum class CachePolicy { NONE, WRITE_THROUGH }

    val networkHeaders: Map<String, String> = if (networkHeaders == null)
        emptyMap() else Collections.unmodifiableMap(LinkedHashMap(networkHeaders))
    val contentLength: Long = maxOf(0L, contentLength)
    val parallelProfile: String = parallelProfile ?: ""
    val refreshUri: String = refreshUri ?: ""
    val expiresAtEpochMs: Long = maxOf(0L, expiresAtEpochMs)
    val cacheKey: String = cacheKey ?: ""

    @JvmName("kind")
    fun kindAccessor(): Kind = kind
    @JvmName("cachePolicy")
    fun cachePolicyAccessor(): CachePolicy = cachePolicy
    @JvmName("networkHeaders")
    fun networkHeadersAccessor(): Map<String, String> = networkHeaders
    @JvmName("explicitCookie")
    fun explicitCookieAccessor(): Boolean = explicitCookie
    @JvmName("credentials")
    fun credentialsAccessor(): Boolean = credentials
    @JvmName("contentLength")
    fun contentLengthAccessor(): Long = contentLength
    @JvmName("parallelProfile")
    fun parallelProfileAccessor(): String = parallelProfile
    @JvmName("refreshUri")
    fun refreshUriAccessor(): String = refreshUri
    @JvmName("expiresAtEpochMs")
    fun expiresAtEpochMsAccessor(): Long = expiresAtEpochMs
    @JvmName("cacheKey")
    fun cacheKeyAccessor(): String = cacheKey

    fun parallelRanges(): Boolean {
        return kind == Kind.PROGRESSIVE && contentLength > 0L && OpenDrivePlaybackHints.validProfile(parallelProfile)
    }

    fun refreshable(): Boolean {
        return kind == Kind.PROGRESSIVE && refreshUri.startsWith("opendrive://")
    }

    fun adaptive(): Boolean {
        return kind == Kind.HLS || kind == Kind.DASH || kind == Kind.SMOOTH_STREAMING
    }

    companion object {
        @JvmStatic
        fun parse(url: String?, mimeType: String?, sourceHeaders: Map<String, String>?): PlaybackTransportPlan {
            val headers = if (sourceHeaders == null) LinkedHashMap<String, String>() else LinkedHashMap(sourceHeaders)
            val explicitCookie = removeFlag(headers, OpenDrivePlaybackHints.EXPLICIT_COOKIE)
            val explicitHls = removeFlag(headers, OpenDrivePlaybackHints.HLS)
            var contentLength = removeLong(headers, OpenDrivePlaybackHints.CONTENT_LENGTH)
            var parallelProfile = removeValue(headers, OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE)
            val refreshUri = removeValue(headers, OpenDrivePlaybackHints.REFRESH_URI)
            val expiresAt = removeLong(headers, OpenDrivePlaybackHints.EXPIRES_AT)
            removeFlag(headers, OpenDrivePlaybackHints.QUARK_SMART_ORIGINAL_FALLBACK)
            removeValue(headers, OpenDrivePlaybackHints.FALLBACK_REASON)
            removeValue(headers, OpenDrivePlaybackHints.COPY_LEASE)
            val kind = inferKind(url, mimeType, explicitHls)
            if (kind != Kind.PROGRESSIVE || contentLength <= 0L || !OpenDrivePlaybackHints.validProfile(parallelProfile)) {
                contentLength = 0L
                parallelProfile = ""
            }
            val http = isHttp(url)
            val credentials = containsHeader(headers, "Cookie") || containsHeader(headers, "Authorization") || containsHeader(headers, "Proxy-Authorization")
            val cacheableKind = kind == Kind.PROGRESSIVE || kind == Kind.HLS || kind == Kind.DASH || kind == Kind.SMOOTH_STREAMING
            val stableRefreshKey = kind == Kind.PROGRESSIVE && refreshUri.isNotEmpty()
            val cachePolicy = if (http && cacheableKind && !credentials && !isLoopback(url)
                && parallelProfile.isEmpty() && (!hasSensitiveQuery(url) || stableRefreshKey))
                CachePolicy.WRITE_THROUGH else CachePolicy.NONE
            val cacheIdentity = if (refreshUri.isNotEmpty()) refreshUri else url
            val cacheKey = if (cachePolicy == CachePolicy.WRITE_THROUGH && kind == Kind.PROGRESSIVE)
                "media-" + sha256(cacheIdentity ?: "") else ""
            return PlaybackTransportPlan(kind, cachePolicy, headers, explicitCookie, credentials,
                contentLength, parallelProfile, refreshUri, expiresAt, cacheKey)
        }

        @JvmStatic
        fun inferKind(value: String?, mimeType: String?, explicitHls: Boolean): Kind {
            if (explicitHls || equalsMime(mimeType, "application/x-mpegurl") || equalsMime(mimeType, "application/vnd.apple.mpegurl")) return Kind.HLS
            if (equalsMime(mimeType, "application/dash+xml")) return Kind.DASH
            if (equalsMime(mimeType, "application/vnd.ms-sstr+xml")) return Kind.SMOOTH_STREAMING
            val scheme = scheme(value)
            if ("rtsp" == scheme) return Kind.RTSP
            if ("rtmp" == scheme || "rtmps" == scheme) return Kind.RTMP
            if ("magnet" == scheme || "ed2k" == scheme || "thunder" == scheme) return Kind.P2P
            if ("file" == scheme || "content" == scheme || "asset" == scheme || "android.resource" == scheme) return Kind.LOCAL
            if (isLoopbackM3u8Proxy(value)) return Kind.HLS
            val path = path(value).lowercase(Locale.ROOT)
            if (path.endsWith(".m3u8")) return Kind.HLS
            if (path.endsWith(".mpd")) return Kind.DASH
            if (path.endsWith(".ism") || path.endsWith(".isml") || path.endsWith("/manifest")) return Kind.SMOOTH_STREAMING
            if ("http" == scheme || "https" == scheme) return Kind.PROGRESSIVE
            return if (scheme.isEmpty()) Kind.UNKNOWN else Kind.PROGRESSIVE
        }

        private fun isLoopbackM3u8Proxy(value: String?): Boolean {
            try {
                val uri = URI.create(value ?: "")
                val host = uri.host
                if (host == null || !("localhost".equals(host, ignoreCase = true) || "127.0.0.1" == host || "::1" == host) || "/proxy" != uri.path) return false
                val query = uri.rawQuery ?: return false
                for (field in query.split("&")) {
                    val separator = field.indexOf('=')
                    val name = if (separator < 0) field else field.substring(0, separator)
                    val parameter = if (separator < 0) "" else field.substring(separator + 1)
                    if ("do".equals(name, ignoreCase = true) && "m3u8".equals(parameter, ignoreCase = true)) return true
                }
            } catch (_: Exception) {}
            return false
        }

        private fun equalsMime(actual: String?, expected: String): Boolean {
            if (actual == null) return false
            val delimiter = actual.indexOf(';')
            val normalized = if (delimiter < 0) actual else actual.substring(0, delimiter)
            return expected.equals(normalized.trim(), ignoreCase = true)
        }

        private fun isHttp(value: String?): Boolean {
            val scheme = scheme(value)
            return "http" == scheme || "https" == scheme
        }

        private fun isLoopback(value: String?): Boolean {
            try {
                val host = URI.create(value).host
                return host != null && ("localhost".equals(host, ignoreCase = true) || "127.0.0.1" == host || "::1" == host)
            } catch (_: Exception) { return false }
        }

        private fun hasSensitiveQuery(value: String?): Boolean {
            try {
                val query = URI.create(value).rawQuery
                if (query == null || query.isEmpty()) return false
                for (field in query.split("&")) {
                    val separator = field.indexOf('=')
                    val name = (if (separator < 0) field else field.substring(0, separator)).lowercase(Locale.ROOT)
                    if (name.contains("token") || name.contains("auth") || name.contains("sign") || name == "key"
                        || name.contains("credential") || name.contains("policy") || name.contains("expires") || name == "x-oss-security-token") {
                        return true
                    }
                }
            } catch (_: Exception) {}
            return false
        }

        private fun scheme(value: String?): String {
            try {
                val scheme = URI.create(value ?: "").scheme
                return scheme?.lowercase(Locale.ROOT) ?: ""
            } catch (_: Exception) { return "" }
        }

        private fun path(value: String?): String {
            try {
                val path = URI.create(value ?: "").path
                return path ?: ""
            } catch (_: Exception) { return "" }
        }

        private fun containsHeader(headers: Map<String, String>, name: String): Boolean {
            for (key in headers.keys) if (name.equals(key, ignoreCase = true)) return true
            return false
        }

        private fun removeFlag(headers: MutableMap<String, String>, name: String): Boolean {
            var enabled = false
            val iterator = headers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!name.equals(entry.key, ignoreCase = true)) continue
                enabled = enabled or entry.value.toBoolean()
                iterator.remove()
            }
            return enabled
        }

        private fun removeLong(headers: MutableMap<String, String>, name: String): Long {
            val value = removeValue(headers, name)
            if (value.isEmpty()) return 0L
            try { return maxOf(0L, value.toLong()) }
            catch (_: NumberFormatException) { return 0L }
        }

        private fun removeValue(headers: MutableMap<String, String>, name: String): String {
            var value = ""
            val iterator = headers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!name.equals(entry.key, ignoreCase = true)) continue
                if (value.isEmpty()) value = entry.value
                iterator.remove()
            }
            return value
        }

        private fun sha256(value: String): String {
            try {
                val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
                val result = StringBuilder(digest.size * 2)
                for (item in digest) result.append(String.format(Locale.ROOT, "%02x", item))
                return result.toString()
            } catch (_: Exception) {
                return Integer.toHexString(value.hashCode())
            }
        }
    }
}
