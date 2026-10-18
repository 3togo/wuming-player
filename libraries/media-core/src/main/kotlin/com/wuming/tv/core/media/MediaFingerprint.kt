package com.wuming.tv.core.media

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object MediaFingerprint {
    private val HEX = "0123456789abcdef".toCharArray()

    @JvmStatic
    fun sha256(value: String): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
            val result = StringBuilder("sha256:")
            for (item in digest) {
                val valueAt = item.toInt() and 0xff
                result.append(HEX[valueAt ushr 4]).append(HEX[valueAt and 0x0f])
            }
            return result.toString()
        } catch (e: Exception) {
            throw IllegalStateException("SHA-256 is unavailable", e)
        }
    }
}
