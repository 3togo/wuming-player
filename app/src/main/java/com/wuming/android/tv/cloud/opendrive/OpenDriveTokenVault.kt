package com.wuming.android.tv.cloud.opendrive

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.github.catvod.Init
import com.github.catvod.Init.context
import java.nio.charset.StandardCharsets
import java.security.Key
import java.security.KeyStore
import java.util.Arrays
import java.util.Base64
import java.util.LinkedHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.Volatile

/** Android-keystore-backed storage addressed only by opaque catalog vault references.  */
object OpenDriveTokenVault {
    private const val PREFS = "opendrive_vault_v1"
    private const val ALIAS = "tvbox.opendrive.vault.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val REFERENCE_PREFIX = "vault:opendrive/"
    private val VAULT_ENCODER: Base64.Encoder = Base64.getEncoder()
    private val VAULT_DECODER: Base64.Decoder = Base64.getDecoder()

    @Volatile
    private var cachedKey: SecretKey? = null

    @JvmStatic
    fun reference(providerId: String?, handle: String?): String =
        REFERENCE_PREFIX + providerId + "/" + handle

    @JvmStatic
    fun put(reference: String?, plaintext: String?): Boolean {
        if (!valid(reference) || plaintext == null || plaintext.length > 128 * 1024) return false
        try {
            preferences()!!.edit().putString(reference, encrypt(plaintext)).apply()
            return true
        } catch (ignored: Exception) {
            return false
        }
    }

    @JvmStatic
    fun get(reference: String?): String {
        if (!valid(reference)) return ""
        try {
            val value: String? = preferences()!!.getString(reference, "")
            if (value == null) return ""
            val separator: Int = value.indexOf('.')
            if (separator <= 0 || separator == value.length - 1) return ""
            val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, VAULT_DECODER.decode(value.substring(0, separator)))
            )
            val plaintext: ByteArray =
                cipher.doFinal(VAULT_DECODER.decode(value.substring(separator + 1)))
            try {
                return String(plaintext, StandardCharsets.UTF_8)
            } finally {
                Arrays.fill(plaintext, 0.toByte())
            }
        } catch (ignored: Exception) {
            return ""
        }
    }

    @JvmStatic
    fun remove(reference: String?) {
        if (valid(reference)) preferences()!!.edit().remove(reference).apply()
    }

    @JvmStatic
    fun clearProvider(providerId: String?) {
        val prefix: String = REFERENCE_PREFIX + providerId + "/"
        val editor: SharedPreferences.Editor = preferences()!!.edit()
        for (key: String in preferences()!!.getAll().keys) {
            if (key.startsWith(prefix)) editor.remove(key)
        }
        editor.apply()
    }

    @JvmStatic
    fun exportCredentials(): MutableMap<String, String> {
        val result = LinkedHashMap<String, String>()
        for (reference: String in preferences()!!.getAll().keys) {
            if (!credentialReference(reference)) continue
            val value: String = get(reference)
            if (!value.isEmpty()) result.put(reference, value)
        }
        return result
    }

    @JvmStatic
    fun replaceCredentials(values: Map<out String?, String?>?): Boolean {
        if (!validCredentials(values)) return false
        val encrypted: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        for (entry: Map.Entry<String?, String?> in values!!.entries) {
            if (!credentialReference(entry.key) || entry.value == null ||
                entry.value!!.length > 128 * 1024
            ) {
                return false
            }
            try {
                encrypted.put(entry.key, OpenDriveTokenVault.encrypt(entry.value!!))
            } catch (ignored: Exception) {
                return false
            }
        }
        val editor: SharedPreferences.Editor = preferences()!!.edit()
        for (key: String? in preferences()!!.getAll().keys) {
            if (credentialReference(key)) editor.remove(key)
        }
        for (entry: MutableMap.MutableEntry<String?, String?> in encrypted.entries) {
            editor.putString(entry.key, entry.value)
        }
        return editor.commit()
    }

    @JvmStatic
    fun validCredentials(values: Map<out String?, String?>?): Boolean {
        if (values == null) return false
        for (entry: Map.Entry<String?, String?> in values.entries) {
            if (!credentialReference(entry.key) || entry.value == null ||
                entry.value!!.length > 128 * 1024
            ) {
                return false
            }
        }
        return true
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun encrypt(plaintext: String): String {
        val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val cleartext: ByteArray = plaintext.toByteArray(StandardCharsets.UTF_8)
        try {
            val encrypted: ByteArray = cipher.doFinal(cleartext)
            return (
                VAULT_ENCODER.encodeToString(cipher.getIV()) + "." +
                    VAULT_ENCODER.encodeToString(encrypted)
                )
        } finally {
            Arrays.fill(cleartext, 0.toByte())
        }
    }

    @JvmStatic
    private fun credentialReference(reference: String?): Boolean {
        if (!valid(reference)) return false
        return reference!!.endsWith("/token") || reference.endsWith("/credential") ||
            reference.startsWith(REFERENCE_PREFIX + "webdav/config.")
    }

    @JvmStatic
    private fun valid(reference: String?): Boolean {
        if (reference == null || !reference.startsWith(REFERENCE_PREFIX)) return false
        val providerStart: Int = REFERENCE_PREFIX.length
        val separator: Int = reference.indexOf('/', providerStart)
        if (separator <= providerStart || separator == reference.length - 1 ||
            separator - providerStart > 32 ||
            reference.length - separator - 1 > 128 ||
            !lowerOrDigit(
                reference.get(providerStart)
            ) || !alphaNumeric(reference.get(separator + 1))
        ) {
            return false
        }
        for (index: Int in providerStart + 1..<separator) {
            val current: Char = reference.get(index)
            if (!lowerOrDigit(current) && current != '_' && current != '-') return false
        }
        for (index: Int in separator + 1..<reference.length) {
            val current: Char = reference.get(index)
            if (!alphaNumeric(current) && current != '.' && current != '_' && current != '~' &&
                current != '-'
            ) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    private fun lowerOrDigit(value: Char): Boolean =
        value >= 'a' && value <= 'z' || value >= '0' && value <= '9'

    @JvmStatic
    private fun alphaNumeric(value: Char): Boolean =
        lowerOrDigit(value) || value >= 'A' && value <= 'Z'

    @JvmStatic
    private fun preferences(): SharedPreferences? =
        context()!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    @Throws(Exception::class)
    @JvmStatic
    private fun key(): SecretKey? {
        if (cachedKey != null) return cachedKey
        val store: KeyStore = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val existing: Key? = store.getKey(ALIAS, null)
        if (existing is SecretKey) return existing.also { cachedKey = it }
        val generator: KeyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey().also { cachedKey = it }
    }
}
