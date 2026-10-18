package com.wuming.android.tv.utils

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.util.ArrayList
import java.util.Comparator
import java.util.concurrent.TimeUnit

/** Bounded app-private cache cleanup activated only by actual storage pressure. */
object StoragePressureManager {
    private const val MIN_FREE_BYTES = 256L * 1024 * 1024
    private val CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

    @Volatile private var lastCheckAt = 0L

    @JvmStatic @Synchronized
    fun relieve(context: Context?): Report {
        if (context == null) return Report(false, 0, 0, 0)
        val now = System.currentTimeMillis()
        val cache = context.cacheDir
        val before = usable(cache)
        val total = cache.totalSpace
        if (before <= 0 || total <= 0) return Report(false, before, before, 0)
        val lowWatermark = Math.max(MIN_FREE_BYTES, total / 20)
        if (now - lastCheckAt < CHECK_INTERVAL_MS || before >= lowWatermark) return Report(false, before, before, 0)
        lastCheckAt = now
        val target = Math.max(MIN_FREE_BYTES * 2, total / 10)
        val reclaimed = cleanCache(cache, Math.max(0, target - before))
        var after = usable(cache)
        if (after < lowWatermark) {
            // The HTTP disk cache also lives under cacheDir.
            try {
                com.github.catvod.net.OkHttp
                    .get()
                    .clear()
            } catch (_: RuntimeException) {
            }
            after = usable(cache)
        }
        return Report(true, before, after, Math.max(reclaimed, after - before))
    }

    @JvmStatic fun cleanCache(
        cacheDir: File?,
        bytesNeeded: Long,
    ): Long {
        if (cacheDir == null || bytesNeeded <= 0 || !cacheDir.isDirectory) return 0
        try {
            val root = cacheDir.canonicalPath + File.separator
            val candidates = ArrayList<File>()
            val children = cacheDir.listFiles() ?: return 0
            val recentCutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30)
            for (child in children) {
                // SimpleCache owns this directory; its own LRU handles it.
                if (child.name == "exo") continue
                if (newestModified(child, root) >= recentCutoff) continue
                candidates.add(child)
            }
            candidates.sortWith(Comparator.comparingLong(File::lastModified))
            var reclaimed = 0L
            for (candidate in candidates) {
                if (reclaimed >= bytesNeeded) break
                val size = sizeInside(candidate, root)
                if (deleteInside(candidate, root)) reclaimed += size
            }
            return reclaimed
        } catch (_: Exception) {
            return 0
        }
    }

    private fun deleteInside(
        file: File,
        root: String,
    ): Boolean {
        val path = file.canonicalPath
        if (!path.startsWith(root) || path == root.substring(0, root.length - 1)) return false
        if (Files.isSymbolicLink(file.toPath())) return file.delete()
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            for (child in children) if (!deleteInside(child, root)) return false
        }
        return !file.exists() || file.delete()
    }

    private fun sizeInside(
        file: File,
        root: String,
    ): Long {
        val path = file.canonicalPath
        if (!path.startsWith(root) || Files.isSymbolicLink(file.toPath())) return 0
        if (!file.isDirectory) return Math.max(0, file.length())
        var size = 0L
        val children = file.listFiles()
        if (children != null) for (child in children) size += sizeInside(child, root)
        return size
    }

    private fun newestModified(
        file: File,
        root: String,
    ): Long {
        val path = file.canonicalPath
        if (!path.startsWith(root) || Files.isSymbolicLink(file.toPath())) return Long.MAX_VALUE
        var newest = file.lastModified()
        if (!file.isDirectory) return newest
        val children = file.listFiles() ?: return Long.MAX_VALUE
        for (child in children) newest = Math.max(newest, newestModified(child, root))
        return newest
    }

    private fun usable(file: File?): Long =
        try {
            file?.usableSpace ?: 0
        } catch (_: RuntimeException) {
            0
        }

    @JvmRecord data class Report(
        val pressured: Boolean,
        val freeBefore: Long,
        val freeAfter: Long,
        val reclaimedBytes: Long,
    )
}
