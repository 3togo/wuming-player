package com.wuming.tv.core.library

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Read-only export of a closed/quiescent SQLite database and its recovery sidecars. */
object DatabaseRecoveryArchive {
    @JvmStatic
    @Throws(IOException::class)
    fun write(database: File, output: OutputStream) {
        if (!database.isFile) throw IOException("Database missing")
        ZipOutputStream(output).use { zip ->
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val file = File(database.path + suffix)
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry("tv$suffix"))
                Files.copy(file.toPath(), zip)
                zip.closeEntry()
            }
            zip.finish()
        }
    }
}
