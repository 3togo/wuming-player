package com.wuming.tv.core.runtime.guardspider

import javax.crypto.Cipher

/** Android providers use PKCS7 for AES; the desktop JCE names the same block padding PKCS5. */
object ProviderCiphers {
    @JvmStatic
    fun getInstance(transformation: String?): Cipher {
        val desktopName = if ("AES/CBC/PKCS7Padding".equals(transformation, ignoreCase = true)) {
            "AES/CBC/PKCS5Padding"
        } else {
            transformation
        }
        return Cipher.getInstance(desktopName)
    }
}
