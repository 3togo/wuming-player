package com.wuming.tv.core.base

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesCipher {
    private const val KEY_SIZE = 32
    private const val IV_SIZE = 16
    private const val SALT_PREFIX = "Salted__"

    fun decrypt(encData: ByteArray, password: String): String? {
        return try {
            if (encData.size < 16 || !String(encData, 0, 8, Charsets.US_ASCII).startsWith(SALT_PREFIX)) {
                return null
            }
            val salt = encData.copyOfRange(8, 16)
            val ciphertext = encData.copyOfRange(16, encData.size)

            val (key, iv) = deriveKeyAndIv(password.toByteArray(Charsets.US_ASCII), salt)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun deriveKeyAndIv(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val totalSize = KEY_SIZE + IV_SIZE
        val result = ByteArrayOutputStream()
        var prevHash: ByteArray? = null

        while (result.size() < totalSize) {
            val md = java.security.MessageDigest.getInstance("MD5")
            if (prevHash != null) md.update(prevHash)
            md.update(password)
            md.update(salt)
            prevHash = md.digest()
            result.write(prevHash)
        }

        val all = result.toByteArray()
        val key = all.copyOfRange(0, KEY_SIZE)
        val iv = all.copyOfRange(KEY_SIZE, KEY_SIZE + IV_SIZE)
        return Pair(key, iv)
    }
}
