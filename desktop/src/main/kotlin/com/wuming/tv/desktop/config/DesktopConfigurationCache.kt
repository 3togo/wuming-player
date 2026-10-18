package com.wuming.tv.desktop.config

import com.wuming.tv.core.base.AtomicFilePublisher
import com.wuming.tv.core.base.Digests
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration

class DesktopConfigurationCache internal constructor(
    private val root: Path = defaultRoot(),
    private val clock: Clock = Clock.systemUTC(),
) {
    internal fun read(location: String): ResolvedPayload? = runCatching {
        val target = path(location)
        require(Files.size(target) <= 16L * 1024 * 1024)
        val age = Duration.between(Files.getLastModifiedTime(target).toInstant(), clock.instant())
        require(!age.isNegative && age < Duration.ofDays(7))
        val lines = Files.readAllLines(target, StandardCharsets.UTF_8)
        require(lines.size >= 3 && lines.first() == CACHE_VERSION)
        val json = lines.drop(2).joinToString("\n")
        require(json.trimStart().startsWith("{"))
        ResolvedPayload(lines[1], json)
    }.getOrNull()

    internal fun readFresh(location: String, maximumAge: Duration): ResolvedPayload? {
        val target = path(location)
        val fresh = runCatching {
            val modified = Files.getLastModifiedTime(target).toInstant()
            !modified.isAfter(clock.instant()) && modified.isAfter(clock.instant().minus(maximumAge))
        }.getOrDefault(false)
        return if (fresh) read(location) else null
    }

    internal fun touch(location: String) {
        runCatching { Files.setLastModifiedTime(path(location), FileTime.from(clock.instant())) }
    }

    internal fun write(location: String, payload: ResolvedPayload) {
        runCatching {
            val target = path(location)
            val content = "$CACHE_VERSION\n${payload.location}\n${payload.json}"
            require(content.toByteArray(StandardCharsets.UTF_8).size <= 16 * 1024 * 1024)
            AtomicFilePublisher.publish(target, "config-", ownerOnly = true) { Files.writeString(it, content) }
            Files.setLastModifiedTime(target, FileTime.from(clock.instant()))
            val files = Files.list(root).use { stream -> stream.filter { it.fileName.toString().endsWith(".conf") }
                .toList().sortedByDescending { Files.getLastModifiedTime(it).toMillis() } }
            var bytes = 0L
            files.forEachIndexed { index, file ->
                bytes += Files.size(file)
                if (index >= 32 || bytes > 32L * 1024 * 1024) Files.deleteIfExists(file)
            }
        }
    }

    internal fun clear() {
        if (!Files.isDirectory(root)) return
        Files.list(root).use { files -> files.filter { it.fileName.toString().endsWith(".conf") }.forEach(Files::deleteIfExists) }
    }

    private fun path(location: String): Path = root.resolve("${Digests.sha256Hex(location)}.conf")

    private companion object {
        const val CACHE_VERSION = "TVBOX-DESKTOP-CONFIG-1"

        fun defaultRoot(): Path {
            val xdg = System.getenv("XDG_CACHE_HOME")?.trim().orEmpty()
            val base = if (xdg.isNotEmpty()) Path.of(xdg) else Path.of(System.getProperty("user.home"), ".cache")
            return base.resolve("tvbox-desktop").resolve("configurations")
        }
    }
}
