package com.wuming.android.tv.bean

import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DriveFolderFile {

    @JvmField
    var parentFolder: DriveFolderFile? = null

    @JvmField
    var name: String? = null

    @JvmField
    var version: Int = 0

    @JvmField
    var isFile: Boolean = false

    @JvmField
    var fileType: String? = null

    private var driveData: Drive? = null

    @JvmField
    var lastModifiedDate: Long? = null

    @JvmField
    var isSelected: Boolean = false

    @JvmField
    var isDelMode: Boolean = false

    @JvmField
    var fileUrl: String? = null

    private var accessingPath: Array<String> = emptyArray()

    private var children: MutableList<DriveFolderFile>? = null

    private var config: JsonObject? = null

    constructor(driveData: Drive) {
        this.driveData = driveData
        this.name = driveData.name
        if (!driveData.configJson.isNullOrEmpty())
            this.config = runCatching { JsonParser.parseString(driveData.configJson).asJsonObject }.getOrNull()
    }

    constructor(parent: DriveFolderFile?, name: String?, version: Int, isFile: Boolean, fileType: String?, lastModifiedDate: Long?) {
        if (parent != null) {
            val path = mutableListOf<String>()
            var currentParent: DriveFolderFile? = parent
            while (currentParent != null) {
                path.add(0, currentParent.name ?: "")
                currentParent = currentParent.parentFolder
            }
            accessingPath = path.toTypedArray()
        }
        this.parentFolder = parent
        this.name = name
        this.version = version
        this.isFile = isFile
        if (fileType != null)
            this.fileType = fileType.uppercase(Locale.ROOT)
        this.lastModifiedDate = lastModifiedDate
    }

    fun getAccessingPath(): Array<String> = accessingPath.copyOf()

    fun setAccessingPath(accessingPath: Array<String>) {
        this.accessingPath = accessingPath
    }

    fun getAccessingPathStr(): String {
        val sb = StringBuilder()
        for (pathItem in accessingPath) {
            sb.append(pathItem).append('/')
        }
        return sb.toString()
    }

    fun isDrive(): Boolean = driveData != null && driveData?.name != null

    fun getDriveType(): DriveType.TYPE? = driveData?.let { DriveType.TYPE.values()[it.type] }

    fun getDriveData(): Drive? = driveData

    fun setDriveData(driveData: Drive?) {
        this.driveData = driveData
    }

    fun getChildren(): MutableList<DriveFolderFile>? = children

    fun setChildren(children: MutableList<DriveFolderFile>?) {
        this.children = children
    }

    fun getFormattedLastModified(): String {
        if (lastModifiedDate != null) {
            return requireNotNull(DATE_FMT.get()).format(Date(lastModifiedDate!!))
        }
        return ""
    }

    fun getConfig(): JsonObject? = config

    fun setConfig(config: JsonObject?) {
        this.config = config
    }

    fun getWebDAVBase64Credential(): String? {
        try {
            val cfg = config
            if (cfg != null && cfg.has("username") && cfg.has("password")) {
                val username = cfg.get("username")?.asString ?: return null
                val password = cfg.get("password")?.asString ?: return null
                val data = "$username:$password".toByteArray(StandardCharsets.UTF_8)
                return Base64.encodeToString(data, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.w("DriveFolderFile", "getWebDAVBase64Credential failed", e)
        }
        return null
    }

    companion object {

        private val DATE_FMT = ThreadLocal.withInitial {
            SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US)
        }
    }
}
