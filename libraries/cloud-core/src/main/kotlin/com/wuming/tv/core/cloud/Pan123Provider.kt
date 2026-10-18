package com.wuming.tv.core.cloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Official 123 Netdisk protocol and workflow; platform services are supplied as ports. */
class Pan123Provider(private val port: Port, vault: CloudHandleVault,
                     private val videoName: (String) -> Boolean) : CloudStorageProvider {
    interface Port {
        fun token(): String
        fun clearCredentials()
        @Throws(Exception::class) fun get(url: String, query: Map<String, String>, headers: Map<String, String>): JsonObject
        @Throws(Exception::class) fun post(url: String, body: JsonObject, headers: Map<String, String>): JsonObject
        fun authExpired()
        fun invalidUrl(itemId: String)
        fun userAgent(): String
    }
    private val files = CloudFileHandles(id(), FileRef::class.java, vault)
    override fun id() = "123pan"
    override fun displayName() = "123 Netdisk"
    override fun available() = true
    override fun availabilityMessage() = ""
    override fun authenticated() = CredentialShapePolicy.isOpaqueToken(port.token())
    override fun beginLogin() = OpenDriveLoginChallenge(UUID.randomUUID().toString(), LOGIN,
        System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15))
    override fun pollLogin(sessionId: String) = if (authenticated()) OpenDriveLoginState.CONFIRMED else OpenDriveLoginState.WAITING
    override fun list(parentId: String): List<OpenDriveItem> {
        val parent = if (parentId == "root") "0" else files.require(parentId).fileId
        val root = port.get(API + "file/list/new", mapOf("driveId" to "0", "limit" to PAGE_SIZE.toString(),
            "next" to "0", "orderBy" to "update_time", "orderDirection" to "desc", "parentFileId" to parent,
            "trashed" to "false", "Page" to "1"), headers(true))
        val values = CloudJson.array(CloudJson.jsonObject(root, "data"), "InfoList")
        if (values.size() == 0 && CloudJson.number(root, "code") != 0L) throw IOException("123 Netdisk API request failed")
        return items(values)
    }
    override fun supportsShare(url: String) = share(url) != null
    override fun listShare(url: String): List<OpenDriveItem> {
        val link = share(url) ?: throw IllegalArgumentException("Invalid 123 Netdisk share link")
        val result = ArrayList<OpenDriveItem>()
        val folders = ArrayDeque<Long>()
        folders.add(0L)
        var visited = 0
        while (folders.isNotEmpty()) {
            if (++visited > 256) throw IOException("123 Netdisk share contains too many folders")
            val parent = folders.removeFirst()
            var next = 0
            for (page in 1..20) {
                val root = port.get(API + "share/get", mapOf("limit" to PAGE_SIZE.toString(), "next" to next.toString(),
                    "orderBy" to "file_name", "orderDirection" to "asc", "shareKey" to link.key,
                    "SharePwd" to link.password, "ParentFileId" to parent.toString(), "Page" to page.toString()), shareHeaders())
                val data = CloudJson.jsonObject(root, "data")
                val values = CloudJson.array(data, "InfoList")
                for (value in values) {
                    if (!value.isJsonObject) continue
                    val item = value.asJsonObject
                    val category = CloudJson.number(item, "Category").toInt()
                    if (category == 0) folders.addLast(CloudJson.number(item, "FileId"))
                    else if (category == 2 || videoName(name(item))) {
                        val file = ref(item, link)
                        result.add(OpenDriveItem(files.remember(file), name(item), false, file.size))
                    }
                }
                val newNext = CloudJson.number(data, "Next").toInt()
                if (values.size() < PAGE_SIZE || newNext <= next) break
                next = newNext
            }
        }
        return result
    }
    fun prepareExternal(payload: OpenDriveExternalPayload): OpenDrivePreparedExternal? {
        val item = payload.json(false)
        if (!ExternalEpisodePayload.belongsTo(payload.flag, item, "pan123", "123pan", "123盘", "123网", "123云")) return null
        val shareKey: String
        val fileId: String
        val s3: String
        val etag: String
        val size: Long
        var name = "Cloud file"
        var type = 0
        if (item != null) {
            shareKey = ExternalEpisodePayload.first(item, "ShareKey", "shareKey", "share_key")
            fileId = ExternalEpisodePayload.first(item, "FileID", "FileId", "fileId", "file_id")
            s3 = ExternalEpisodePayload.first(item, "S3KeyFlag", "s3KeyFlag", "s3_key_flag")
            etag = ExternalEpisodePayload.first(item, "Etag", "etag")
            size = ExternalEpisodePayload.number(item, "Size", "size")
            ExternalEpisodePayload.first(item, "FileName", "fileName", "name").takeIf { !it.isBlank() }?.let { name = it }
            val importedType = ExternalEpisodePayload.number(item, "Type", "type")
            if (importedType in 0..Int.MAX_VALUE.toLong()) type = importedType.toInt()
        } else {
            val values = ExternalEpisodePayload.split(payload.raw, "\\+\\+", 5) ?: return null
            shareKey = values[0]; fileId = values[1]; s3 = values[2]; etag = values[4]
            size = try { values[3].toLong() } catch (_: Exception) { return null }
        }
        if (!ExternalEpisodePayload.identifier(shareKey) || !ExternalEpisodePayload.identifier(fileId) ||
            !ExternalEpisodePayload.capability(s3) || !ExternalEpisodePayload.capability(etag) || size < 0) return null
        val file = FileRef(fileId, name, s3, size, etag, type, shareKey, "")
        return OpenDrivePreparedExternal.of { files.remember(file) }
    }
    override fun resolve(itemId: String): OpenDrivePlayback {
        val file = files.require(itemId)
        if (!authenticated()) {
            port.authExpired()
            throw IllegalStateException("Open Settings > Cloud storage > 123 Netdisk and sign in first")
        }
        val privateFile = file.shareKey.isBlank()
        val endpoint: String
        val body: JsonObject
        if (privateFile) {
            endpoint = "v2/file/download_info"
            body = json("driveId" to 0, "etag" to file.etag, "fileId" to file.fileId, "s3keyFlag" to file.s3KeyFlag,
                "type" to file.type, "fileName" to file.name, "size" to file.size)
        } else {
            endpoint = "share/download/info"
            body = json("ShareKey" to file.shareKey, "FileID" to file.fileId, "S3KeyFlag" to file.s3KeyFlag,
                "Size" to file.size, "Etag" to file.etag)
        }
        val root = port.post(API + endpoint, body, if (privateFile) headers(true) else shareDownloadHeaders(port.token()))
        val url = findDownloadUrl(CloudJson.jsonObject(root, "data"))
        if (!validUrl(url)) { port.invalidUrl(itemId); throw IOException("123 Netdisk returned an invalid download URL") }
        return OpenDrivePlayback(url, mapOf("User-Agent" to port.userAgent()), System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10))
    }
    fun credentialsRestored() = files.clear()
    override fun logout() { files.clearPersisted(); port.clearCredentials() }
    override fun persistHandle(itemId: String) = files.persist(itemId)
    private fun items(values: JsonArray): List<OpenDriveItem> {
        val result = ArrayList<OpenDriveItem>()
        for (value in values) {
            if (!value.isJsonObject) continue
            val item = value.asJsonObject
            val directory = CloudJson.number(item, "Type") == 1L
            if (!directory && CloudJson.number(item, "Category") != 2L && !videoName(name(item))) continue
            val file = ref(item, null)
            result.add(OpenDriveItem(files.remember(file), name(item), directory, file.size))
        }
        return result
    }
    private fun headers(authenticated: Boolean) = linkedMapOf("Content-Type" to "application/json", "platform" to "web", "App-Version" to "3").apply {
        if (authenticated) put("Authorization", "Bearer " + port.token())
    }
    private data class FileRef(val fileId: String, val name: String, val s3KeyFlag: String, val size: Long,
        val etag: String, val type: Int, val shareKey: String, val sharePassword: String)
    private data class ShareLink(val key: String, val password: String)
    companion object {
        private const val API = "https://www.123684.com/b/api/"
        private const val LOGIN = "https://login.123pan.com/centerlogin?redirect_url=https%3A%2F%2Fyun.123pan.cn%2F&source_page=website"
        private const val PAGE_SIZE = 100
        private const val SHARE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        @JvmStatic fun canonicalShareUrl(value: String?): String? = share(value)?.let {
            "https://www.123pan.com/s/" + it.key + if (it.password.isBlank()) "" else "?pwd=" + it.password
        }
        @JvmStatic fun shareHeaders(): Map<String, String> = mapOf("User-Agent" to SHARE_USER_AGENT)
        @JvmStatic fun shareDownloadHeaders(token: String?): Map<String, String> = linkedMapOf(
            "User-Agent" to SHARE_USER_AGENT, "Content-Type" to "application/json",
            "Authorization" to "Bearer " + (token ?: ""), "platform" to "android")
        private fun name(item: JsonObject) = CloudJson.string(item, "FileName").ifBlank { "Cloud file" }
        private fun ref(item: JsonObject, share: ShareLink?) = FileRef(CloudJson.number(item, "FileId").toString(), name(item),
            CloudJson.string(item, "S3KeyFlag"), CloudJson.number(item, "Size"), CloudJson.string(item, "Etag"),
            CloudJson.number(item, "Type").toInt(), share?.key ?: "", share?.password ?: "")
        private fun validUrl(value: String) = try {
            val uri = URI.create(value)
            uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null && uri.port == -1
        } catch (_: Exception) { false }
        private fun findDownloadUrl(data: JsonObject): String {
            for (key in listOf("DownloadURL", "DownloadUrl", "downloadUrl", "url")) {
                val value = CloudJson.string(data, key)
                val decoded = decodeWrappedUrl(value)
                if (validUrl(decoded)) return decoded
                if (validUrl(value)) return value
            }
            return ""
        }
        private fun decodeWrappedUrl(value: String): String = try {
            val encoded = value.toHttpUrlOrNull()?.queryParameter("params")
            if (encoded == null) "" else {
                var normalized = encoded.replace('-', '+').replace('_', '/')
                normalized += "=".repeat((4 - normalized.length % 4) % 4)
                String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8)
            }
        } catch (_: Exception) { try { URLDecoder.decode(value, StandardCharsets.UTF_8) } catch (_: Exception) { "" } }
        private fun share(value: String?): ShareLink? {
            val uri = FirstPartyOriginPolicy.httpsUri(value, 2048, true) ?: return null
            val domains = setOf("123pan.com", "123pan.cn", "123684.com", "123592.com", "123912.com", "123865.com")
            val host = uri.host.lowercase(Locale.ROOT)
            if (host !in domains && (!host.startsWith("www.") || host.substring(4) !in domains)) return null
            return try {
                val path = uri.path ?: return null
                if (!path.matches("/s/[A-Za-z0-9_-]{4,128}(?:\\.html)?/?".toRegex())) return null
                val key = path.substring(3).replace("/", "").replace(".html", "")
                var password = ""
                uri.rawQuery?.split("&")?.forEach {
                    val pair = it.split("=", limit = 2)
                    if (pair.size == 2 && (pair[0].equals("pwd", true) || pair[0].equals("code", true)))
                        password = URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                }
                if (!password.matches("[A-Za-z0-9]{0,16}".toRegex())) return null
                ShareLink(key, password)
            } catch (_: Exception) { null }
        }
        private fun json(vararg entries: Pair<String, Any>): JsonObject = JsonObject().apply {
            for ((key, value) in entries) when (value) {
                is String -> addProperty(key, value)
                is Number -> addProperty(key, value)
            }
        }
    }
}
