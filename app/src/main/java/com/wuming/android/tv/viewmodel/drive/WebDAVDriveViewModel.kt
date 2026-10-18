package com.wuming.android.tv.viewmodel.drive

import com.wuming.android.tv.bean.DriveFolderFile
import com.google.gson.JsonObject
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import java.io.IOException
import java.util.Locale

open class WebDAVDriveViewModel : AbstractDriveViewModel() {
    private var webDAV: Sardine? = null

    @Synchronized
    protected open fun getWebDAV(config: JsonObject): Sardine {
        if (webDAV == null) {
            val candidate = OkHttpSardine()
            if (config.has("username") && config.has("password")) {
                candidate.setCredentials(config.get("username")?.asString ?: "", config.get("password")?.asString ?: "")
            }
            webDAV = candidate
        }
        return webDAV!!
    }

    override fun loadData(callback: LoadDataCallback?): String? {
        val generation = beginLoad()
        if (generation < 0) return null
        return try {
            val config = currentDrive?.getConfig() ?: throw Exception("Missing drive config")
            if (currentDriveNote == null) {
                currentDriveNote = DriveFolderFile(
                    null,
                    if (config.has("initPath")) config.get("initPath")?.asString ?: "" else "",
                    0, false, null, null,
                )
            }
            val targetNode = currentDriveNote!!
            val targetPath = targetNode.getAccessingPathStr() + targetNode.name
            if (targetNode.getChildren() == null) {
                submitLoad(generation) { loadDirectory(config, targetPath, targetNode, generation, callback) }
            } else {
                sortData(targetNode.getChildren()!!)
                callback?.callback(targetNode.getChildren()!!, true, generation)
            }
            targetPath
        } catch (error: Exception) {
            fail(callback, generation)
            null
        }
    }

    private fun loadDirectory(config: JsonObject, path: String, targetNode: DriveFolderFile, generation: Int, callback: LoadDataCallback?) {
        if (!isCurrent(generation)) return
        try {
            val resources = getWebDAV(config).list((config.get("url")?.asString ?: "") + path)
            if (!isCurrent(generation)) return
            val items = mutableListOf<DriveFolderFile>()
            if (resources != null) {
                for (resource in resources) {
                    if (!isCurrent(generation)) return
                    if (resource == null) continue
                    val resourcePath = java.util.Objects.toString(resource.path, "")
                    if (path.isNotEmpty() && resourcePath.uppercase(Locale.ROOT).endsWith(path.uppercase(Locale.ROOT) + "/")) continue
                    items.add(entry(targetNode, resource.name, resource.isDirectory, resource.modified))
                }
            }
            sortData(items)
            val back = DriveFolderFile(null, null, 0, false, null, null)
            back.parentFolder = back
            items.add(0, back)
            if (!isCurrent(generation)) return
            targetNode.setChildren(items)
            callback?.callback(items, false, generation)
        } catch (error: Exception) {
            fail(callback, generation)
        }
    }

    private fun fail(callback: LoadDataCallback?, generation: Int) {
        if (callback != null && isCurrent(generation)) callback.fail("无法访问该WebDAV地址", generation)
    }

    @Synchronized
    override fun close() {
        super.close()
        webDAV = null
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun entry(parent: DriveFolderFile?, name: String, directory: Boolean, modified: java.util.Date?): DriveFolderFile {
            if (name.isEmpty()) throw IOException("Missing WebDAV resource name")
            val dot = name.lastIndexOf('.')
            val extension = if (!directory && dot >= 0 && dot + 1 < name.length) name.substring(dot + 1) else null
            return DriveFolderFile(parent, name, 0, !directory, extension, modified?.time ?: 0L)
        }
    }
}
