package com.github.catvod.utils

import android.os.Environment
import com.github.catvod.Init
import com.github.catvod.Init.context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.Arrays
import java.util.Locale

open class Path {
    // Keep generated static forwarders non-final so existing Java subclasses can hide them.
    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        private val TAG: String = Path::class.java.getSimpleName()

        private fun mkdir(file: File?): File? {
            if (file == null || file.exists()) return file
            if (file.mkdirs()) Log.d(TAG, "Created dir:" + file)
            return file
        }

        @JvmStatic
        open fun exists(path: String): Boolean = File(path.replace("file://", "")).exists()

        @JvmStatic
        open fun exists(file: File?): Boolean = file != null && file.exists() && file.length() > 0

        @JvmStatic
        open fun root(): File = Environment.getExternalStorageDirectory()

        @JvmStatic
        open fun cache(): File = context()!!.getCacheDir()

        @JvmStatic
        open fun files(): File = context()!!.getFilesDir()

        @JvmStatic
        open fun rootPath(): String = root().getAbsolutePath()

        @JvmStatic
        open fun tv(): File {
            // Always use app-internal storage to avoid needing WRITE_EXTERNAL_STORAGE
            // permission on Android 9 and below. External storage access requires runtime
            // permission which may not be granted when App.onCreate() runs, causing crashes.
            // On Android 11+ (API 30+), MANAGE_EXTERNAL_STORAGE would be needed for external
            // storage, which requires special Play Store approval.
            return mkdir(File(files(), "TV"))!!
        }

        @JvmStatic
        open fun so(): File = mkdir(File(files().toString() + File.separator + "so"))!!

        @JvmStatic
        open fun js(): File = mkdir(File(cache().toString() + File.separator + "js"))!!

        @JvmStatic
        open fun py(): File = mkdir(File(cache().toString() + File.separator + "py"))!!

        @JvmStatic
        open fun jar(): File = mkdir(File(cache().toString() + File.separator + "jar"))!!

        @JvmStatic
        open fun exo(): File = mkdir(File(cache().toString() + File.separator + "exo"))!!

        @JvmStatic
        open fun epg(): File = mkdir(File(cache().toString() + File.separator + "epg"))!!

        @JvmStatic
        open fun jpa(): File = mkdir(File(cache().toString() + File.separator + "jpa"))!!

        @JvmStatic
        open fun thunder(): File = mkdir(File(cache().toString() + File.separator + "thunder"))!!

        @JvmStatic
        open fun root(name: String): File = File(root(), name)

        @JvmStatic
        open fun root(child: String, name: String): File = File(mkdir(File(root(), child)), name)

        @JvmStatic
        open fun cache(name: String): File = File(cache(), name)

        @JvmStatic
        open fun files(name: String): File = File(files(), name)

        @JvmStatic
        open fun epg(name: String): File = File(epg(), name)

        @JvmStatic
        open fun js(name: String): File = File(js(), name)

        @JvmStatic
        open fun py(name: String): File = File(py(), name)

        @JvmStatic
        open fun jar(name: String?): File = File(jar(), Util.md5(name) + ".jar")

        @JvmStatic
        open fun thunder(name: String): File = mkdir(File(thunder(), name))!!

        @JvmStatic
        open fun local(path: String): File {
            var path: String = path
            path = path.replace("file:/", "")
            val file: File = File(root(), path)
            return if (file.exists()) file else File(path)
        }

        @JvmStatic
        open fun read(file: File?): String {
            try {
                return String(readToByte(file), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                return ""
            }
        }

        @JvmStatic
        open fun read(`is`: InputStream?): String {
            try {
                return String(readToByte(`is`!!), StandardCharsets.UTF_8)
            } catch (e: IOException) {
                return ""
            }
        }

        @JvmStatic
        open fun readToByte(file: File?): ByteArray {
            try {
                FileInputStream(file).use { `is` ->
                    return readToByte(`is`)
                }
            } catch (e: IOException) {
                return ByteArray(0)
            }
        }

        @Throws(IOException::class)
        private fun readToByte(`is`: InputStream): ByteArray {
            `is`.use { input ->
                ByteArrayOutputStream().use { bos ->
                    var read: Int
                    val buffer: ByteArray = ByteArray(16384)
                    while ((input.read(buffer).also { read = it }) != -1) bos.write(buffer, 0, read)
                    return bos.toByteArray()
                }
            }
        }

        @JvmStatic
        open fun write(file: File, `is`: InputStream): File {
            try {
                `is`.use { input ->
                    FileOutputStream(create(file)).use { output ->
                        var read: Int
                        val buffer: ByteArray = ByteArray(16384)
                        while ((input.read(buffer).also { read = it }) != -1) {
                            output.write(
                                buffer,
                                0,
                                read
                            )
                        }
                        return file
                    }
                }
            } catch (e: IOException) {
                return file
            }
        }

        @JvmStatic
        open fun write(file: File, data: ByteArray?): File {
            try {
                FileOutputStream(create(file)).use { fos ->
                    fos.write(data)
                    fos.flush()
                    return file
                }
            } catch (e: IOException) {
                return file
            }
        }

        @JvmStatic
        open fun move(`in`: File, out: File) {
            if (`in`.renameTo(out)) return
            copy(`in`, out)
            clear(`in`)
        }

        @JvmStatic
        open fun copy(`in`: File?, out: File) {
            try {
                copy(FileInputStream(`in`), out)
            } catch (ignored: IOException) {
            }
        }

        @JvmStatic
        open fun copy(`in`: InputStream, out: File) {
            try {
                `in`.use { input ->
                    FileOutputStream(create(out)).use { output ->
                        var read: Int
                        val buffer: ByteArray = ByteArray(16384)
                        while ((input.read(buffer).also { read = it }) != -1) {
                            output.write(
                                buffer,
                                0,
                                read
                            )
                        }
                    }
                }
            } catch (ignored: IOException) {
            }
        }

        @JvmStatic
        open fun sort(files: Array<File>) {
            Arrays.sort<File>(
                files,
                Comparator { o1: File?, o2: File? ->
                    if (o1!!.isDirectory() && o2!!.isFile()) return@Comparator -1
                    if (o1.isFile() && o2!!.isDirectory()) return@Comparator 1
                    o1.getName().lowercase().compareTo(o2!!.getName().lowercase())
                }
            )
        }

        @JvmStatic
        open fun list(dir: File): MutableList<File> {
            val files: Array<File>? = dir.listFiles()
            if (files != null) sort(files)
            return if (files == null) ArrayList<File>() else Arrays.asList<File>(*files)
        }

        @JvmStatic
        open fun clear(dir: File?) {
            if (dir == null) return
            if (dir.isDirectory()) for (file: File? in list(dir)) clear(file)
            if (dir.delete()) Log.d(TAG, "Deleted:" + dir)
        }

        @JvmStatic
        open fun create(file: File): File {
            try {
                val parent: File? = file.getParentFile()
                if (parent != null) mkdir(parent)
                if (file.exists()) clear(file)
                if (file.createNewFile()) Log.d(TAG, "Create:" + file)
                file.setReadable(true)
                file.setWritable(true)
                return file
            } catch (e: IOException) {
                return file
            }
        }
    }
}
