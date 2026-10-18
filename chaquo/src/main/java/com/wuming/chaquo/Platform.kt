package com.wuming.chaquo

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.chaquo.python.Python
import com.chaquo.python.internal.Common
import com.github.catvod.Init
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import org.json.JSONException
import org.json.JSONObject

open class Platform : Python.Platform() {
    private val context = Init.context()!!
    private val sp = context.getSharedPreferences(Common.ASSET_DIR, Context.MODE_PRIVATE)
    private val am = context.assets
    private val buildJson: JSONObject
    private val abi: String

    init {
        try {
            buildJson =
                am.open(Common.ASSET_DIR + "/" + Common.ASSET_BUILD_JSON).use {
                    JSONObject(streamToString(it))
                }
            loadNativeLibs()
        } catch (
            e: IOException
        ) {
            throw RuntimeException(e)
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        var selected: String? = null
        for (candidate in supportedAbis) {
            try {
                am.open(
                    Common.ASSET_DIR + "/" + Common.assetZip(Common.ASSET_STDLIB, candidate)
                ).use {
                    selected = candidate
                }
                break
            } catch (_: IOException) { }
        }
        abi = selected ?: throw RuntimeException("No supported ABI found in: $supportedAbis")
    }

    override fun getPath(): String {
        val assetDir = File(context.filesDir, Common.ASSET_DIR).absolutePath
        val pathAssets =
            listOf(
                Common.assetZip(Common.ASSET_STDLIB, Common.ABI_COMMON),
                Common.assetZip(Common.ASSET_BOOTSTRAP),
                Common.ASSET_BOOTSTRAP_NATIVE + "/" + abi
            )
        val pythonPath = pathAssets.joinToString(":") { "$assetDir/$it" }
        val extractionList = ArrayList(pathAssets)
        extractionList.add(Common.ASSET_CACERT)
        try {
            deleteObsolete(context.filesDir, OBSOLETE_FILES)
            deleteObsolete(context.cacheDir, OBSOLETE_CACHE)
            extractAssets(extractionList)
        } catch (
            e: IOException
        ) {
            throw RuntimeException(e)
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }
        return pythonPath
    }

    override fun onStart(py: Python) {
        val appPath = arrayOf(
            Common.ASSET_APP,
            Common.ASSET_REQUIREMENTS,
            Common.ASSET_STDLIB + "-" + abi
        )
        py.getModule("java.android").callAttr("initialize", context, buildJson, appPath)
    }

    private fun deleteObsolete(baseDir: File, filenames: Array<String>) {
        for (filename in filenames) {
            deleteRecursive(
                File(
                    baseDir,
                    Common.ASSET_DIR + "/" + filename.replace("<abi>", abi)
                )
            )
        }
    }

    @Throws(IOException::class, JSONException::class)
    private fun extractAssets(assets: List<String>) {
        val assetsJson = buildJson.getJSONObject("assets")
        val unextracted = HashSet(assets)
        val directories = HashSet<String>()
        val editor = sp.edit()
        val keys = assetsJson.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            for (asset in assets) {
                if (path == asset || path.startsWith("$asset/")) {
                    extractAsset(assetsJson, editor, path)
                    unextracted.remove(asset)
                    if (path.startsWith("$asset/")) directories.add(asset)
                    break
                }
            }
        }
        if (unextracted.isNotEmpty()) throw RuntimeException("Failed to find assets: $unextracted")
        for (dir in directories) cleanExtractedDir(dir, assetsJson)
        editor.apply()
    }

    @Throws(IOException::class, JSONException::class)
    private fun extractAsset(
        assetsJson: JSONObject,
        editor: SharedPreferences.Editor,
        path: String
    ) {
        val fullPath = Common.ASSET_DIR + "/" + path
        val outFile = File(context.filesDir, fullPath)
        val spKey = "asset.$path"
        val newHash = assetsJson.getString(path)
        if (outFile.exists() && sp.getString(spKey, "")!!.equals(newHash)) return
        outFile.delete()
        val outDir = outFile.parentFile
        if (outDir != null && !outDir.exists() &&
            !outDir.mkdirs()
        ) {
            throw IOException("Failed to create $outDir")
        }
        val tmpFile = File(outDir, outFile.name + ".tmp")
        tmpFile.delete()
        am.open(fullPath).use { input ->
            FileOutputStream(tmpFile).use { output -> transferStream(input, output) }
        }
        if (!tmpFile.renameTo(outFile)) throw IOException("Failed to rename $tmpFile")
        editor.putString(spKey, newHash)
    }

    private fun cleanExtractedDir(dir: String, assetsJson: JSONObject) {
        val outDir = File(context.filesDir, Common.ASSET_DIR + "/" + dir)
        val list = outDir.listFiles() ?: return
        for (outFile in list) {
            val name = outFile.name
            if (outFile.isDirectory) {
                cleanExtractedDir("$dir/$name", assetsJson)
            } else if (!assetsJson.has("$dir/$name")) {
                outFile.delete()
            }
        }
    }

    private fun deleteRecursive(file: File) {
        file.listFiles()?.forEach(::deleteRecursive)
        file.delete()
    }

    @Throws(IOException::class)
    private fun transferStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val length = input.read(buffer)
            if (length == -1) break
            output.write(buffer, 0, length)
        }
    }

    @Throws(IOException::class)
    private fun streamToString(input: InputStream): String {
        val result = ByteArrayOutputStream()
        transferStream(input, result)
        return result.toString(StandardCharsets.UTF_8.name())
    }

    @Throws(JSONException::class)
    private fun loadNativeLibs() {
        val pythonVersion = buildJson.getString("python_version")
        for (lib in arrayOf(
            "crypto_chaquopy",
            "ssl_chaquopy",
            "sqlite3_chaquopy",
            "python$pythonVersion",
            "chaquopy_java"
        )) {
            System.loadLibrary(lib)
        }
    }

    companion object {
        private val OBSOLETE_FILES =
            arrayOf("app.zip", "requirements.zip", "chaquopy.mp3", "stdlib.mp3", "chaquopy.zip", "lib-dynload", "stdlib.zip", "bootstrap.zip", "stdlib-common.zip", "ticket.txt")
        private val OBSOLETE_CACHE = arrayOf("AssetFinder")

        @JvmStatic fun create(): Platform = Platform()
    }
}
