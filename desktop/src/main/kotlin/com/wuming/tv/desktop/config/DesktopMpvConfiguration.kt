package com.wuming.tv.desktop.config

import com.wuming.tv.core.base.AtomicFilePublisher
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Owns only the app's optional extra config; never edits the user's system mpv configuration. */
class DesktopMpvConfiguration(val path: Path = defaultPath()) {
    fun read(): String = if (Files.exists(path)) readFrom(path) else ""
    fun save(content: String) {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Configuration exceeds 1 MiB." }
        require('\u0000' !in content) { "Configuration contains a NUL character." }
        AtomicFilePublisher.publish(path, ownerOnly = true) { Files.write(it, bytes) }
    }
    fun readFrom(source: Path): String {
        require(Files.size(source) <= MAX_BYTES) { "Configuration exceeds 1 MiB." }
        return Files.newInputStream(source).use { decode(it.readNBytes(MAX_BYTES + 1)) }
    }
    fun importFrom(source: Path): String = readFrom(source).also(::save)
    fun launchArguments(): List<String> = if (Files.isRegularFile(path)) listOf("--include=$path") else emptyList()
    private fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "Configuration exceeds 1 MiB." }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    companion object {
        const val MAX_BYTES = 1024 * 1024
        fun defaultPath(): Path = (System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".config")).resolve("wuming/mpv.conf")
    }
}
