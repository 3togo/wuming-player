package com.wuming.tv.core.runtime.guardspider

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/** Recognizes the two output names used by supported guard loaders without scanning other files. */
object GuardedDexPayload {
    private val dexHeader = "dex\n".toByteArray(Charsets.US_ASCII)
    private val names = listOf("config.db", "config.jar")

    fun expectedOutput(dataDirectory: Path): Path = directory(dataDirectory).resolve(names.first())

    fun find(dataDirectory: Path): Path? = names.asSequence()
        .map { directory(dataDirectory).resolve(it) }
        .firstOrNull(::isDexPayload)

    private fun directory(dataDirectory: Path): Path = dataDirectory.resolve("code-cache/sharedb")

    private fun isDexPayload(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        return try {
            val header = Files.newInputStream(path).use { it.readNBytes(4) }
            when {
                header.contentEquals(dexHeader) -> true
                header.size < 4 || header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte() -> false
                else -> JarFile(path.toFile(), true).use { jar ->
                    jar.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".dex", ignoreCase = true) }
                }
            }
        } catch (_: IOException) {
            false
        }
    }
}
