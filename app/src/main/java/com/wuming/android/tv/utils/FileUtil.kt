package com.wuming.android.tv.utils

import android.content.Intent
import android.net.Uri
import android.os.StatFs
import androidx.core.content.FileProvider
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.impl.Callback
import com.wuming.android.tv.player.extractor.CacheTree
import com.github.catvod.utils.Path
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLConnection
import java.text.DecimalFormat
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile

object FileUtil {

    private val SIZE_FORMAT = DecimalFormat("#,##0.#")

    @JvmStatic
    fun getWall(index: Int): File = Path.files("wallpaper_$index")

    @JvmStatic
    fun getWallCache(): File = Path.files("wallpaper_cache")

    @JvmStatic
    fun openFile(file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setDataAndType(getShareUri(file), getMimeType(file.name))
        App.get().startActivity(intent)
    }

    @JvmStatic
    fun gzipCompress(target: File) {
        val buffer = ByteArray(16384)
        val gzFile = File(target.absolutePath + ".gz")
        try {
            FileInputStream(target).use { input ->
                GZIPOutputStream(FileOutputStream(gzFile)).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } > 0) {
                        output.write(buffer, 0, read)
                    }
                }
            }
            Path.clear(target)
        } catch (e: IOException) {
            AppLog.e("FileUtil", "gzip compress failed", e)
            gzFile.delete()
        }
    }

    @JvmStatic
    fun gzipDecompress(target: File, path: File) {
        val buffer = ByteArray(16384)
        try {
            GZIPInputStream(BufferedInputStream(FileInputStream(target))).use { input ->
                BufferedOutputStream(FileOutputStream(path)).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("FileUtil", "gzip decompress failed", e)
            path.delete()
        }
    }

    @JvmStatic
    fun zipDecompress(target: File, path: File) {
        try {
            ZipFile(target).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val out = File(path, entry.name)
                    val canonicalPath = out.canonicalPath
                    val canonicalDir = path.canonicalPath + File.separator
                    if (!canonicalPath.startsWith(canonicalDir)) {
                        throw IOException("Zip entry outside target directory: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        zip.getInputStream(entry).use { input ->
                            Path.copy(input, out)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("FileUtil", "zip decompress failed", e)
        }
    }

    @JvmStatic
    fun clearCache(callback: Callback) {
        Task.execute {
            try {
                CacheTree.children(Path.cache()).forEach { CacheTree.delete(it) }
                App.post { callback.success() }
            } catch (error: Exception) {
                AppLog.e("FileUtil", "Cache clear failed", error)
                App.post { callback.error(ResUtil.getString(R.string.cache_clear_failed)) }
            }
        }
    }

    @JvmStatic
    fun getCacheSize(callback: Callback) {
        Task.execute {
            try {
                val usage = byteCountToDisplaySize(getDirectorySize(Path.cache()))
                App.post { callback.success(usage) }
            } catch (error: Exception) {
                AppLog.e("FileUtil", "Cache size scan failed", error)
                App.post { callback.error(ResUtil.getString(R.string.cache_size_unavailable)) }
            }
        }
    }

    @JvmStatic
    fun getDirectorySize(dir: File?): Long {
        return if (dir == null) 0 else CacheTree.size(dir)
    }

    @JvmStatic
    fun getAvailableStorageSpace(file: File): Long {
        return try {
            val stat = StatFs(file.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            0
        }
    }

    @JvmStatic
    fun getShareUri(path: String): Uri = getShareUri(File(path.replace("file://", "")))

    @JvmStatic
    fun getShareUri(file: File): Uri =
        FileProvider.getUriForFile(App.get(), App.get().packageName + ".provider", file)

    private fun getMimeType(fileName: String): String {
        val mimeType = URLConnection.guessContentTypeFromName(fileName)
        return if (BeanUtils.isEmpty(mimeType)) "*/*" else mimeType
    }

    @JvmStatic
    fun byteCountToDisplaySize(size: Long): String {
        if (size <= 0) return ResUtil.getString(R.string.none)
        val units = arrayOf("bytes", "KB", "MB", "GB", "TB")
        val digitGroups = kotlin.math.min((Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt(), units.size - 1)
        return SIZE_FORMAT.format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }
}
