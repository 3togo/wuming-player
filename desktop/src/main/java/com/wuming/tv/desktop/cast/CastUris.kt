package com.wuming.tv.desktop.cast

import java.net.URI
import java.util.Locale

class CastUris private constructor() {
    companion object {
        @JvmStatic fun requireHttp(value: String?): String {
            try {
                val uri = URI.create(value)
                require(
                    ("http".equals(uri.scheme, true) || "https".equals(uri.scheme, true)) &&
                        uri.host != null && uri.userInfo == null &&
                        !value!!.chars().anyMatch(Character::isISOControl),
                )
                return uri.toASCIIString()
            } catch (error: Exception) {
                throw IllegalArgumentException("Casting requires an HTTP(S) video URL")
            }
        }

        @JvmStatic fun seconds(value: String): Double {
            require(value.matches(Regex("[0-9]{1,6}:[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,3})?"))) {
                "Seek position must be HH:MM:SS"
            }
            val parts = value.split(':')
            return parts[0].toLong() * 3600.0 + parts[1].toInt() * 60.0 + parts[2].toDouble()
        }

        @JvmStatic fun time(seconds: Double): String {
            val s = maxOf(0L, seconds.toLong())
            return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        }
    }
}
