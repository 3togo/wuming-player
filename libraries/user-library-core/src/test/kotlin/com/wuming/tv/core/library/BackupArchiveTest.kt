package com.wuming.tv.core.library

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.IOException

class BackupArchiveTest {
    @Test fun `failed compression and replacement preserve previous archive`() {
        val dir = Files.createTempDirectory("backup-test")
        try {
            val target = dir.resolve("daily.gz")
            val good = BackupArchive()
            good.write(target, "previous".toByteArray())
            val failures = listOf(
                BackupArchive(publish = { _, _ -> throw IOException("rename failed") }),
                BackupArchive(compress = { path, _ -> Files.write(path, byteArrayOf(1)); throw IOException("disk full") }),
                BackupArchive(compress = { path, _ -> Files.write(path, byteArrayOf(1)) }),
            )
            failures.forEach { failing ->
                assertThrows(IOException::class.java) { failing.write(target, "new".toByteArray()) }
                assertEquals("previous", String(good.read(target)))
                Files.list(dir).use { assertEquals(1L, it.count()) }
            }
            good.write(target, "new".toByteArray())
            assertEquals("new", String(good.read(target)))
        } finally { dir.toFile().deleteRecursively() }
    }
}
