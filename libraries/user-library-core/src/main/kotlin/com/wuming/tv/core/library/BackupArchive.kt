package com.wuming.tv.core.library

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Publish only a verified archive. No non-atomic overwrite fallback: retain the old backup. */
class BackupArchive @JvmOverloads constructor(
    private val publish: (Path, Path) -> Unit = { temporary, target ->
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
    private val compress: (Path, ByteArray) -> Unit = { path, payload ->
        Files.newOutputStream(path).use { raw ->
            GZIPOutputStream(raw).use { it.write(payload) }
        }
    },
) {
    @Throws(IOException::class)
    fun write(target: Path, payload: ByteArray) {
        require(payload.size <= MAX_BYTES) { "Backup exceeds size limit" }
        val directory = target.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".backup-", ".tmp")
        try {
            compress(temporary, payload)
            if (!read(temporary).contentEquals(payload)) throw IOException("Backup verification failed")
            java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
            publish(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Throws(IOException::class)
    fun read(path: Path): ByteArray {
        val output = ByteArrayOutputStream()
        Files.newInputStream(path).use { raw ->
            GZIPInputStream(raw).use { input ->
                val buffer = ByteArray(16384)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > MAX_BYTES) throw IOException("Backup exceeds size limit")
                    output.write(buffer, 0, count)
                }
            }
        }
        return output.toByteArray()
    }

    companion object { const val MAX_BYTES = 64 * 1024 * 1024 }
}
