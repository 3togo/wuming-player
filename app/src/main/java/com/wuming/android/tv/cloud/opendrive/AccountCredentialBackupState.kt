package com.wuming.android.tv.cloud.opendrive

import com.wuming.android.tv.bean.Drive
import com.wuming.android.tv.bean.DriveType
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.db.DataCache
import com.github.catvod.net.PersistentCookieJar
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.util.LinkedHashMap

internal object AccountCredentialBackupState {

    private val GSON = Gson()
    private const val COOKIES = "cookies"
    private const val CONNECTORS = "connectors"

    @JvmField
    val DOMAINS: Set<String> = setOf(
        "uc.cn", "quark.cn", "baidu.com", "alipan.com",
        "aliyundrive.com", "123pan.com", "123pan.cn", "123684.com", "115.com", "189.cn",
        "bilibili.com"
    )

    @JvmStatic
    fun capture(): Map<String, String> {
        val connectors = mutableListOf<Connector>()
        for (connector in WebDavConnectorStore().list()) {
            connectors.add(Connector(connector!!.name!!, connector.baseUrl!!, connector.initialPath!!, connector.tokenRef!!))
        }
        for (provider in listOf("alipan", "123pan", "uc", "baidu")) {
            OpenDriveTokenStore.get(provider)
        }
        val result = OpenDriveTokenVault.exportCredentials().toMutableMap()
        result[COOKIES] = GSON.toJson(PersistentCookieJar.get().exportCookies(DOMAINS))
        result[CONNECTORS] = GSON.toJson(connectors)
        return result
    }

    @JvmStatic
    fun credentialCount(values: Map<String, String>): Int {
        var count = values.size - (if (values.containsKey(COOKIES)) 1 else 0) -
            (if (values.containsKey(CONNECTORS)) 1 else 0)
        if (values.containsKey(COOKIES)) count += cookies(values[COOKIES]!!).size
        return count
    }

    @JvmStatic
    fun restore(payload: Map<String, String>) {
        OpenDriveTokenManager.get().replaceCredentials { restoreState(payload) }
    }

    private fun restoreState(payload: Map<String, String>) {
        val vault = LinkedHashMap(payload)
        val hasCookies = vault.containsKey(COOKIES)
        val hasConnectors = vault.containsKey(CONNECTORS)
        val cookies: Map<String, String>? = if (hasCookies) cookies(vault.remove(COOKIES)!!) else null
        val connectors: List<Connector>? = if (hasConnectors) {
            GSON.fromJson(vault.remove(CONNECTORS), object : TypeToken<List<Connector>>() {}.type)
        } else null

        if (!OpenDriveTokenVault.validCredentials(vault)) {
            throw IllegalArgumentException("Invalid backup credentials")
        }

        val jar = PersistentCookieJar.get()
        if (hasCookies) jar.validateCookies(DOMAINS, cookies)

        val restored = mutableListOf<Drive>()
        if (hasConnectors) {
            requireNotNull(connectors) { "Missing connectors" }
            val references = HashSet<String>()
            for (connector in connectors) {
                require(connector.name.isNotBlank()
                    && connector.tokenRef.startsWith("vault:opendrive/webdav/config.")
                    && vault.containsKey(connector.tokenRef) && references.add(connector.tokenRef)) {
                    "Invalid backup connector"
                }
                val secret = runCatching { JsonParser.parseString(vault[connector.tokenRef]).asJsonObject }.getOrNull()
                    ?: throw IllegalArgumentException("Invalid connector credential JSON")
                require(secret.has("username") && secret.has("password")
                    && secret.get("username").isJsonPrimitive && secret.get("password").isJsonPrimitive) {
                    "Invalid connector credentials"
                }
                val drive = Drive()
                drive.name = connector.name
                drive.type = DriveType.TYPE.WEBDAV.ordinal
                val config = JsonObject()
                config.addProperty("schema", 1)
                config.addProperty("url", WebDavConnectorStore.normalizeBaseUrl(connector.url))
                config.addProperty("initPath", WebDavConnectorStore.normalizeInitialPath(connector.path))
                config.addProperty("tokenRef", connector.tokenRef)
                drive.configJson = config.toString()
                restored.add(drive)
            }
        }

        val previousVault = OpenDriveTokenVault.exportCredentials()
        val previousCookies = jar.exportCookies(DOMAINS)
        try {
            AppDatabase.get().runInTransaction {
                if (hasConnectors) {
                    for (drive in AppDatabase.get().getDriveDao().getAll()) {
                        if (drive.type == DriveType.TYPE.WEBDAV.ordinal) {
                            AppDatabase.get().getDriveDao().delete(drive)
                        }
                    }
                    for (drive in restored) AppDatabase.get().getDriveDao().insert(drive)
                }
                if (!OpenDriveTokenVault.replaceCredentials(vault)) {
                    throw IllegalStateException("Unable to restore credentials")
                }
                if (hasCookies && !jar.replaceCookies(DOMAINS, cookies)) {
                    throw IllegalStateException("Unable to restore cookies")
                }
            }
        } catch (error: RuntimeException) {
            val vaultRestored = OpenDriveTokenVault.replaceCredentials(previousVault)
            val cookiesRestored = !hasCookies || jar.replaceCookies(DOMAINS, previousCookies)
            if (!vaultRestored || !cookiesRestored) {
                error.addSuppressed(IllegalStateException("Credential rollback failed"))
            }
            throw error
        }

        OpenDriveTokenStore.clearLegacyCredentials()
        if (hasConnectors && DataCache.isReady()) DataCache.get().reload()
    }

    private fun cookies(value: String): Map<String, String> {
        val result: Map<String, String>? = GSON.fromJson(
            value,
            object : TypeToken<LinkedHashMap<String, String>>() {}.type
        )
        requireNotNull(result) { "Missing cookies" }
        return result
    }

    private data class Connector(val name: String, val url: String, val path: String, val tokenRef: String)
}
