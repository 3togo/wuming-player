package com.wuming.tv.core.runtime.jvmspider

import com.wuming.tv.core.base.native.NativeLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile

object JvmSpiderArtifactInspector {

    private const val MAX_ENTRIES = 4_096
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private val forbiddenSuffixes = listOf(".dex", ".so", ".guard", ".aar", ".apk", ".dylib", ".dll")
    private val forbiddenClassMarkers = listOf(
        "android/", "androidx/", "dalvik/", "com/fongmi/android/",
    ).map(String::toByteArray)

    private val nativeAvailable: Boolean get() = NativeLoader.available

    fun inspect(path: Path, expectedSha256: String? = null): JvmSpiderArtifactReport {
        require(Files.isRegularFile(path)) { "JVM spider artifact is not a regular file" }
        val size = Files.size(path)
        if (size <= 0 || size > MAX_TOTAL_BYTES) unsupported("JVM spider JAR has an invalid size")
        if (nativeAvailable) {
            val data = Files.readAllBytes(path)
            val resultJson = nativeInspect(data, expectedSha256 ?: "")
            if (resultJson != null && resultJson.isNotEmpty()) {
                val result = com.google.gson.JsonParser.parseString(resultJson).asJsonObject
                if (result.has("error") && result.get("error").asString.isNotEmpty()) {
                    unsupported(result.get("error").asString)
                }
                return JvmSpiderArtifactReport(
                    result.get("sha256").asString,
                    result.get("classes").asInt,
                    result.get("total").asLong,
                )
            }
        }
        val sha256 = digest(path)
        expectedSha256?.trim()?.takeIf(String::isNotEmpty)?.let { expected ->
            if (!expected.matches(Regex("[a-fA-F0-9]{64}"))) {
                unsupported("Trusted JVM spider SHA-256 must contain exactly 64 hexadecimal characters")
            }
            if (!sha256.equals(expected, ignoreCase = true)) unsupported("JVM spider SHA-256 mismatch")
        }

        var entries = 0
        var classes = 0
        var total = 0L
        runCatching {
            JarFile(path.toFile(), true).use { jar ->
                val iterator = jar.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    if (entry.isDirectory) continue
                    entries++
                    if (entries > MAX_ENTRIES) unsupported("JVM spider JAR contains too many entries")
                    val name = entry.name.lowercase()
                    if (forbiddenSuffixes.any(name::endsWith) || name.endsWith(".jar")) {
                        unsupported("JVM spider JAR contains a non-portable entry: ${entry.name}")
                    }
                    val declared = entry.size
                    if (declared > MAX_ENTRY_BYTES) unsupported("JVM spider JAR entry is too large: ${entry.name}")
                    jar.getInputStream(entry).use { input ->
                        var count = 0L
                        val bytes = if (name.endsWith(".class")) input.readBytes() else {
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                if (count > MAX_ENTRY_BYTES) unsupported("JVM spider JAR entry is too large: ${entry.name}")
                            }
                            total += count
                            null
                        }
                        if (bytes != null) {
                            count = bytes.size.toLong()
                            if (count > MAX_ENTRY_BYTES) unsupported("JVM spider class is too large: ${entry.name}")
                            if (forbiddenClassMarkers.any { marker -> bytes.contains(marker) }) {
                                unsupported("JVM spider class references an Android runtime: ${entry.name}")
                            }
                            classes++
                            total += count
                        }
                    }
                    if (total > MAX_TOTAL_BYTES) unsupported("JVM spider JAR expands beyond the portable limit")
                }
            }
        }.getOrElse { failure ->
            if (failure is UnsupportedJvmSpiderArtifact) throw failure
            throw UnsupportedJvmSpiderArtifact("JVM spider artifact is not a readable JAR: ${failure.message}")
        }
        if (classes == 0) unsupported("JVM spider JAR contains no JVM classes")
        return JvmSpiderArtifactReport(sha256, classes, total)
    }

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) if (this[start + offset] != needle[offset]) continue@outer
            return true
        }
        return false
    }

    private fun unsupported(message: String): Nothing = throw UnsupportedJvmSpiderArtifact(message)

    @JvmStatic
    private external fun nativeInspect(data: ByteArray, expectedSha256: String): String?
}
