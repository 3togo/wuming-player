package com.wuming.tv.core.cloud

import com.wuming.tv.core.cloud.CredentialShapePolicy
import com.wuming.tv.core.cloud.CredentialShapePolicy.isLoginValue
import com.wuming.tv.core.cloud.CredentialShapePolicy.isSharePasscode
import com.wuming.tv.core.cloud.FirstPartyOriginPolicy
import com.wuming.tv.core.cloud.FirstPartyOriginPolicy.httpsUri
import com.wuming.tv.core.cloud.FirstPartyOriginPolicy.isWithinDomain
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.Locale
import java.util.regex.Pattern
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Share canonicalization and bounded stream helpers shared by cloud providers. */
object CloudProtocol {
    @JvmField val BYTE_RANGE: Pattern = Pattern.compile("bytes=(\\d+)-(\\d*)")

    @JvmField val SUFFIX_RANGE: Pattern = Pattern.compile("bytes=-(\\d+)")

    @JvmField val CONTENT_RANGE: Pattern = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)")

        private val SHARE_PATH: Pattern = Pattern.compile("/s/[A-Za-z0-9_-]{6,128}/?")
        private const val MAX_SHARE_URL_LENGTH = 2048

    @JvmStatic
        fun validLoginValue(value: String?): Boolean = isLoginValue(value)

        /**
         * Normalizes a provider share link to its canonical form, rejecting other
         * schemes/hosts, user info, explicit ports, and malformed share ids.
         * Returns null when the URL is not a first-party share link.
         */
    @JvmStatic
        fun canonicalShareUrl(value: String?, host: String): String? {
            val uri: URI? = httpsUri(value, MAX_SHARE_URL_LENGTH, true)
            if (uri == null || !isWithinDomain(uri.getHost(), host, false)) return null
            val path: String? = uri.getPath()
            if (path == null || !SHARE_PATH.matcher(path).matches()) return null
            return "https://" + host + "/s/" + path.substring(3).replace("/", "")
        }

        /** Extracts and shape-validates the `pwd` share passcode parameter.  */
    @JvmStatic
        fun sharePasscode(value: String?, providerName: String?): String {
            val url: HttpUrl? = if (value ==
                null
            ) {
                null
            } else {
                value.trim { it <= ' ' }.toHttpUrlOrNull()
            }
            if (url == null) return ""
            val passcode: String? = url.queryParameter("pwd")
            if (passcode == null ||
                passcode.codePoints().allMatch { Character.isWhitespace(it) }
            ) {
                return ""
            }
            require(isSharePasscode(passcode)) { "Invalid " + providerName + " share passcode" }
            return passcode
        }

        /** Preserve access information when routing a canonical share identity.  */
    @JvmStatic
        fun shareUrlWithPasscode(
            canonical: String,
            original: String?,
            provider: String?
        ): String? {
            val passcode: String = sharePasscode(original, provider)
            return if (passcode.codePoints().allMatch { Character.isWhitespace(it) }) {
                canonical
            } else {
                canonical.toHttpUrl().newBuilder()
                    .setQueryParameter("pwd", passcode).build().toString()
            }
        }

        /** Reads up to `limit` bytes, tolerating streams that temporarily report zero.  */
        @Throws(IOException::class)
    @JvmStatic
        fun readPrefix(input: InputStream?, limit: Int): ByteArray {
            if (input == null || limit <= 0) return ByteArray(0)
            val buffer: ByteArray = ByteArray(limit)
            var offset: Int = 0
            while (offset < limit) {
                val count: Int = input.read(buffer, offset, limit - offset)
                if (count < 0) break
                if (count == 0) {
                    // Defensive progress for non-conforming streams that temporarily report zero.
                    val value: Int = input.read()
                    if (value < 0) break
                    buffer[offset++] = value.toByte()
                } else {
                    offset += count
                }
            }
            return if (offset == limit) buffer else buffer.copyOf(offset)
        }

    @JvmStatic
        fun urlLooksLikeHls(value: String?): Boolean {
            try {
                val uri: URI = URI.create(value)
                val path: String? = uri.getPath()
                return path != null && path.lowercase().endsWith(".m3u8")
            } catch (ignored: Exception) {
                return false
            }
        }
}
