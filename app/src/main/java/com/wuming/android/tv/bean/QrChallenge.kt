package com.wuming.android.tv.bean

import java.util.regex.Pattern

class QrChallenge private constructor(
    val imageBase64: String,
    val imageUrl: String,
) {
    fun hasImage(): Boolean = imageBase64.isNotEmpty() || imageUrl.isNotEmpty()

    companion object {
        private val DATA_IMAGE = Pattern.compile("data:image/(?:png|jpe?g|webp);base64,([A-Za-z0-9+/=\\r\\n]+)", Pattern.CASE_INSENSITIVE)
        private val QR_DATA = Pattern.compile("[\"']qr_data[\"']\\s*:\\s*[\"']([A-Za-z0-9+/=\\r\\n]+)[\"']", Pattern.CASE_INSENSITIVE)
        private val IMAGE_SOURCE = Pattern.compile("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE)
        private val QR_URL = Pattern.compile("[\"'](?:qr_(?:url|image)|imgurl)[\"']\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        private const val MAX_BASE64_LENGTH = 8 * 1024 * 1024

        @JvmStatic
        fun from(value: String?): QrChallenge {
            val source = value.orEmpty()
            val data = DATA_IMAGE.matcher(source)
            var base64 = if (data.find()) data.group(1)!!.replace("\r", "").replace("\n", "") else ""
            if (base64.isEmpty()) {
                val qrData = QR_DATA.matcher(source)
                if (qrData.find()) base64 = qrData.group(1)!!.replace("\r", "").replace("\n", "")
            }
            if (base64.length > MAX_BASE64_LENGTH) base64 = ""
            val image = IMAGE_SOURCE.matcher(source)
            var imageUrl = if (image.find()) normalizeImageUrl(image.group(1)!!) else ""
            if (imageUrl.isEmpty()) {
                val qrUrl = QR_URL.matcher(source)
                if (qrUrl.find()) imageUrl = normalizeImageUrl(qrUrl.group(1)!!)
            }
            return QrChallenge(base64, imageUrl)
        }

        private fun normalizeImageUrl(value: String): String {
            // Java trim removes only characters <= U+0020; Kotlin trim is broader.
            var url = value.replace("\\/", "/").replace("&amp;", "&").trim { it <= ' ' }
            if (url.startsWith("//")) url = "https:$url"
            if (url.startsWith("passport.baidu.com/", ignoreCase = true)) url = "https://$url"
            return if (Pattern.matches("(?i)^https?://.+", url)) url else ""
        }
    }
}
