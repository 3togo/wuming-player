package com.wuming.quickjs.utils

import android.util.Base64
import android.util.Log
import com.github.catvod.utils.Util
import java.nio.charset.StandardCharsets
import java.security.Key
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {

    @JvmStatic
    fun md5(text: String): String {
        return try {
            Util.md5(text)
        } catch (e: Exception) {
            Log.e("Crypto", "Crypto operation failed", e)
            ""
        }
    }

    @JvmStatic
    fun aes(mode: String, encrypt: Boolean, input: String, inBase64: Boolean, key: String, iv: String?, outBase64: Boolean): String {
        return try {
            var keyBuf = key.toByteArray()
            if (keyBuf.size < 16) keyBuf = keyBuf.copyOf(16)
            var ivBuf = if (iv == null) ByteArray(0) else iv.toByteArray()
            if (ivBuf.size < 16) ivBuf = ivBuf.copyOf(16)
            val cipher = Cipher.getInstance(mode + "Padding")
            val keySpec = SecretKeySpec(keyBuf, "AES")
            if (iv == null) cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec)
            else cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ivBuf))
            val inBuf = if (inBase64) Base64.decode(input.replace("_", "/").replace("-", "+"), Base64.DEFAULT) else input.toByteArray(StandardCharsets.UTF_8)
            return if (outBase64) Base64.encodeToString(cipher.doFinal(inBuf), Base64.NO_WRAP) else String(cipher.doFinal(inBuf), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e("Crypto", "Crypto operation failed", e)
            ""
        }
    }

    @JvmStatic
    fun rsa(mode: String, pub: Boolean, encrypt: Boolean, input: String, inBase64: Boolean, key: String, outBase64: Boolean): String {
        return try {
            val rsaKey = generateKey(pub, key)
            val inBytes = if (inBase64) Base64.decode(input.replace("_", "/").replace("-", "+"), Base64.DEFAULT) else input.toByteArray(StandardCharsets.UTF_8)
            var transformation = "RSA/ECB/PKCS1Padding"
            if (mode == "RSA/PKCS1") transformation = "RSA/ECB/PKCS1Padding"
            else if (mode == "RSA/None/NoPadding") transformation = "RSA/None/NoPadding"
            val cipher = Cipher.getInstance(transformation)
            cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, rsaKey)
            val outBytes = cipher.doFinal(inBytes)
            return if (outBase64) Base64.encodeToString(outBytes, Base64.NO_WRAP) else String(outBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e("Crypto", "Crypto operation failed", e)
            ""
        }
    }

    private fun generateKey(pub: Boolean, key: String): Key {
        val cleanedKey = if (pub) key.replace(Regex("[\\r\\n]"), "").replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
        else key.replace(Regex("[\\r\\n]"), "").replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
        return if (pub) KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(cleanedKey, Base64.DEFAULT)))
        else KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(cleanedKey, Base64.DEFAULT)))
    }
}
