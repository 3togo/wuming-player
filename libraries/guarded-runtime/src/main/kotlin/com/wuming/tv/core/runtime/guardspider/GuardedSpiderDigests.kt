package com.wuming.tv.core.runtime.guardspider

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Single SHA-256 helper for the guarded-spider pipeline: cache keys, package integrity
 * checks and instrumented-artifact identities all hash by content, so they must agree.
 */
internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().hex()
}

internal fun sha256OrNull(path: Path): String? = runCatching { sha256(path) }.getOrNull()

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
