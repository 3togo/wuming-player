package com.wuming.tv.core.library

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Read-only preflight before SQLite/Room may modify a legacy database. */
object DatabaseUpgradePolicy {
    @JvmStatic
    @Throws(IOException::class)
    fun requireSupported(file: File, oldest: Int, current: Int) {
        if (!file.exists() || file.length() == 0L) return
        RandomAccessFile(file, "r").use { input ->
            val signature = ByteArray(16)
            input.readFully(signature)
            if (String(signature, Charsets.US_ASCII) != "SQLite format 3\u0000")
                throw IOException("Database header is unreadable; original files retained")
            input.seek(60)
            val version = input.readInt()
            if (version !in oldest..current) throw UnsupportedVersion(version, oldest, current)
        }
    }
    class UnsupportedVersion(val version: Int, oldest: Int, current: Int) : IOException(
        "Database version $version requires recovery (supported $oldest–$current); original files retained")
}
