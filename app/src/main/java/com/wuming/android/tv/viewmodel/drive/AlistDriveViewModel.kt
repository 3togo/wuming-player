package com.wuming.android.tv.viewmodel.drive

import com.wuming.android.tv.bean.DriveFolderFile
import com.wuming.android.tv.Constant
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.Task
import com.github.catvod.net.OkHttp
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLDecoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

class AlistDriveViewModel : AbstractDriveViewModel() {
    private fun getRandomUA(): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    private fun setRequestHeader(request: Request.Builder, originParam: String?): Request.Builder {
        request.header("User-Agent", getRandomUA())
        var origin = originParam
        if (origin != null && origin.isNotEmpty()) {
            if (origin.endsWith("/")) origin = origin.substring(0, origin.length - 1)
            request.header("origin", origin)
            request.header("Referer", origin)
        }
        request.header("accept", "application/json, text/plain, */*")
        request.header("content-type", "application/json;charset=UTF-8")
        return request
    }

    fun getUrl(str: String?): String {
        if (str != null) {
            try {
                val url = URL(str)
                val str2 = if (url.port > 0) ":" + url.port else ""
                return url.protocol + "://" + url.host + str2
            } catch (e: Exception) {
                AppLog.e(TAG, e)
            }
        }
        return ""
    }

    override fun loadData(callback: LoadDataCallback?): String? {
        val generation = beginLoad()
        if (generation < 0) return null
        val config = currentDrive!!.getConfig()!!
        if (currentDriveNote == null) {
            currentDriveNote = DriveFolderFile(
                null,
                if (config.has("initPath")) config.get("initPath").asString else "",
                0, false, null, null,
            )
        }
        val targetPath = currentDriveNote!!.getAccessingPathStr() + currentDriveNote!!.name
        if (currentDriveNote!!.getChildren() == null) {
            submitLoad(generation, LoadDataTask(this, config, currentDrive!!, currentDriveNote!!, targetPath, generation, callback))
            return targetPath
        } else {
            sortData(currentDriveNote!!.getChildren()!!)
            callback?.callback(currentDriveNote!!.getChildren()!!, true, generation)
        }
        return targetPath
    }

    fun loadFile(targetFile: DriveFolderFile, callback: LoadFileCallback?) {
        val config = currentDrive!!.getConfig()!!
        val webLink = getUrl(config.get("url").asString)
        val targetPath = targetFile.getAccessingPathStr() + targetFile.name
        try {
            if (callback != null) {
                if (targetFile.fileUrl != null && targetFile.fileUrl!!.isNotEmpty()) {
                    callback.callback(targetFile.fileUrl!!)
                } else {
                    callback.callback(URLDecoder.decode(webLink + "/d" + targetPath, "UTF-8"))
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, e)
            callback?.fail(e.message ?: e.javaClass.simpleName)
        }
    }

    interface LoadFileCallback {
        fun callback(fileUrl: String)
        fun fail(msg: String)
    }

    private class LoadDataTask(
        viewModel: AlistDriveViewModel,
        private val config: JsonObject,
        private val drive: DriveFolderFile,
        private val targetNode: DriveFolderFile,
        private val targetPath: String,
        private val generation: Int,
        private val callback: LoadDataCallback?,
    ) : Runnable {
        private val viewModelRef = WeakReference(viewModel)

        override fun run() {
            var viewModel = viewModelRef.get() ?: return
            if (!viewModel.isCurrent(generation)) return
            val webLink = viewModel.getUrl(config.get("url").asString)
            val client = OkHttp.client(Constant.TIMEOUT_VOD)
            try {
                if (drive.version == 0) {
                    detectAlistVersion(client, webLink)
                }
                viewModel = viewModelRef.get() ?: return
                if (!viewModel.isCurrent(generation)) return
                if (drive.version == 2) {
                    listVersion2(viewModel, client, webLink)
                } else if (drive.version == 3) {
                    listVersion3(viewModel, client, webLink)
                }
            } catch (ex: Exception) {
                AppLog.e(TAG, ex)
                if (callback != null && viewModel.isCurrent(generation)) {
                    callback.fail("无法访问，请注意地址格式", generation)
                }
            }
        }

        @Throws(Exception::class)
        private fun detectAlistVersion(client: OkHttpClient, webLink: String) {
            val versionRequest = Request.Builder().url(webLink + "/api/public/settings").build()
            client.newCall(versionRequest).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful) {
                    throw IOException("Unable to detect AList version: HTTP " + response.code)
                }
                val opt = JSONObject(body.string())
                val obj = JSONTokener(opt.optString("data")).nextValue()
                if (obj is JSONObject) {
                    drive.version = 3
                } else if (obj is JSONArray) {
                    drive.version = 2
                } else {
                    throw IOException("Unable to detect AList version")
                }
            }
        }

        @Throws(Exception::class)
        private fun listVersion2(viewModel: AlistDriveViewModel, client: OkHttpClient, webLink: String) {
            val requestBody = JSONObject()
            requestBody.put("path", if (targetPath.isEmpty()) "/" else targetPath)
            requestBody.put("password", config.get("password")?.asString ?: "")
            requestBody.put("page_num", 1)
            requestBody.put("page_size", 200)
            val request = viewModel.setRequestHeader(Request.Builder(), webLink)
                .url(webLink + "/api/public/path")
                .post(requestBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .tag("drive")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body
                    val respBody = body.string()
                    val respData = JsonParser.parseString(respBody).asJsonObject
                    val items = mutableListOf<DriveFolderFile>()
                    if (respData.get("code")?.asInt == 200) {
                        val dateFormat = DATE_FORMAT.get()!!
                        val files = respData.get("data")?.asJsonObject?.get("files")?.asJsonArray
                        if (files != null) for (file in files) {
                            val fileObj = file.asJsonObject
                            val fileName = fileObj.get("name")?.asString ?: continue
                            val extNameStartIndex = fileName.lastIndexOf(".")
                            val isFile = fileObj.get("type")?.asInt != 1
                            var fileUrl: String? = null
                            if (fileObj.has("url") && fileObj.get("url")?.asString?.isNotEmpty() == true) {
                                fileUrl = fileObj.get("url")?.asString
                            }
                            try {
                                synchronized(dateFormat) {
                                    val driveFile = DriveFolderFile(
                                        targetNode, fileName, drive.version, isFile,
                                        if (isFile && extNameStartIndex >= 0 && extNameStartIndex < fileName.length) fileName.substring(extNameStartIndex + 1) else null,
                                        requireNotNull(dateFormat.parse(fileObj.get("updated_at")?.asString ?: "")).time,
                                    )
                                    if (fileUrl != null) driveFile.fileUrl = fileUrl
                                    items.add(driveFile)
                                }
                            } catch (e: ParseException) {
                                AppLog.e(TAG, e)
                            }
                        }
                    }
                    deliverResult(viewModel, items)
                }
            } catch (ex: Exception) {
                if (callback != null && viewModel.isCurrent(generation)) {
                    callback.fail("无法访问，请注意地址格式", generation)
                }
            }
        }

        @Throws(Exception::class)
        private fun listVersion3(viewModel: AlistDriveViewModel, client: OkHttpClient, webLink: String) {
            val requestBody = JSONObject()
            requestBody.put("path", if (targetPath.isEmpty()) "/" else targetPath)
            requestBody.put("password", config.get("password")?.asString ?: "")
            requestBody.put("page", 1)
            requestBody.put("per_page", 200)
            requestBody.put("refresh", false)
            val request = viewModel.setRequestHeader(Request.Builder(), webLink)
                .url(webLink + "/api/fs/list")
                .post(requestBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .tag("drive")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body
                    val respBody = body.string()
                    val respData = JsonParser.parseString(respBody).asJsonObject
                    val items = mutableListOf<DriveFolderFile>()
                    if (respData.get("code")?.asInt == 200) {
                        val dateFormat = DATE_FORMAT.get()!!
                        val content = respData.get("data")?.asJsonObject?.get("content")?.asJsonArray
                        if (content != null) for (file in content) {
                            val fileObj = file.asJsonObject
                            val fileName = fileObj.get("name")?.asString ?: continue
                            val extNameStartIndex = fileName.lastIndexOf(".")
                            val isFile = !(fileObj.get("is_dir")?.asBoolean ?: false)
                            try {
                                synchronized(dateFormat) {
                                    val driveFile = DriveFolderFile(
                                        targetNode, fileName, drive.version, isFile,
                                        if (isFile && extNameStartIndex >= 0 && extNameStartIndex < fileName.length) fileName.substring(extNameStartIndex + 1) else null,
                                        requireNotNull(dateFormat.parse(fileObj.get("modified")?.asString ?: "")).time,
                                    )
                                    items.add(driveFile)
                                }
                            } catch (e: ParseException) {
                                AppLog.e(TAG, e)
                            }
                        }
                    }
                    deliverResult(viewModel, items)
                }
            } catch (ex: Exception) {
                if (callback != null && viewModel.isCurrent(generation)) {
                    callback.fail("无法访问，请注意地址格式", generation)
                }
            }
        }

        private fun deliverResult(viewModel: AlistDriveViewModel, items: MutableList<DriveFolderFile>) {
            viewModel.sortData(items)
            val backItem = DriveFolderFile(null, null, 0, false, null, null)
            backItem.parentFolder = backItem
            items.add(0, backItem)
            if (!viewModel.isCurrent(generation)) return
            targetNode.setChildren(items)
            callback?.callback(items, false, generation)
        }
    }

    companion object {
        private const val TAG = "AlistDriveViewModel"
        private val DATE_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US) }
    }
}
