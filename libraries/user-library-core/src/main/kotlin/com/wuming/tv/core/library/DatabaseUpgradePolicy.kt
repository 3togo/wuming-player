package com.wuming.tv.core.library

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read-only preflight before SQLite/Room may modify a legacy database. */
object DatabaseUpgradePolicy {
    @JvmStatic
    @Throws(IOException::class)
    fun requireSupported(file: File, oldest: Int, current: Int) {
        if (!file.exists() || file.length() == 0L) return
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(100)
            input.readFully(header)
            val baseVersion = headerVersion(header)
            // Room uses WAL. After process death the main file can still say version 0,
            // or an older version, while the committed page-one image lives in the WAL.
            val version = if (header[18] == 2.toByte() && header[19] == 2.toByte()) {
                committedWalVersion(File(file.path + "-wal")) ?: baseVersion
            } else baseVersion
            if (version !in oldest..current) throw UnsupportedVersion(version, oldest, current)
        }
    }

    private fun headerVersion(header: ByteArray): Int {
        if (String(header, 0, 16, Charsets.US_ASCII) != "SQLite format 3\u0000")
            throw IOException("Database header is unreadable; original files retained")
        return ByteBuffer.wrap(header).getInt(60)
    }

    // SQLite's WAL reader rule: the last valid page one followed by a commit wins.
    // Validate the entire checksum chain so stale, partial and uncommitted tails
    // cannot bypass the version guard. Never open SQLite or modify its sidecars here.
    // Format: https://www.sqlite.org/fileformat2.html#walformat
    private fun committedWalVersion(file: File): Int? {
        if (!file.exists()) return null
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length < 32) return@use null
            val header = ByteArray(32).also(input::readFully)
            val fields = ByteBuffer.wrap(header)
            val magic = fields.getInt(0)
            if (magic != 0x377f0682 && magic != 0x377f0683) return@use null
            val pageSize = fields.getInt(8)
            if (fields.getInt(4) != 3007000 || pageSize !in 512..65536
                || pageSize and (pageSize - 1) != 0) return@use null
            val checksum = WalChecksum(if (magic == 0x377f0682) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            checksum.update(header, 24)
            if (!checksum.matches(fields, 24)) return@use null
            val salt1 = fields.getInt(16)
            val salt2 = fields.getInt(20)
            val frame = ByteArray(24)
            val page = ByteArray(pageSize)
            var pendingVersion: Int? = null
            var committedVersion: Int? = null
            while (length - input.filePointer >= 24L + pageSize) {
                input.readFully(frame)
                val values = ByteBuffer.wrap(frame)
                if (values.getInt(0) == 0 || values.getInt(8) != salt1 || values.getInt(12) != salt2) break
                input.readFully(page)
                checksum.update(frame, 8)
                checksum.update(page, page.size)
                if (!checksum.matches(values, 16)) break
                if (values.getInt(0) == 1) pendingVersion = headerVersion(page)
                if (values.getInt(4) != 0) committedVersion = pendingVersion
            }
            // A concurrent checkpoint/reset makes this snapshot unsuitable for preflight.
            input.seek(0)
            val after = ByteArray(32).also(input::readFully)
            if (!header.contentEquals(after) || input.length() < length)
                throw IOException("Database WAL changed during preflight; retry without modifying originals")
            committedVersion
        }
    }

    private class WalChecksum(private val order: ByteOrder) {
        private var first = 0
        private var second = 0
        fun update(bytes: ByteArray, length: Int) {
            val words = ByteBuffer.wrap(bytes).order(order)
            repeat(length / 8) {
                first += words.int + second
                second += words.int + first
            }
        }
        fun matches(fields: ByteBuffer, offset: Int) =
            first == fields.getInt(offset) && second == fields.getInt(offset + 4)
    }

    class UnsupportedVersion(val version: Int, oldest: Int, current: Int) : IOException(
        "Database version $version requires recovery (supported $oldest–$current); original files retained")
}
