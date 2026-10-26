package com.wuming.tv.core.library
import java.nio.file.Files
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class DatabaseUpgradePolicyTest {
    @Test fun `committed WAL schema overrides the zero version in the main file`() {
        for (fixture in listOf("supported.wal", "big-endian.wal")) withWal(fixture) { file, _ ->
            assertEquals(0, ByteBuffer.wrap(file.readBytes()).getInt(60))
            DatabaseUpgradePolicy.requireSupported(file, 30, 46)
        }
    }

    @Test fun `newer committed WAL schema still requires recovery and preserves all bytes`() {
        withWal("newer.wal") { file, _ ->
            assertEquals(47, assertThrows(DatabaseUpgradePolicy.UnsupportedVersion::class.java) {
                DatabaseUpgradePolicy.requireSupported(file, 30, 46)
            }.version)
        }
    }

    @Test fun `incomplete commit cannot promote an uncommitted page one`() {
        withWal("supported.wal", { it.copyOf(it.size - 536) }) { file, _ ->
            assertEquals(0, assertThrows(DatabaseUpgradePolicy.UnsupportedVersion::class.java) {
                DatabaseUpgradePolicy.requireSupported(file, 30, 46)
            }.version)
        }
    }

    @Test fun `partial checksum damaged and stale salt tails retain the last valid commit`() {
        val tails: List<(ByteArray) -> ByteArray> = listOf(
            { it.copyOf(it.size - 1) },
            { it.apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() } },
            { it.apply { val salt = size - 536 + 8; this[salt] = (this[salt].toInt() xor 1).toByte() } },
        )
        for (damage in tails) withWal("newer.wal", damage) { file, _ ->
            DatabaseUpgradePolicy.requireSupported(file, 30, 46)
        }
    }

    @Test fun `invalid WAL header cannot allow an unsupported main schema`() {
        withWal("supported.wal", { it.apply { this[24] = (this[24].toInt() xor 1).toByte() } }) { file, _ ->
            assertEquals(0, assertThrows(DatabaseUpgradePolicy.UnsupportedVersion::class.java) {
                DatabaseUpgradePolicy.requireSupported(file, 30, 46)
            }.version)
        }
    }

    private fun withWal(name: String, mutate: (ByteArray) -> ByteArray = { it },
                        check: (java.io.File, java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("wal-preflight").toFile()
        try {
            val main = java.io.File(directory, "tv")
            val wal = java.io.File(directory, "tv-wal")
            val mainBytes = javaClass.getResourceAsStream("/database-wal/main.db")!!.use { it.readBytes() }
            val walBytes = mutate(javaClass.getResourceAsStream("/database-wal/$name")!!.use { it.readBytes() })
            main.writeBytes(mainBytes)
            wal.writeBytes(walBytes)
            check(main, wal)
            assertArrayEquals(mainBytes, main.readBytes())
            assertArrayEquals(walBytes, wal.readBytes())
            assertEquals(setOf("tv", "tv-wal"), directory.list()!!.toSet())
        } finally { directory.deleteRecursively() }
    }

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
