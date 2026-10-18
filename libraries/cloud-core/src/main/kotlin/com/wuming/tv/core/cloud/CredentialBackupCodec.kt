package com.wuming.tv.core.cloud

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted envelope every platform writes for an account-credential backup.
 *
 * Android and desktop both export the same file so a backup taken on one can be restored on the
 * other; keeping the codec here is what guarantees they cannot drift apart. Passphrase-derived
 * key (PBKDF2/HMAC-SHA256) with AES-GCM, versioned so older envelopes stay readable.
 */
object CredentialBackupCodec {

    private const val FORMAT = "tvbox-account-credentials"
    private const val LEGACY_FORMAT = "tvbox-opendrive-credentials"
    private const val VERSION = 2
    private const val ITERATIONS = 210_000
    private val GSON = Gson()

    /** Encrypted backups are rejected past this size before any key derivation happens. */
    @JvmField
    val MAX_FILE_BYTES = 2 * 1024 * 1024

    @JvmStatic
    @Throws(Exception::class)
    fun encrypt(credentials: Map<String, String>, passphrase: CharArray?): ByteArray {
        requirePassphrase(passphrase)
        val salt = random(16)
        val iv = random(12)
        val plaintext = GSON.toJson(LinkedHashMap(credentials)).toByteArray(StandardCharsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ITERATIONS), GCMParameterSpec(128, iv))
            val envelope = Envelope()
            envelope.format = FORMAT
            envelope.version = VERSION
            envelope.kdf = "PBKDF2WithHmacSHA256"
            envelope.iterations = ITERATIONS
            envelope.salt = encode(salt)
            envelope.iv = encode(iv)
            envelope.ciphertext = encode(cipher.doFinal(plaintext))
            val result = GSON.toJson(envelope).toByteArray(StandardCharsets.UTF_8)
            if (result.size > MAX_FILE_BYTES) throw IllegalArgumentException("Backup is too large")
            return result
        } finally {
            java.util.Arrays.fill(plaintext, 0)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun decrypt(encoded: ByteArray?, passphrase: CharArray?): Map<String, String> {
        requirePassphrase(passphrase)
        requireNotNull(encoded) { "Invalid backup size" }
        require(encoded.isNotEmpty() && encoded.size <= MAX_FILE_BYTES) { "Invalid backup size" }

        val envelope = GSON.fromJson(String(encoded, StandardCharsets.UTF_8), Envelope::class.java)
        require(
            envelope != null && (FORMAT == envelope.format || LEGACY_FORMAT == envelope.format) &&
                (envelope.version == 1 || envelope.version == VERSION) &&
                "PBKDF2WithHmacSHA256" == envelope.kdf &&
                envelope.iterations == ITERATIONS
        ) { "Unsupported backup format" }

        val salt = decode(envelope.salt, 16)
        val iv = decode(envelope.iv, 12)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, envelope.iterations), GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(ciphertext)
        try {
            val values: Map<String, String>? = GSON.fromJson(
                String(plaintext, StandardCharsets.UTF_8),
                object : TypeToken<LinkedHashMap<String, String>>() {}.type
            )
            requireNotNull(values) { "Invalid backup payload" }
            return values
        } finally {
            java.util.Arrays.fill(plaintext, 0)
        }
    }

    @Throws(Exception::class)
    private fun key(passphrase: CharArray?, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES"
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun requirePassphrase(passphrase: CharArray?) {
        require(passphrase != null && passphrase.size >= 8) {
            "Passphrase must contain at least 8 characters"
        }
    }

    private fun random(length: Int): ByteArray {
        val value = ByteArray(length)
        SecureRandom().nextBytes(value)
        return value
    }

    private fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    private fun decode(value: String?, expectedLength: Int): ByteArray {
        val decoded = Base64.getDecoder().decode(value ?: "")
        require(decoded.size == expectedLength) { "Invalid backup metadata" }
        return decoded
    }

    private class Envelope {
        var format: String? = null
        var version: Int = 0
        var kdf: String? = null
        var iterations: Int = 0
        var salt: String? = null
        var iv: String? = null
        var ciphertext: String? = null
    }
}
