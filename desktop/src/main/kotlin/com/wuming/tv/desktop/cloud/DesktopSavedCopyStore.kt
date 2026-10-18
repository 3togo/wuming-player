package com.wuming.tv.desktop.cloud

import com.wuming.tv.core.base.AtomicFilePublisher
import com.wuming.tv.core.cloud.SavedCopyRecord
import com.wuming.tv.core.cloud.SavedCopyRegistry
import com.wuming.tv.core.cloud.SavedCopyStore
import com.wuming.tv.core.base.Digests
import com.google.gson.Gson
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Atomic account-scoped ledger. Corrupt/unwritable records fail closed before remote mutations. */
internal class DesktopSavedCopyStore(private val path: Path) : SavedCopyStore {
    override fun <T> locked(action: () -> T): T = synchronized(lock) {
        Files.createDirectories(path.toAbsolutePath().parent)
        FileChannel.open(path.resolveSibling(path.fileName.toString() + ".lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lease = channel.tryLock() ?: throw IOException("Another desktop instance is managing cloud copies.")
            lease.use { action() }
        }
    }

    override fun read(): List<SavedCopyRecord> {
        if (!Files.exists(path)) return emptyList()
        if (Files.size(path) > 256 * 1024) throw IOException("Saved-copy ledger is too large.")
        return try {
            val entries = Gson().fromJson(Files.readString(path), Array<SavedCopyRecord>::class.java).toList()
            require(entries.size <= 128)
            entries.forEach {
                require(Digests.isSha256Hex(it.account) && Digests.isSha256Hex(it.source))
                require((it.file.isEmpty() || it.file.matches(Regex("[A-Za-z0-9_-]{1,256}"))) && it.folder.matches(Regex("[A-Za-z0-9_-]{1,256}")))
                require(it.task == null || it.task?.matches(Regex("[A-Za-z0-9_-]{1,256}")) == true)
                require(it.file.isEmpty() || it.task == null)
                require(it.updated >= 0 && it.owners.isNotEmpty() && it.owners.all { pid -> pid > 0 })
            }
            entries
        } catch (error: Exception) { throw IOException("Saved-copy ledger is invalid; automatic cleanup is disabled.", error) }
    }

    override fun write(records: List<SavedCopyRecord>) {
        AtomicFilePublisher.publish(path, "saved-copies-", ownerOnly = true) { temporary ->
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(Gson().toJson(records).toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
        }
        FileChannel.open(path.toAbsolutePath().parent, StandardOpenOption.READ).use { it.force(true) }
    }

    companion object {
        private val lock = Any()
        fun openDefault(): SavedCopyRegistry {
            val root = System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".config")
            return SavedCopyRegistry(DesktopSavedCopyStore(root.resolve("tvbox-desktop/saved-copies.json")),
                ProcessHandle.current().pid(), { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) })
        }
    }
}
