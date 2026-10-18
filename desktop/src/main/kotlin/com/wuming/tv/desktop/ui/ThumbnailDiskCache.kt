package com.wuming.tv.desktop.ui

import com.wuming.tv.core.base.Digests
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import javax.imageio.ImageIO
import javax.swing.ImageIcon

/** Bounded cache of validated, already-scaled poster pixels. URLs and provider headers are never stored. */
internal class ThumbnailDiskCache(
    private val directory: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val maximumAge: Duration = Duration.ofDays(30),
    private val maximumEntries: Int = 512,
    private val maximumBytes: Long = 64L * 1024 * 1024,
) {
    @Synchronized
    fun read(key: String): ImageIcon? {
        val path = path(key)
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val age = clock.millis() - Files.getLastModifiedTime(path).toMillis()
            if (age > maximumAge.toMillis() || age < 0) {
                Files.deleteIfExists(path)
                return null
            }
            val image = ImageIO.read(path.toFile()) ?: error("Unreadable cached poster")
            require(image.width == PosterImages.PIXEL_WIDTH && image.height == PosterImages.PIXEL_HEIGHT)
            Files.setLastModifiedTime(path, FileTime.fromMillis(clock.millis()))
            PosterImages.icon(image)
        }.getOrElse {
            runCatching { Files.deleteIfExists(path) }
            null
        }
    }

    @Synchronized
    fun write(key: String, icon: ImageIcon) {
        val image = PosterImages.pixels(icon) ?: return
        Files.createDirectories(directory)
        runCatching { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")) }
        val target = path(key)
        val temporary = Files.createTempFile(directory, ".poster-", ".png")
        try {
            require(ImageIO.write(image, "png", temporary.toFile())) { "PNG encoder unavailable" }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.setLastModifiedTime(target, FileTime.fromMillis(clock.millis()))
            trim()
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized
    fun clear() {
        if (!Files.isDirectory(directory)) return
        Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".png") }.forEach(Files::deleteIfExists) }
    }

    private fun path(key: String): Path = directory.resolve(digest(key) + ".png")

    private fun trim() {
        val files = Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".png") }
                .map { CachedFile(it, Files.getLastModifiedTime(it).toMillis(), Files.size(it)) }
                .sorted(compareByDescending(CachedFile::modifiedAtMillis))
                .toList()
        }
        var retainedBytes = 0L
        files.forEachIndexed { index, file ->
            retainedBytes += file.bytes
            if (index >= maximumEntries || retainedBytes > maximumBytes) Files.deleteIfExists(file.path)
        }
    }

    private fun digest(value: String): String = Digests.sha256Hex(value)

    private data class CachedFile(val path: Path, val modifiedAtMillis: Long, val bytes: Long)

    companion object {
        fun openDefault(): ThumbnailDiskCache {
            val cacheRoot = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".cache")
            return ThumbnailDiskCache(cacheRoot.resolve("tvbox-desktop/thumbnails"))
        }
    }
}
