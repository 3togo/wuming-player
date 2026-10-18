package com.wuming.android.tv.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesCipherTest {
    private val PASSWORD = "public-fixture-password"
    private val TEST_JSON = """{"vod":[{"url":"http://example.com","name":"test"}],"live":[]}"""

    @Test
    fun decrypt_validOpensslEncryptedData_returnsOriginalJson() {
        val encrypted = opensslEncrypt(TEST_JSON, PASSWORD)
        val decrypted = AesCipher.decrypt(encrypted, PASSWORD)
        assertNotNull(decrypted)
        assertEquals(TEST_JSON, decrypted)
    }

    @Test
    fun decrypt_wrongPassword_neverYieldsOriginalJson() {
        val encrypted = opensslEncrypt(TEST_JSON, PASSWORD)
        // AES/CBC only rejects a wrong key through PKCS#5 padding, which a fresh salt makes
        // valid by luck about 1 in 256 runs. The contract callers rely on is the mismatch.
        assertNotEquals(TEST_JSON, AesCipher.decrypt(encrypted, "wrongpassword"))
    }

    @Test
    fun decrypt_invalidData_returnsNull() {
        val decrypted = AesCipher.decrypt("not encrypted".toByteArray(), PASSWORD)
        assertNull(decrypted)
    }

    @Test
    fun decrypt_tooShortData_returnsNull() {
        val decrypted = AesCipher.decrypt(ByteArray(5), PASSWORD)
        assertNull(decrypted)
    }

    @Test
    fun decrypt_emptyJson_returnsEmptyJson() {
        val encrypted = opensslEncrypt("{}", PASSWORD)
        val decrypted = AesCipher.decrypt(encrypted, PASSWORD)
        assertNotNull(decrypted)
        assertEquals("{}", decrypted)
    }

    private fun opensslEncrypt(plaintext: String, password: String): ByteArray {
        val salt = ByteArray(8)
        SecureRandom().nextBytes(salt)

        val (key, iv) = deriveKeyAndIv(password.toByteArray(Charsets.US_ASCII), salt)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val result = ByteArrayOutputStream()
        result.write("Salted__".toByteArray(Charsets.US_ASCII))
        result.write(salt)
        result.write(ciphertext)
        return result.toByteArray()
    }

    private fun deriveKeyAndIv(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val keySize = 32
        val ivSize = 16
        val totalSize = keySize + ivSize

        val result = ByteArrayOutputStream()
        var prevHash: ByteArray? = null

        while (result.size() < totalSize) {
            val md = MessageDigest.getInstance("MD5")
            if (prevHash != null) md.update(prevHash)
            md.update(password)
            md.update(salt)
            prevHash = md.digest()
            result.write(prevHash)
        }

        val all = result.toByteArray()
        return Pair(all.copyOfRange(0, keySize), all.copyOfRange(keySize, keySize + ivSize))
    }
}
