package com.wuming.tv.core.runtime.guardspider

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.jar.JarFile

enum class GuardedSpiderAbi(
    val configName: String,
    internal val libraryFileName: String,
    val bits: Int,
) {
    ARMEABI_V7A("armeabi-v7a", "ftyguard_v7.so", 32),
    ARM64_V8A("arm64-v8a", "ftyguard_v8.so", 64);

    companion object {
        fun parse(value: String): GuardedSpiderAbi = entries.firstOrNull {
            it.configName.equals(value, true) || it.name.equals(value, true)
        } ?: throw IllegalArgumentException("Unsupported guarded-spider ABI: $value")
    }
}

data class GuardedSpiderPackageReport(
    val sha256: String,
    val compressedBytes: Long,
    val dexEntry: String,
    val guardEntry: String,
    val libraries: Map<GuardedSpiderAbi, String>,
) {
    val supportedAbis: Set<GuardedSpiderAbi> get() = libraries.keys
}

class UnsupportedGuardedSpiderPackage(message: String) : IllegalArgumentException(message)

object GuardedSpiderPackageInspector {
    private const val MAX_PACKAGE_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    private const val MAX_ENTRIES = 256

    fun inspect(path: Path, expectedSha256: String): GuardedSpiderPackageReport {
        require(Files.isRegularFile(path)) { "Guarded spider package is not a regular file" }
        val packageBytes = Files.size(path)
        if (packageBytes <= 0 || packageBytes > MAX_PACKAGE_BYTES) unsupported("Guarded spider package has an invalid size")
        val expected = expectedSha256.trim()
        if (!expected.matches(Regex("[a-fA-F0-9]{64}"))) unsupported("Trusted SHA-256 must contain 64 hexadecimal characters")
        val actual = sha256(path)
        if (!actual.equals(expected, true)) unsupported("Guarded spider package SHA-256 mismatch")

        var dex: String? = null
        var guard: String? = null
        val libraries = linkedMapOf<GuardedSpiderAbi, String>()
        val names = hashSetOf<String>()
        runCatching {
            JarFile(path.toFile(), true).use { jar ->
                val entries = jar.entries().asSequence().filterNot { it.isDirectory }.toList()
                if (entries.size > MAX_ENTRIES) unsupported("Guarded spider package contains too many entries")
                entries.forEach { entry ->
                    val normalized = normalize(entry.name)
                    if (!names.add(normalized.lowercase())) unsupported("Guarded spider package contains duplicate entries")
                    if (entry.size > MAX_ENTRY_BYTES) unsupported("Guarded spider entry is too large: $normalized")
                    when {
                        normalized.equals("classes.dex", true) -> dex = unique(dex, normalized, "classes.dex")
                        normalized.substringAfterLast('/').equals("ftyshinidie.guard", true) ->
                            guard = unique(guard, normalized, "ftyshinidie.guard")
                        else -> GuardedSpiderAbi.entries.firstOrNull {
                            normalized.substringAfterLast('/').equals(it.libraryFileName, true)
                        }?.let { abi ->
                            if (libraries.put(abi, normalized) != null) unsupported("Guarded spider package contains duplicate ${abi.configName} libraries")
                        }
                    }
                }
            }
        }.getOrElse { failure ->
            if (failure is UnsupportedGuardedSpiderPackage) throw failure
            throw UnsupportedGuardedSpiderPackage("Guarded spider package is not a readable JAR: ${failure.message}")
        }
        if (dex == null) unsupported("Guarded spider package has no classes.dex")
        if (guard == null) unsupported("Guarded spider package has no ftyshinidie.guard")
        if (libraries.isEmpty()) unsupported("Guarded spider package has no supported ARM guard library")
        return GuardedSpiderPackageReport(actual, packageBytes, dex, guard, libraries.toMap())
    }

    internal fun extract(path: Path, report: GuardedSpiderPackageReport, abi: GuardedSpiderAbi): GuardedSpiderExtraction {
        val libraryEntry = report.libraries[abi]
            ?: unsupported("Guarded spider package does not contain ${abi.configName}")
        val root = Files.createTempDirectory("tvbox-guarded-spider-")
        return try {
            val native = Files.createDirectory(root.resolve("native"))
            val data = Files.createDirectory(root.resolve("data"))
            val library = native.resolve(abi.libraryFileName)
            val guard = native.resolve("ftyshinidie.guard")
            val originalDex = root.resolve("classes.dex")
            JarFile(path.toFile(), true).use { jar ->
                copy(jar, libraryEntry, library)
                copy(jar, report.guardEntry, guard)
                copy(jar, report.dexEntry, originalDex)
            }
            GuardedSpiderExtraction(root, library, guard, originalDex, data, abi)
        } catch (failure: Throwable) {
            deleteExtractionTree(root)
            throw failure
        }
    }

    private fun normalize(name: String): String {
        val path = Path.of(name.replace('\\', '/')).normalize().toString().replace('\\', '/')
        if (path.startsWith("../") || path == ".." || path.startsWith('/')) unsupported("Unsafe guarded spider entry: $name")
        return path
    }

    private fun unique(current: String?, candidate: String, label: String): String {
        if (current != null) unsupported("Guarded spider package contains duplicate $label entries")
        return candidate
    }

    private fun copy(jar: JarFile, name: String, target: Path) {
        val entry = jar.getJarEntry(name) ?: unsupported("Guarded spider package changed during extraction")
        jar.getInputStream(entry).use { input -> Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun unsupported(message: String): Nothing = throw UnsupportedGuardedSpiderPackage(message)
}

class GuardedSpiderExtraction internal constructor(
    val root: Path,
    val library: Path,
    val guard: Path,
    val originalDex: Path,
    val dataDirectory: Path,
    val abi: GuardedSpiderAbi,
) : Closeable {
    val decryptedDex: Path get() = dataDirectory.resolve("code-cache/sharedb/config.db")

    override fun close() = deleteExtractionTree(root)
}

private fun deleteExtractionTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { Files.deleteIfExists(path) } }
    }
}
