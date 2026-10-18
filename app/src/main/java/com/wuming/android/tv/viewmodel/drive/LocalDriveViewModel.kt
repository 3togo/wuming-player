package com.wuming.android.tv.viewmodel.drive

import com.wuming.android.tv.bean.DriveFolderFile
import java.io.File

class LocalDriveViewModel : AbstractDriveViewModel() {
    override fun loadData(callback: LoadDataCallback?): String? {
        val generation = beginLoad()
        if (generation < 0) return null
        if (currentDriveNote == null) {
            currentDriveNote = DriveFolderFile(null, "", 0, false, null, null)
        }
        val targetNode = currentDriveNote!!
        val path = currentDrive!!.name + targetNode.getAccessingPathStr() + targetNode.name
        if (targetNode.getChildren() == null) {
            submitLoad(generation) { loadDirectory(targetNode, path, generation, callback) }
        } else {
            sortData(targetNode.getChildren()!!)
            callback?.callback(targetNode.getChildren()!!, true, generation)
        }
        return path
    }

    private fun loadDirectory(targetNode: DriveFolderFile, path: String, generation: Int, callback: LoadDataCallback?) {
        try {
            val files = File(path).listFiles()
            val items = mutableListOf<DriveFolderFile>()
            if (files != null) {
                for (file in files) {
                    if (!isCurrent(generation)) return
                    val extensionStart = file.name.lastIndexOf('.')
                    val regularFile = file.isFile
                    val extension = if (regularFile && extensionStart >= 0 && extensionStart + 1 < file.name.length) {
                        file.name.substring(extensionStart + 1)
                    } else null
                    items.add(DriveFolderFile(targetNode, file.name, 0, regularFile, extension, file.lastModified()))
                }
            }
            sortData(items)
            val backItem = DriveFolderFile(null, null, 0, false, null, null)
            backItem.parentFolder = backItem
            items.add(0, backItem)
            if (!isCurrent(generation)) return
            targetNode.setChildren(items)
            callback?.callback(items, false, generation)
        } catch (error: RuntimeException) {
            if (isCurrent(generation) && callback != null) {
                val message = error.message ?: error.javaClass.simpleName
                callback.fail(message, generation)
            }
        }
    }
}
