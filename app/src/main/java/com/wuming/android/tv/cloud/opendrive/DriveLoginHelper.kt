package com.wuming.android.tv.cloud.opendrive

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.bean.Flag
import com.wuming.android.tv.bean.Site
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Objects
import okhttp3.ResponseBody

/**
 * Helpers for drive-provider QR login flows: bounded image decoding, and
 * baidu/bilibili site detection used to pick login hints and error handling.
 * Extracted from BaseVideoActivity; pure/static, no Activity state.
 */
object DriveLoginHelper {
    const val MAX_QR_IMAGE_BYTES: Int = 8 * 1024 * 1024
    const val MAX_QR_IMAGE_DIMENSION: Int = 2048
    const val MAX_QR_IMAGE_PIXELS: Long = 4L * 1024 * 1024

    /** Decodes a QR bitmap with bounded memory (downsamples large images).  */
    @JvmStatic
    fun decodeQrBitmap(data: ByteArray?): Bitmap? {
        if (data == null || data.size == 0 || data.size > MAX_QR_IMAGE_BYTES) return null
        val bounds: BitmapFactory.Options = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample: Int = 1
        while (bounds.outWidth / sample > MAX_QR_IMAGE_DIMENSION ||
            bounds.outHeight / sample > MAX_QR_IMAGE_DIMENSION ||
            (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_QR_IMAGE_PIXELS
        ) {
            sample *= 2
        }
        val options: BitmapFactory.Options = BitmapFactory.Options()
        options.inSampleSize = sample
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    /** Reads a response body with a hard size cap; returns null when over the cap.  */
    @Throws(IOException::class)
    @JvmStatic
    fun readLimited(body: ResponseBody?): ByteArray? {
        if (body == null || body.contentLength() > MAX_QR_IMAGE_BYTES) return null
        val capacity: Int =
            if (body.contentLength() > 0) body.contentLength().toInt() else 16 * 1024
        body.byteStream().use { input ->
            ByteArrayOutputStream(capacity).use { output ->
                val buffer: ByteArray = ByteArray(8192)
                var total: Int = 0
                var count: Int
                while ((input.read(buffer).also { count = it }) != -1) {
                    total += count
                    if (total > MAX_QR_IMAGE_BYTES) return null
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        }
    }

    /** Whether the current episode/flag points at a Baidu drive source.  */
    @JvmStatic
    fun isBaiduLogin(episode: Episode?, flag: Flag?): Boolean {
        if (episode == null || flag == null) return false
        val episodeUrl: String = episode.getUrl().lowercase()
        val flagName: String = flag.getFlag().lowercase()
        return episodeUrl.contains("pan.baidu.com") ||
            episodeUrl.contains("\"share_id\"") && episodeUrl.contains("\"sekey\"") ||
            episodeUrl.contains(
                "baidu"
            ) ||
            flagName.contains("百度") || flagName.contains("嘟嘟")
    }

    /** Whether the site configuration belongs to bilibili.  */
    @JvmStatic
    fun isBilibiliSite(site: Site?): Boolean {
        if (site == null) return false
        val value: String = (
            (
                site.key + " " + site.name + " " + site.api + " " +
                    site.ext + " " + site.jar
                )
            ).lowercase()
        return value.contains("bilibili") || value.contains("bili") ||
            value.contains("哔哩") || value.contains("嗶哩") || value.contains("b站")
    }

    /** Whether an error message indicates a bilibili login failure.  */
    @JvmStatic
    fun isBilibiliLoginError(message: String?, bilibiliSite: Boolean): Boolean {
        val value: String = Objects.toString(message, "").lowercase()
        val login: Boolean = value.contains("login") || value.contains("登录") ||
            value.contains("登入") || value.contains("登錄") || value.contains("登陆")
        val bilibili: Boolean = bilibiliSite || value.contains(
            "bilibili"
        ) || value.contains("哔哩") ||
            value.contains("嗶哩") || value.contains("b站")
        return login && bilibili
    }
}
