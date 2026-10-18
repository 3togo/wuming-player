package com.wuming.android.tv.utils

/** Compatibility entry point; source-envelope cryptography is shared. */
object AesCipher {
    fun decrypt(encData: ByteArray, password: String): String? = com.wuming.tv.core.base.AesCipher.decrypt(encData, password)
}
