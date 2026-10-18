package com.wuming.tv.core.runtime.python

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** Hosts supply a verified bundle; extraction cannot replace a running runtime revision. */
object PythonRuntimeBundle {
    @Synchronized fun install(root: File, sha256: String, input: () -> InputStream): File {
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        val target = File(root, sha256)
        if (File(target, ".complete").readTextOrNull() == sha256) return target
        root.mkdirs()
        val archive = File(root, ".download-${UUID.randomUUID()}")
        val stage = File(root, ".stage-${UUID.randomUUID()}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var compressed = 0L
            input().use { source -> archive.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    compressed += n
                    require(compressed <= 256L * 1024 * 1024) { "Python bundle is too large" }
                    digest.update(buffer, 0, n); out.write(buffer, 0, n)
                }
            } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == sha256) { "Python bundle checksum mismatch" }
            stage.mkdirs()
            var total = 0L
            var count = 0
            val seen = HashSet<String>()
            ZipInputStream(archive.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 20000) { "Too many Python bundle entries" }
                    val name = entry.name.removeSuffix("/")
                    require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && name.split('/').none { it in setOf("", ".", "..") }) { "Invalid Python bundle path" }
                    require(seen.add(name)) { "Duplicate Python bundle path" }
                    val file = File(stage, name)
                    if (entry.isDirectory) { file.mkdirs(); continue }
                    file.parentFile.mkdirs()
                    file.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 512L * 1024 * 1024) { "Expanded Python bundle is too large" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            File(stage, ".complete").writeText(sha256)
            require(!target.exists()) { "Incomplete Python runtime exists; remove $target and retry" }
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return target
        } finally { archive.delete(); stage.deleteRecursively() }
    }
    private fun File.readTextOrNull(): String? = if (isFile) readText() else null
}
