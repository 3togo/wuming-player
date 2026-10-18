package com.wuming.tv.core.runtime.cloudlogin

import com.wuming.tv.core.cloud.*
import com.wuming.tv.core.cloud.CloudDriveDetector
import com.wuming.tv.core.cloud.CloudDriveProviderKind
import com.wuming.tv.core.runtime.cloudlogin.CloudCredentialStore
import com.wuming.tv.core.runtime.cloudlogin.CloudLoginResponse
import com.wuming.tv.core.runtime.cloudlogin.CloudLoginTransport
import com.wuming.tv.core.runtime.cloudlogin.JdkCloudLoginTransport
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.logging.Logger

/** Resolves CatVod cloud-drive episodes with the credentials managed by Desktop Accounts. */
class QuarkPlaybackResolver(
    private val credentials: CloudCredentialStore,
    private val transport: CloudLoginTransport = JdkCloudLoginTransport(),
    private val copies: com.wuming.tv.core.cloud.SavedCopyRegistry,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    private val logger = Logger.getLogger("tvbox.cloud.playback")

    fun resolve(provider: CloudDriveProviderKind, id: String): CloudPlaybackResult = when (provider) {
        CloudDriveProviderKind.QUARK -> runCatching { resolveQuark(id) }.fold(
            onSuccess = { playback -> playback.also { logger.info("resolve provider=Quark supported=${it.supported}") } },
            onFailure = { error ->
                val message = error.message?.takeIf(String::isNotBlank) ?: "Quark could not resolve this video."
                if (error is InterruptedException) Thread.currentThread().interrupt()
                logger.warning("resolve provider=Quark supported=false error=${error.javaClass.simpleName}")
                CloudPlaybackResult(message = message)
            },
        )
        else -> CloudPlaybackResult(message = CloudDriveDetector.accountRequiredMessage(provider))
    }

    @Synchronized
    private fun resolveQuark(id: String): CloudPlaybackResult {
        val cookie = credentials.read(QUARK_ID).trim()
        if (!hasQuarkAccount(cookie)) {
            return CloudPlaybackResult(message = "Sign in to Quark under Accounts, then try this episode again.")
        }
        val session = QuarkSession(cookie)
        val file = externalFile(id) ?: shareFile(id, session)
            ?: throw IOException("This Quark episode does not contain a usable video capability.")
        val folder = ensureCacheFolder(session)
        val account = accountIdentity(cookie)
        val savedFid = copies.obtainTask(account, fingerprint(file.shareId + "\n" + file.fid), folder,
            exists = { record -> containsFile(session, record.folder, record.file) },
            delete = { record ->
                session.post("file/delete", json("action_type" to 2, "filelist" to listOf(record.file),
                    "exclude_fids" to emptyList<String>()), delete = true)
                true
            },
            start = { startSave(file, session, folder) },
            await = { awaitSave(it, session) },
        )
        val playbackCookie = session.cookie
        val response = session.post("file/download", json("fids" to listOf(savedFid)), entry = true)
        val downloads = response.dataArray()
        val download = downloads.asSequence().mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject
                ?.takeIf { it.text("fid") == savedFid }?.text("download_url")
        }.firstOrNull { validHttps(it) }
            ?: throw response.apiError("Quark did not return a download URL.")
        return CloudPlaybackResult(
            supported = true,
            url = download,
            headers = mapOf(
                "Cookie" to playbackCookie,
                "Referer" to "https://pan.quark.cn/",
                "User-Agent" to USER_AGENT,
            ),
        )
    }

    private fun startSave(file: QuarkFile, session: QuarkSession, folder: String): String {
        val response = session.post("share/sharepage/save", json(
            "fid_list" to listOf(file.fid),
            "fid_token_list" to listOf(file.fileToken),
            "to_pdir_fid" to folder,
            "pwd_id" to file.shareId,
            "stoken" to file.shareToken,
            "pdir_fid" to "0",
            "scene" to "link",
        ))
        val taskId = response.dataObject().text("task_id")
        if (!identifier(taskId)) throw response.apiError("Quark could not save the shared video.")
        return taskId
    }

    private fun awaitSave(taskId: String, session: QuarkSession): String {
        val deadline = System.nanoTime() + 30_000_000_000L
        repeat(SAVE_POLLS) { retry ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Quark save interrupted")
            if (System.nanoTime() >= deadline) throw IOException("Quark timed out while saving the shared video.")
            val task = session.get("task", mapOf(
                "task_id" to taskId, "retry_index" to retry.toString(), "uc_param_str" to "",
            ))
            val data = task.dataObject()
            val saveAs = data.objectValue("save_as")
            val fid = saveAs.arrayValue("save_as_top_fids").asSequence()
                .mapNotNull { runCatching { it.asString }.getOrNull() }.firstOrNull(::identifier)
            when (com.wuming.tv.core.cloud.QuarkSavePolicy.state(data.long("status"), fid,
                saveAs.long("remain_capacity"), saveAs.long("min_save_file_size"))) {
                com.wuming.tv.core.cloud.QuarkSavePolicy.State.PENDING -> Unit
                com.wuming.tv.core.cloud.QuarkSavePolicy.State.COMPLETE -> return fid!!
                com.wuming.tv.core.cloud.QuarkSavePolicy.State.QUOTA_EXHAUSTED ->
                    throw SavedCopyTaskRejected("Quark cloud space is insufficient; retained copies remain protected.")
                com.wuming.tv.core.cloud.QuarkSavePolicy.State.FAILED ->
                    throw SavedCopyTaskRejected("Quark rejected the shared video save.")
                com.wuming.tv.core.cloud.QuarkSavePolicy.State.MISSING_FILE ->
                    throw IOException("Quark completed the task without a destination; retain it for reconciliation.")
            }
            if (retry + 1 < SAVE_POLLS) pause(SAVE_POLL_MILLIS)
        }
        throw IOException("Quark timed out while saving the shared video.")
    }

    fun reviewCopies(): List<SavedCopyReview> = copies.review(accountIdentity(credentials.read(QUARK_ID).trim()))

    @Synchronized
    fun reconcileCopies(): SavedCopyMaintenance {
        val session = maintenanceSession()
        return copies.reconcile(accountIdentity(session.cookie)) { record ->
            val fid = awaitSave(record.task!!, session)
            if (!containsFile(session, record.folder, fid)) throw IOException("Recovered save is not present in its recorded folder.")
            fid
        }
    }

    @Synchronized
    fun cleanCopies(reviewed: List<SavedCopyRecord>): SavedCopyMaintenance {
        val session = maintenanceSession()
        return copies.clean(accountIdentity(session.cookie), reviewed,
            exists = { containsFile(session, it.folder, it.file) },
            delete = {
                session.post("file/delete", json("action_type" to 2, "filelist" to listOf(it.file),
                    "exclude_fids" to emptyList<String>()), delete = true)
                true
            })
    }

    fun forgetCopies(reviewed: List<SavedCopyRecord>): SavedCopyMaintenance =
        copies.forget(accountIdentity(credentials.read(QUARK_ID).trim()), reviewed)

    private fun maintenanceSession(): QuarkSession {
        val cookie = credentials.read(QUARK_ID).trim()
        if (!hasQuarkAccount(cookie)) throw IOException("Sign in to Quark before reconciling or cleaning copies.")
        return QuarkSession(cookie)
    }

    private fun containsFile(session: QuarkSession, folder: String, fid: String): Boolean {
        for (page in 1..100) {
            val data = session.get("file/sort", mapOf("pdir_fid" to folder,
                "_page" to page.toString(), "_size" to "200")).dataObject()
            if (!data.has("list") || !data.get("list").isJsonArray) throw IOException("Invalid Quark cache listing.")
            val entries = data.arrayValue("list")
            if (entries.any { it.isJsonObject && it.asJsonObject.text("fid") == fid && !it.asJsonObject.boolean("dir") }) return true
            if (entries.size() < 200) return false
        }
        throw IOException("Quark cache listing exceeded its safety limit.")
    }

    private fun ensureCacheFolder(session: QuarkSession): String {
        val listing = session.get("file/sort", mapOf(
            "pdir_fid" to "0", "_page" to "1", "_size" to "200",
            "_sort" to "file_type:asc,updated_at:desc",
        ))
        listing.dataObject().arrayValue("list").forEach { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (item.boolean("dir") && item.text("file_name") == CACHE_FOLDER) {
                return item.text("fid").takeIf(::identifier)
                    ?: throw IOException("Quark returned an invalid cache folder.")
            }
        }
        val created = session.post("file", json(
            "pdir_fid" to "0", "file_name" to CACHE_FOLDER, "dir_path" to "", "dir_init_lock" to false,
        ))
        return created.dataObject().text("fid").takeIf(::identifier)
            ?: throw created.apiError("Quark could not create the TVBox cache folder.")
    }

    /** Supports share URLs used by the desktop detail fallback; the first video is deterministic. */
    private fun shareFile(raw: String, session: QuarkSession): QuarkFile? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || !uri.host.equals("pan.quark.cn", true)) return null
        val shareId = Regex("^/s/([A-Za-z0-9_-]+)").find(uri.path.orEmpty())?.groupValues?.get(1) ?: return null
        val passcode = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val split = part.indexOf('='); if (split <= 0) null else part.substring(0, split) to part.substring(split + 1)
        }.firstOrNull { it.first in setOf("pwd", "passcode", "code") }?.second.orEmpty()
        val token = session.post("share/sharepage/token", json("pwd_id" to shareId, "passcode" to passcode))
        val stoken = token.dataObject().text("stoken")
        if (!capability(stoken)) throw token.apiError("Quark rejected the share link.")
        val folders = ArrayDeque<String>().apply { add("0") }
        var visited = 0
        while (folders.isNotEmpty()) {
            if (++visited > 256) throw IOException("Quark share contains too many folders.")
            val parent = folders.removeFirst()
            val detail = session.get("share/sharepage/detail", mapOf(
                "pwd_id" to shareId, "stoken" to stoken, "pdir_fid" to parent,
                "force" to "0", "_page" to "1", "_size" to "200",
                "_sort" to "file_type:asc,file_name:asc",
            ))
            for (element in detail.dataObject().arrayValue("list")) {
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                if (item.boolean("dir")) item.text("fid").takeIf(::identifier)?.let(folders::addLast)
                else if (isVideo(item.text("file_name"))) {
                    val file = QuarkFile(item.text("fid"), item.text("share_fid_token"), shareId, stoken)
                    if (file.valid()) return file
                }
            }
        }
        return null
    }

    private fun externalFile(raw: String): QuarkFile? {
        val objectValue = parsePayload(raw) ?: return null
        return QuarkFile(
            objectValue.first("fid", "file_id", "fileId"),
            objectValue.first("share_fid_token", "fid_token", "fileToken"),
            objectValue.first("sId", "share_id", "shareId", "pwd_id"),
            objectValue.first("sToken", "stoken", "share_token", "shareToken"),
        ).takeIf(QuarkFile::valid)
    }

    private fun parsePayload(raw: String): JsonObject? {
        if (raw.isBlank() || raw.length > MAX_PAYLOAD) return null
        fun parse(value: String) = runCatching { JsonParser.parseString(value) }
            .getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        parse(raw.trim())?.let { return it }
        return runCatching {
            val normalized = raw.trim().replace('-', '+').replace('_', '/').let {
                it + "=".repeat((4 - it.length % 4) % 4)
            }
            String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8)
        }.getOrNull()?.let(::parse)
    }

    private inner class QuarkSession(initialCookie: String) {
        var cookie = initialCookie
            private set

        fun get(path: String, query: Map<String, String>) = accept(transport.get(endpoint(path), baseQuery + query, headers()))
        fun post(path: String, body: JsonObject, entry: Boolean = false, delete: Boolean = false) = accept(transport.postJson(
            endpoint(path) + "?pr=ucpro&fr=pc" + (if (entry) "&entry=ft" else "") +
                (if (delete) "&uc_param_str=" else ""),
            Gson().toJson(body), headers(),
        ))

        private fun headers(): Map<String, String> {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Quark request interrupted")
            if (credentials.read(QUARK_ID).trim() != cookie) throw IOException("Cloud account changed during resolution.")
            return mapOf("Cookie" to cookie, "Referer" to "https://pan.quark.cn/", "User-Agent" to USER_AGENT)
        }

        private fun accept(response: CloudLoginResponse): JsonObject {
            if (credentials.read(QUARK_ID).trim() != cookie) throw IOException("Cloud account changed during resolution.")
            val additions = response.setCookies.mapNotNull { header ->
                val pair = header.substringBefore(';'); val split = pair.indexOf('=')
                if (split > 0) pair.substring(0, split).trim() to pair.substring(split + 1).trim() else null
            }
            if (additions.isNotEmpty()) {
                val values = parseCookies(cookie)
                additions.forEach { (name, value) -> values[name] = value }
                val updated = values.entries.joinToString("; ") { "${it.key}=${it.value}" }
                if (updated != cookie) {
                    if (credentials.read(QUARK_ID).trim() != cookie) throw IOException("Cloud account changed during resolution.")
                    credentials.write(QUARK_ID, updated)
                    cookie = updated
                }
            }
            val body = response.body
            if (body.long("code") != 0L || (body.has("status") && body.long("status") !in 200L..299L)) {
                throw IOException("Quark API rejected the request (code ${body.long("code")}).")
            }
            return body
        }
    }

    private data class QuarkFile(val fid: String, val fileToken: String, val shareId: String, val shareToken: String) {
        fun valid() = identifier(fid) && capability(fileToken) && identifier(shareId) && capability(shareToken)
    }

    private companion object {
        const val QUARK_ID = "quark"
        const val API = "https://drive-pc.quark.cn/1/clouddrive"
        const val CACHE_FOLDER = "TVBoxCache"
        const val SAVE_POLLS = 60
        const val SAVE_POLL_MILLIS = 500L
        const val MAX_PAYLOAD = 64 * 1024
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) quark-cloud-drive/3.20.0 Chrome/112.0.5615.165 " +
            "Electron/24.1.3.8 Safari/537.36 Channel/pckk_other_ch"
        val baseQuery = mapOf("pr" to "ucpro", "fr" to "pc")
        val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts")

        fun accountIdentity(cookie: String): String = fingerprint(parseCookies(cookie)
            .filterKeys { it in setOf("__pus", "__puus") }.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" })
        fun fingerprint(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun endpoint(path: String) = "$API/$path"
        fun identifier(value: String) = value.length in 1..256 && value.matches(Regex("[A-Za-z0-9_-]+"))
        fun capability(value: String) = value.isNotBlank() && value.length <= 4096 && value.none(Character::isISOControl)
        fun validHttps(value: String) = runCatching { URI(value) }.getOrNull()?.let {
            it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.userInfo == null
        } == true
        fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in videoExtensions
        fun hasQuarkAccount(cookie: String) = parseCookies(cookie).keys.any { it == "__pus" || it == "__puus" }
        fun parseCookies(value: String) = linkedMapOf<String, String>().apply {
            value.split(';').forEach { pair ->
                val split = pair.indexOf('=')
                if (split > 0) put(pair.substring(0, split).trim(), pair.substring(split + 1).trim())
            }
        }
        fun json(vararg values: Pair<String, Any>) = JsonObject().apply {
            values.forEach { (name, value) -> add(name, Gson().toJsonTree(value)) }
        }
        fun JsonObject.text(name: String) = runCatching { get(name)?.asString.orEmpty() }.getOrDefault("")
        fun JsonObject.first(vararg names: String) = names.firstNotNullOfOrNull { text(it).takeIf(String::isNotBlank) }.orEmpty()
        fun JsonObject.long(name: String) = runCatching { get(name)?.asLong ?: 0L }.getOrDefault(0L)
        fun JsonObject.boolean(name: String) = runCatching { get(name)?.asBoolean == true }.getOrDefault(false)
        fun JsonObject.objectValue(name: String) = get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        fun JsonObject.arrayValue(name: String) = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        fun JsonObject.dataObject() = objectValue("data")
        fun JsonObject.dataArray(): JsonArray {
            val data = get("data") ?: return JsonArray()
            return when {
                data.isJsonArray -> data.asJsonArray
                data.isJsonObject -> data.asJsonObject.arrayValue("list")
                else -> JsonArray()
            }
        }
        fun JsonObject.apiError(fallback: String): IOException {
            val message = first("message", "msg").ifBlank { dataObject().first("message", "msg") }
            return IOException(message.ifBlank { fallback })
        }
    }
}


data class CloudPlaybackResult(val supported: Boolean = false, val url: String = "",
                               val message: String = "", val headers: Map<String, String> = emptyMap())
