package com.wuming.tv.core.base

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Digest helpers for cache keys, content fingerprints and artifact identities.
 *
 * Lowercase hex SHA-256 is the shared on-disk form, so every platform derives the same key for the
 * same bytes; [isSha256Hex] is the matching validator for keys read back from storage.
 */
object Digests {

    private val SHA_256_HEX = Regex("[0-9a-f]{64}")

    @JvmStatic
    fun sha256Hex(value: ByteArray): String =
        Hex.encodeLower(MessageDigest.getInstance("SHA-256").digest(value))

    @JvmStatic
    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(StandardCharsets.UTF_8))

    /** Streams [input] without buffering it, for fingerprints of files or downloaded artifacts. */
    @JvmStatic
    @Throws(IOException::class)
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return Hex.encodeLower(digest.digest())
    }

    /** Shortened digest for keys that only need to be distinct, not verifiable. */
    @JvmStatic
    @JvmOverloads
    fun sha256Tag(value: String, length: Int = 16): String = sha256Hex(value).take(length.coerceIn(1, 64))

    @JvmStatic
    fun isSha256Hex(value: String?): Boolean = value != null && SHA_256_HEX.matches(value)
}
