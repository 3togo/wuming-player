package com.wuming.android.tv.cloud.opendrive

import java.util.Optional
import java.util.regex.Pattern

data class OpenDriveShareTarget(
    val shareUrl: String,
    val itemId: String
) {
    fun shareUrl(): String = shareUrl
    fun itemId(): String = itemId

    companion object {
        private val HTTPS_URL: Pattern = Pattern.compile("https://\\S+", Pattern.CASE_INSENSITIVE)

        @JvmStatic
        fun fromText(text: String?): Optional<OpenDriveShareTarget> {
            if (text.isNullOrBlank()) return Optional.empty()
            var candidate = text.trim()
            val matcher = HTTPS_URL.matcher(candidate)
            if (matcher.find()) candidate = matcher.group()
            return try {
                Optional.of(OpenDriveShareTarget(candidate, OpenDriveSpider.shareVodId(candidate)))
            } catch (e: IllegalArgumentException) {
                Optional.empty()
            }
        }
    }
}
