package com.wuming.tv.core.base

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Publish a file so a reader never sees a half-written one: write to a temporary sibling, then
 * move it over the target.
 *
 * Every persistent desktop store (caches, configuration, library, viewing records, saved-copy
 * ledger, renderer profiles) needs exactly this discipline, and a crash mid-write must leave the
 * previous copy intact rather than truncate it.
 */
object AtomicFilePublisher {

    /**
     * Writes through [write] to a temporary file in the target's directory and moves it onto
     * [target]. The temporary file is always removed, including when [write] fails.
     *
     * @param ownerOnly restricts the temporary file — and, when it is created here, the directory —
     * to the current user, which matters for anything derived from credentials.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun publish(target: Path, prefix: String = ".publish-", ownerOnly: Boolean = false, write: (Path) -> Unit) {
        val directory = target.toAbsolutePath().parent
        Files.createDirectories(directory)
        if (ownerOnly) ownerOnly(directory)
        val temporary = Files.createTempFile(directory, prefix, ".tmp")
        try {
            if (ownerOnly) ownerOnly(temporary)
            write(temporary)
            move(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Moves [temporary] onto [target], falling back to a plain replace across filesystems. */
    @JvmStatic
    @Throws(IOException::class)
    fun move(temporary: Path, target: Path) {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Best effort: filesystems without POSIX permissions simply keep what they already allow. */
    @JvmStatic
    fun ownerOnly(path: Path) {
        runCatching {
            val permissions = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            if (Files.isDirectory(path)) permissions += PosixFilePermission.OWNER_EXECUTE
            Files.setPosixFilePermissions(path, permissions)
        }
    }
}
