package com.wuming.tv.core.source

import com.wuming.tv.core.source.ProviderJarRecoveryPolicy
import com.wuming.tv.core.source.SpiderDigest
import java.io.File
import java.io.DataInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Disk adapter for one configured provider JAR reference. */
class DexProviderJarCache(directory: File, referenceKey: String) {
    private val directory: Path = directory.toPath()
    private val good: Path
    private val marker: Path

    init {
        require(referenceKey.matches(Regex("[a-fA-F0-9]{32,64}"))) { "Invalid JAR cache key" }
        good = this.directory.resolve("$referenceKey.good.jar")
        marker = this.directory.resolve("$referenceKey.good.sha256")
    }

    @Throws(IOException::class)
    fun accepts(artifact: File, digest: SpiderDigest?): Boolean {
        if (!containsDex(artifact)) return false
        val hashes = hashes(artifact.toPath())
        return ProviderJarRecoveryPolicy.matchesExpected(digest, hashes.md5, hashes.sha256)
    }

    fun recover(digest: SpiderDigest?): File? = runCatching {
        if (!Files.isRegularFile(good) || Files.isSymbolicLink(good) ||
            !Files.isRegularFile(marker) || Files.isSymbolicLink(marker) || !containsDex(good.toFile())) return@runCatching null
        val recorded = Files.readString(marker, StandardCharsets.US_ASCII).trim()
        val hashes = hashes(good)
        good.toFile().takeIf {
            ProviderJarRecoveryPolicy.mayRecover(digest, hashes.md5, hashes.sha256, recorded)
        }
    }.getOrNull()

    @Throws(IOException::class)
    fun promote(initialized: File, digest: SpiderDigest?) {
        if (!accepts(initialized, digest)) throw IOException("Provider JAR failed integrity verification")
        Files.createDirectories(directory)
        val stagedJar = Files.createTempFile(directory, "provider-good-", ".jar")
        var stagedMarker: Path? = null
        try {
            Files.copy(initialized.toPath(), stagedJar, StandardCopyOption.REPLACE_EXISTING)
            if (!accepts(stagedJar.toFile(), digest)) throw IOException("Provider JAR changed while caching")
            val sha256 = hashes(stagedJar).sha256
            stagedMarker = Files.createTempFile(directory, "provider-good-", ".sha256")
            Files.writeString(stagedMarker, sha256, StandardCharsets.US_ASCII)
            move(stagedJar, good)
            move(stagedMarker, marker)
        } finally {
            Files.deleteIfExists(stagedJar)
            stagedMarker?.let(Files::deleteIfExists)
        }
    }

    private fun move(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun containsDex(artifact: File): Boolean = runCatching {
        ZipFile(artifact).use { zip ->
            val entry = zip.getEntry("classes.dex") ?: return@use false
            if (entry.isDirectory) return@use false
            val magic = ByteArray(8)
            DataInputStream(zip.getInputStream(entry)).use { it.readFully(magic) }
            magic[0] == 'd'.code.toByte() && magic[1] == 'e'.code.toByte() &&
                magic[2] == 'x'.code.toByte() && magic[3] == '\n'.code.toByte() &&
                (4..6).all { magic[it] in '0'.code.toByte()..'9'.code.toByte() } && magic[7] == 0.toByte()
        }
    }.getOrDefault(false)

    private fun hashes(artifact: Path): Hashes {
        val md5 = MessageDigest.getInstance("MD5")
        val sha256 = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(artifact).use { input ->
            val buffer = ByteArray(16384)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md5.update(buffer, 0, count)
                sha256.update(buffer, 0, count)
            }
        }
        return Hashes(md5.digest().hex(), sha256.digest().hex())
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private data class Hashes(val md5: String, val sha256: String)
}
