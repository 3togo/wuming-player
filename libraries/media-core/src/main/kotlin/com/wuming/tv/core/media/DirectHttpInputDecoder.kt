package com.wuming.tv.core.media

import com.wuming.tv.core.media.DecodeResult
import com.wuming.tv.core.media.DecodedMedia
import com.wuming.tv.core.media.InputDecoder
import com.wuming.tv.core.media.MediaFingerprint
import com.wuming.tv.core.media.MediaSourceDescriptor
import com.wuming.tv.core.media.OpenDriveUri
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Normalizes direct HTTP input without opening a network connection. */
class DirectHttpInputDecoder : InputDecoder {
    override fun decode(input: String?): DecodeResult {
        if (input == null) return DecodeResult.noMatch()
        val value = input.trim()
        if (!startsWithHttp(value)) return DecodeResult.noMatch()
        if (value.length > MAX_INPUT_LENGTH) return DecodeResult.invalid("HTTP media URL is too long")
        return try {
            val source = value.toHttpUrlOrNull()
            if (source == null || source.username.isNotEmpty() || source.password.isNotEmpty()) {
                return DecodeResult.invalid("HTTP media URL is invalid")
            }
            val url = source.newBuilder().fragment(null).build().toString()
            val persistable = source.querySize == 0
            val descriptor = MediaSourceDescriptor(
                ADAPTER_ID, 1, mapOf("url" to url),
                MediaFingerprint.sha256("$ADAPTER_ID\n$url"), 0, persistable)
            DecodeResult.decoded(DecodedMedia(OpenDriveUri.Kind.ASSET,
                title(source), listOf(descriptor)))
        } catch (error: Exception) {
            DecodeResult.invalid("HTTP media URL is invalid")
        }
    }

    private fun startsWithHttp(value: String): Boolean =
        value.regionMatches(0, "http://", 0, 7, ignoreCase = true) ||
        value.regionMatches(0, "https://", 0, 8, ignoreCase = true)

    private fun title(source: HttpUrl): String {
        val segments = source.pathSegments
        val value = if (segments.isEmpty()) "" else segments[segments.size - 1]
        return if (value.isBlank()) source.host else value
    }

    companion object {
        const val ADAPTER_ID = "org.opendrive.http"
        private const val MAX_INPUT_LENGTH = 8192
    }
}
