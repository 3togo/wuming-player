package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderPackageInspector
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile

enum class SpiderArtifactKind {
    GUARDED_DEX_PACKAGE,
    /** Unguarded Android spider: a `classes.dex` payload without any native guard. */
    PLAIN_DEX_PACKAGE,
    PORTABLE_CLASS_JAR,
    UNKNOWN,
}

data class SpiderArtifactFingerprint(
    val sha256: String,
    val bytes: Long,
    val kind: SpiderArtifactKind,
    val supportedAbis: List<String> = emptyList(),
    val classCount: Int = 0,
)

/** Read-only classification. A fingerprint is evidence for a trust decision, never trust by itself. */
object SpiderArtifactFingerprinter {
    fun inspect(path: Path): SpiderArtifactFingerprint {
        require(Files.isRegularFile(path)) { "Spider artifact is not a regular file" }
        val digest = sha256(path)
        val guarded = runCatching { GuardedSpiderPackageInspector.inspect(path, digest) }.getOrNull()
        if (guarded != null) {
            return SpiderArtifactFingerprint(
                sha256 = digest,
                bytes = Files.size(path),
                kind = SpiderArtifactKind.GUARDED_DEX_PACKAGE,
                supportedAbis = guarded.supportedAbis.map { it.configName }.sorted(),
            )
        }
        if (isPlainDex(path)) {
            return SpiderArtifactFingerprint(
                sha256 = digest,
                bytes = Files.size(path),
                kind = SpiderArtifactKind.PLAIN_DEX_PACKAGE,
                // No native code runs, but activation still needs one cache/data key.
                supportedAbis = listOf(GuardedSpiderAbi.ARMEABI_V7A.configName),
            )
        }
        val classes = runCatching {
            JarFile(path.toFile(), true).use { jar ->
                val entries = jar.entries().asSequence().toList()
                require(entries.size <= MAX_ENTRIES) { "Spider artifact contains too many entries" }
                entries.count { !it.isDirectory && it.name.endsWith(".class") }
            }
        }.getOrNull()
        return SpiderArtifactFingerprint(
            sha256 = digest,
            bytes = Files.size(path),
            kind = if (classes != null && classes > 0) {
                SpiderArtifactKind.PORTABLE_CLASS_JAR
            } else {
                SpiderArtifactKind.UNKNOWN
            },
            classCount = classes ?: 0,
        )
    }

    /** A single DEX payload with neither a guard file nor an ARM library needs no emulation. */
    private fun isPlainDex(path: Path): Boolean = runCatching {
        var dex = false
        JarFile(path.toFile(), true).use { jar ->
            val entries = jar.entries().asSequence().filterNot { it.isDirectory }.toList()
            if (entries.size > MAX_ENTRIES) return@runCatching false
            entries.forEach { entry ->
                val name = entry.name.substringAfterLast('/')
                if (name.endsWith(".dex", ignoreCase = true)) dex = true
                if (name.endsWith(".so", ignoreCase = true)) return@runCatching false
                if (name.endsWith(".guard", ignoreCase = true) || name.equals("woshinidie.bin", ignoreCase = true)) return@runCatching false
            }
        }
        dex
    }.getOrDefault(false)

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val MAX_ENTRIES = 20_000
}
