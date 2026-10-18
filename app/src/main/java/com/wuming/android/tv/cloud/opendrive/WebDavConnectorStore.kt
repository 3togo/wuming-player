package com.wuming.android.tv.cloud.opendrive

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.android.tv.bean.Drive
import com.wuming.android.tv.bean.DriveType
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.db.DataCache
import java.net.URI
import java.util.ArrayList
import java.util.Locale
import java.util.UUID

/** Persistent configuration for user-owned OpenList and WebDAV endpoints.  */
class WebDavConnectorStore {
    @JvmRecord
    data class Connector(
        val id: Int,
        val name: String?,
        val baseUrl: String?,
        val initialPath: String?,
        val username: String?,
        val password: String?,
        val tokenRef: String?
    )

    @JvmRecord
    private data class Secret(val username: String?, val password: String?)

    fun list(): MutableList<Connector?> {
        val result: MutableList<Connector?> = ArrayList<Connector?>()
        for (drive: Drive? in AppDatabase.get().getDriveDao().getAll()) {
            if (drive == null || drive.type != DriveType.TYPE.WEBDAV.ordinal) continue
            val connector: Connector? = decode(drive)
            if (connector != null) result.add(migrateLegacyIfNeeded(drive, connector))
        }
        return result
    }

    fun get(id: Int): Connector? {
        val drive: Drive? = AppDatabase.get().getDriveDao().getById(id)
        if (drive == null || drive.type != DriveType.TYPE.WEBDAV.ordinal) return null
        val connector: Connector? = decode(drive)
        return if (connector == null) null else migrateLegacyIfNeeded(drive, connector)
    }

    fun save(
        id: Int?,
        name: String?,
        baseUrl: String?,
        initialPath: String?,
        username: String?,
        password: String?,
        preserveBlankPassword: Boolean
    ): Connector? {
        val safeName: String = required(name, "Connector name is required")
        val safeUrl: String? = normalizeBaseUrl(baseUrl)
        val safePath: String = normalizeInitialPath(initialPath)
        val safeUser: String = if (username == null) "" else username.trim { it <= ' ' }
        var safePassword: String? = if (password == null) "" else password

        val existing: Drive? = if (id == null) null else AppDatabase.get().getDriveDao().getById(id)
        val old: Connector? = if (existing == null) null else decode(existing)
        if (preserveBlankPassword && safePassword!!.isEmpty() && old != null) {
            safePassword = old.password
        }

        val reference: String = (
            if (old == null ||
                old.tokenRef!!.codePoints().allMatch { Character.isWhitespace(it) }
            ) {
                OpenDriveTokenVault.reference(
                    WebDavConnectorStore.Companion.PROVIDER_ID,
                    "config." + UUID.randomUUID().toString().replace("-", "")
                )
            } else {
                old.tokenRef
            }
            )
        check(
            OpenDriveTokenVault.put(
                reference,
                GSON.toJson(Secret(safeUser, safePassword))
            )
        ) { "Unable to protect WebDAV credentials" }

        val config: JsonObject = JsonObject()
        config.addProperty("schema", 1)
        config.addProperty("url", safeUrl)
        config.addProperty("initPath", safePath)
        config.addProperty("tokenRef", reference)

        val drive: Drive = if (existing == null) Drive() else existing
        drive.name = safeName
        drive.type = DriveType.TYPE.WEBDAV.ordinal
        drive.configJson = config.toString()
        if (existing == null) {
            val inserted: Long = AppDatabase.get().getDriveDao().insert(drive)!!
            if (inserted <= 0) {
                OpenDriveTokenVault.remove(reference)
                throw IllegalStateException("Unable to save WebDAV connector")
            }
            drive.id = inserted.toInt()
            if (DataCache.isReady()) DataCache.get().putDrive(drive)
        } else {
            AppDatabase.get().getDriveDao().update(drive)
            if (DataCache.isReady()) DataCache.get().updateDrive(drive)
        }
        return decode(drive)
    }

    fun delete(id: Int) {
        val drive: Drive? = AppDatabase.get().getDriveDao().getById(id)
        if (drive == null || drive.type != DriveType.TYPE.WEBDAV.ordinal) return
        val connector: Connector? = decode(drive)
        AppDatabase.get().getDriveDao().delete(id)
        if (DataCache.isReady()) DataCache.get().removeDrive(id)
        if (connector != null &&
            !connector.tokenRef!!.codePoints().allMatch { Character.isWhitespace(it) }
        ) {
            OpenDriveTokenVault.remove(connector.tokenRef)
        }
    }

    private fun migrateLegacyIfNeeded(drive: Drive, connector: Connector): Connector? {
        if (!connector.tokenRef!!.codePoints().allMatch {
                Character.isWhitespace(it)
            }
        ) {
            return connector
        }
        return save(
            drive.id,
            connector.name!!,
            connector.baseUrl!!,
            connector.initialPath,
            connector.username,
            connector.password,
            false
        )
    }

    companion object {
        private const val PROVIDER_ID = "webdav"
        private val GSON = Gson()

        @JvmStatic
        fun normalizeBaseUrl(value: String?): String {
            val input: String = required(value, "WebDAV URL is required")
            try {
                val uri: URI = URI.create(input)
                val scheme: String? = uri.getScheme()
                require(
                    !(
                        !(
                            com.wuming.android.tv.utils.UrlUtil.isHttpScheme(scheme)
                            ) || uri.getHost() == null || uri.getRawUserInfo() != null ||
                            uri.getRawQuery() != null ||
                            uri.getRawFragment() != null
                        )
                ) { "Use a valid HTTP(S) WebDAV URL without embedded credentials" }
                var path: String? = uri.getRawPath()
                if (path == null || path.isEmpty()) path = "/"
                if (!path.endsWith("/")) path += "/"
                return URI.create(
                    (
                        scheme!!.lowercase() + "://" +
                            uri.getRawAuthority() + path
                        )
                )
                    .toASCIIString()
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Exception) {
                throw IllegalArgumentException("Use a valid HTTP(S) WebDAV URL", error)
            }
        }

        @JvmStatic
        fun normalizeInitialPath(value: String?): String {
            if (value == null || value.codePoints().allMatch { Character.isWhitespace(it) } ||
                "/" == value.trim { it <= ' ' }
            ) {
                return ""
            }
            var path: String = value.trim { it <= ' ' }.replace('\\', '/')
            while (path.startsWith("/")) path = path.substring(1)
            while (path.endsWith("/")) path = path.substring(0, path.length - 1)
            for (segment: String in path.split("/".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()) {
                require(
                    !(
                        segment.codePoints().allMatch { Character.isWhitespace(it) } ||
                            "." == segment ||
                            ".." == segment
                        )
                ) { "Initial path contains an unsafe segment" }
            }
            return path
        }

        @JvmStatic
        private fun decode(drive: Drive): Connector? {
            try {
                val config: JsonObject? = JsonParser.parseString(drive.configJson).getAsJsonObject()
                val url: String? = normalizeBaseUrl(OpenDriveHttp.string(config, "url"))
                val path: String = normalizeInitialPath(OpenDriveHttp.string(config, "initPath"))
                var reference: String = OpenDriveHttp.string(config, "tokenRef")
                // Backward-compat: older records use "credentialRef"
                if (reference.codePoints().allMatch { Character.isWhitespace(it) }) {
                    reference =
                        OpenDriveHttp.string(config, "credentialRef")
                }
                var username: String = OpenDriveHttp.string(config, "username")
                var password: String = OpenDriveHttp.string(config, "password")
                if (!reference.codePoints().allMatch { Character.isWhitespace(it) }) {
                    val json: String = OpenDriveTokenVault.get(reference)
                    if (!json.codePoints().allMatch { Character.isWhitespace(it) }) {
                        val secret: Secret? = GSON.fromJson<Secret?>(json, Secret::class.java)
                        if (secret != null) {
                            username = if (secret.username == null) "" else secret.username
                            password = if (secret.password == null) "" else secret.password
                        }
                    }
                }
                return Connector(
                    drive.id,
                    if (drive.name == null) "WebDAV" else drive.name,
                    url,
                    path,
                    username,
                    password,
                    reference
                )
            } catch (ignored: Exception) {
                return null
            }
        }

        @JvmStatic
        private fun required(value: String?, message: String?): String {
            require(!(value == null || value.trim { it <= ' ' }.isEmpty())) { message!! }
            return value.trim { it <= ' ' }
        }
    }
}
