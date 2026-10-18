package com.wuming.android.tv.cloud.opendrive

import android.content.Context
import android.content.SharedPreferences
import com.github.catvod.Init
import com.github.catvod.Init.context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.tv.core.cloud.OpenDriveUserCredential
import com.wuming.tv.core.cloud.UserCredentialMethod
import java.util.ArrayList

/**
 * User-registered, non-netdisk service credentials (danmaku / VIP parse site /
 * live OAuth / ...).
 *
 *
 * Metadata is kept in app-private preferences; the secret (cookie, token, or
 * password pair) is delegated to [OpenDriveTokenStore], which encrypts it in
 * the Android keystore-backed vault and is already covered by
 * AccountCredentialBackup. This store is intentionally NOT an [OpenDriveProvider]:
 * it does not browse files, it only stores, refreshes, and hands out credentials to
 * other subsystems (danmaku, source/live resolution).
 *
 *
 * Per-service protocol evidence (exact request order, headers, cookie shape) is
 * still required before any consumer auto-logs-in a service; this store only holds
 * credentials the user has supplied or that a reviewed login flow produced.
 */
object UserCredentialStore {
    private const val PREFS = "opendrive_usercred_meta"
    private val GSON = Gson()
    private const val PROVIDER_ID_PATTERN = "[a-z0-9][a-z0-9_-]{0,31}"

    @JvmStatic
    fun list(): MutableList<OpenDriveUserCredential> {
        val result: MutableList<OpenDriveUserCredential> = ArrayList<OpenDriveUserCredential>()
        for (serviceId: String in metaPrefs()!!.getAll().keys) {
            val credential: OpenDriveUserCredential? = decode(serviceId)
            if (credential != null) result.add(credential)
        }
        return result
    }

    @JvmStatic
    fun get(serviceId: String?): OpenDriveUserCredential? = decode(serviceId)

    /**
     * Finds the credential whose configured `baseUrl` host matches `url`'s
     * host (exact or as a subdomain). Lets any consumer attach the right stored secret
     * without hardcoding service ids. Returns null when nothing matches.
     */
    @JvmStatic
    fun findByUrl(url: String?): OpenDriveUserCredential? {
        if (UserCredentialHosts.hostOf(url) == null) return null
        for (credential: OpenDriveUserCredential in list()) {
            if (UserCredentialHosts.matches(credential.baseUrl, url)) return credential
        }
        return null
    }

    /** Secret as a raw `Cookie` header string, or empty when absent/unsuitable.  */
    @JvmStatic
    fun getCookie(serviceId: String?): String {
        val credential: OpenDriveUserCredential? = decode(serviceId)
        if (credential == null || credential.method != UserCredentialMethod.COOKIE) return ""
        return OpenDriveTokenStore.get(serviceId)
    }

    /** Secret as a bearer/token string, or empty when absent/unsuitable.  */
    @JvmStatic
    fun getToken(serviceId: String?): String {
        val credential: OpenDriveUserCredential? = decode(serviceId)
        if (credential == null || credential.method != UserCredentialMethod.OAUTH_TOKEN) return ""
        return OpenDriveTokenStore.get(serviceId)
    }

    /** Raw secret regardless of method (caller must apply it correctly).  */
    @JvmStatic
    fun getSecret(serviceId: String?): String =
        if (decode(serviceId) == null) "" else OpenDriveTokenStore.get(serviceId)

    @JvmStatic
    fun save(
        serviceId: String?,
        displayName: String?,
        baseUrl: String?,
        method: UserCredentialMethod?,
        secret: String?
    ) {
        require(!(serviceId == null || !serviceId.matches(PROVIDER_ID_PATTERN.toRegex()))) {
            "Use a service id of letters/digits/-/_ (<=32 chars)"
        }
        require(
            !(
                displayName == null || displayName.trim { it <= ' ' }
                    .isEmpty()
                )
        ) { "Service display name is required" }
        requireNotNull(method) { "Credential method is required" }
        require(!(secret == null || secret.isEmpty())) { "Credential secret is required" }
        val meta: JsonObject = JsonObject()
        meta.addProperty("serviceId", serviceId)
        meta.addProperty("displayName", displayName.trim { it <= ' ' })
        meta.addProperty("baseUrl", if (baseUrl == null) "" else baseUrl.trim { it <= ' ' })
        meta.addProperty("method", method.name)
        meta.addProperty("expiresAtEpochMillis", 0L)
        metaPrefs()!!.edit().putString(serviceId, GSON.toJson(meta)).apply()
        OpenDriveTokenStore.put(serviceId, secret)
    }

    @JvmStatic
    fun delete(serviceId: String?) {
        if (serviceId == null) return
        metaPrefs()!!.edit().remove(serviceId).apply()
        OpenDriveTokenStore.clear(serviceId)
    }

    @JvmStatic
    private fun decode(serviceId: String?): OpenDriveUserCredential? {
        try {
            val json: String? = metaPrefs()!!.getString(serviceId, "")
            if (json == null || json.isEmpty()) return null
            val meta: JsonObject? = JsonParser.parseString(json).getAsJsonObject()
            val name: String = OpenDriveHttp.string(meta, "displayName")
            val baseUrl: String = OpenDriveHttp.string(meta, "baseUrl")
            val methodName: String = OpenDriveHttp.string(meta, "method")
            val method: UserCredentialMethod =
                if (methodName.isEmpty()) {
                    UserCredentialMethod.COOKIE
                } else {
                    UserCredentialMethod.valueOf(methodName)
                }
            val expires: Long = OpenDriveHttp.number(meta, "expiresAtEpochMillis")
            return OpenDriveUserCredential(serviceId!!, name, baseUrl, method, expires)
        } catch (ignored: RuntimeException) {
            return null
        }
    }

    @JvmStatic
    private fun metaPrefs(): SharedPreferences? =
        context()!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
