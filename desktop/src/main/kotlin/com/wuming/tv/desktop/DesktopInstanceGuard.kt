package com.wuming.tv.desktop

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Per-user GUI process lock. Never delete its file: another process may still own the inode. */
internal class DesktopInstanceGuard private constructor(
    private val channel: FileChannel?, private val lock: FileLock?,
) : AutoCloseable {
    override fun close() {
        lock?.let { if (it.isValid) it.release() }
        channel?.close()
    }

    companion object {
        fun acquire(allowMultiple: Boolean, path: Path = defaultPath()): DesktopInstanceGuard? {
            Files.createDirectories(path.toAbsolutePath().parent)
            val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            try {
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock != null) return DesktopInstanceGuard(channel, lock)
                channel.close()
                return if (allowMultiple) DesktopInstanceGuard(null, null) else null
            } catch (error: Exception) {
                channel.close()
                throw error
            }
        }

        private fun defaultPath(): Path {
            val config = System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".config")
            return config.resolve("tvbox-desktop/gui-instance.lock")
        }
    }
}
