package com.wuming.tv.core.library
import java.nio.file.Files
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class DatabaseUpgradePolicyTest {
    @Test fun `unsupported database and sidecars remain byte identical`() {
        val directory = Files.createTempDirectory("database-recovery")
        try {
            val path = directory.resolve("tv")
            for (version in listOf(29, 30, 46, 47)) {
                val bytes = ByteArray(100)
                "SQLite format 3\u0000".toByteArray().copyInto(bytes)
                ByteBuffer.wrap(bytes).putInt(60, version)
                Files.write(path, bytes)
                Files.write(directory.resolve("tv-wal"), byteArrayOf(7, 8))
                if (version in 30..46) DatabaseUpgradePolicy.requireSupported(path.toFile(), 30, 46)
                else assertThrows(DatabaseUpgradePolicy.UnsupportedVersion::class.java) {
                    DatabaseUpgradePolicy.requireSupported(path.toFile(), 30, 46)
                }
                assertArrayEquals(bytes, Files.readAllBytes(path))
                assertArrayEquals(byteArrayOf(7, 8), Files.readAllBytes(directory.resolve("tv-wal")))
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
