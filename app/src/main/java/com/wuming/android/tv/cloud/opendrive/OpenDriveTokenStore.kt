package com.wuming.android.tv.cloud.opendrive

import android.content.Context
import android.content.SharedPreferences
import android.webkit.ValueCallback
import android.webkit.WebView
import com.github.catvod.Init
import com.github.catvod.Init.context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.AppLog.e
import com.wuming.tv.core.cloud.CredentialShapePolicy
import com.wuming.tv.core.cloud.CredentialShapePolicy.isOpaqueToken
import com.wuming.tv.core.cloud.FirstPartyOriginPolicy
import com.wuming.tv.core.cloud.FirstPartyOriginPolicy.httpsHost
import com.wuming.tv.core.cloud.FirstPartyOriginPolicy.isWithinDomain

/** App-private tokens captured only from allowlisted first-party WebView origins.  */
object OpenDriveTokenStore {
    private const val PREFS = "opendrive_credentials"

    @JvmStatic
    fun capture(provider: CloudTokenProvider?, webView: WebView?, url: String?) {
        if (provider == null || webView == null || !provider.trusted(url)) return
        webView.evaluateJavascript(
            provider.script()!!,
            ValueCallback { result: String? ->
                val value: String? = decodeJavascriptString(result)
                if (provider.valid(value)) put(provider.key, value)
            }
        )
    }

    @JvmStatic
    fun get(providerId: String?): String {
        try {
            val reference: String = tokenReference(providerId)
            val secured: String = OpenDriveTokenVault.get(reference)
            if (!secured.codePoints().allMatch { Character.isWhitespace(it) }) return secured
            // Builds before the token-manager rename stored provider sessions
            // under the same encrypted vault's "credential" handle. Preserve
            // those sessions across upgrade instead of silently treating a
            // logged-in account as signed out and falling back to a legacy JAR.
            val legacySecured: String = OpenDriveTokenVault.get(legacyTokenReference(providerId))
            if (!legacySecured.codePoints().allMatch { Character.isWhitespace(it) } &&
                OpenDriveTokenVault.put(reference, legacySecured) &&
                legacySecured == OpenDriveTokenVault.get(reference)
            ) {
                OpenDriveTokenVault.remove(legacyTokenReference(providerId))
                return legacySecured
            }
            // One-time migration from versions which used app-private plaintext preferences.
            val legacy: String? = preferences()!!.getString(providerId, "")
            if (legacy != null && !legacy.codePoints().allMatch { Character.isWhitespace(it) } &&
                OpenDriveTokenVault.put(reference, legacy)
            ) {
                preferences()!!.edit().remove(providerId).apply()
            }
            return if (legacy == null) "" else legacy
        } catch (e: Exception) {
            e("OpenDriveTokenStore", e)
            return ""
        }
    }

    @JvmStatic
    fun put(providerId: String?, value: String?) {
        if (providerId == null || value == null || value.length > 32768) return
        try {
            if (OpenDriveTokenVault.put(tokenReference(providerId), value)) {
                preferences()!!.edit().remove(providerId).apply()
            }
        } catch (e: Exception) {
            e("OpenDriveTokenStore", e)
        }
    }

    @JvmStatic
    fun clear(providerId: String?) {
        try {
            OpenDriveTokenVault.remove(tokenReference(providerId))
            OpenDriveTokenVault.remove(legacyTokenReference(providerId))
            preferences()!!.edit().remove(providerId).apply()
        } catch (e: Exception) {
            e("OpenDriveTokenStore", e)
        }
    }

    @JvmStatic
    fun clearLegacyCredentials() {
        preferences()!!.edit().clear().apply()
    }

    @JvmStatic
    private fun preferences(): SharedPreferences? =
        context()!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    private fun tokenReference(providerId: String?): String =
        OpenDriveTokenVault.reference(providerId, "token")

    @JvmStatic
    private fun legacyTokenReference(providerId: String?): String =
        OpenDriveTokenVault.reference(providerId, "credential")

    @JvmStatic
    private fun decodeJavascriptString(result: String?): String? {
        try {
            val value: JsonElement = JsonParser.parseString(if (result == null) "null" else result)
            return if (value.isJsonPrimitive()) value.getAsString() else ""
        } catch (ignored: Exception) {
            return ""
        }
    }

    enum class CloudTokenProvider(key: String) {
        ALIPAN("alipan") {
            override fun trusted(url: String?): Boolean = CloudTokenProvider.trustedHost(
                url,
                "alipan.com"
            ) || CloudTokenProvider.trustedHost(url, "aliyundrive.com")

            override fun script(): String =
                "(function(){return localStorage.getItem('token')||sessionStorage.getItem('token')||''})()"

            override fun valid(value: String?): Boolean {
                try {
                    val token: JsonObjectToken = JsonObjectToken.parse(value)
                    return token.accessToken!!.length >= 20 && token.refreshToken!!.length >= 20
                } catch (ignored: Exception) {
                    return false
                }
            }
        },
        PAN123("123pan") {
            override fun trusted(url: String?): Boolean = CloudTokenProvider.trustedHost(
                url,
                "123pan.com"
            ) || CloudTokenProvider.trustedHost(url, "123pan.cn") ||
                CloudTokenProvider.trustedHost(url, "123684.com")

            override fun script(): String =
                "(function(){return localStorage.getItem('authorToken')||''})()"

            override fun valid(value: String?): Boolean = isOpaqueToken(value)
        };

        val key: String?

        init {
            this.key = key
        }

        abstract fun trusted(url: String?): Boolean

        abstract fun script(): String?

        abstract fun valid(value: String?): Boolean

        companion object {
            @JvmStatic
            private fun trustedHost(value: String?, domain: String): Boolean = isWithinDomain(
                httpsHost(value),
                domain,
                true
            )
        }
    }

    @JvmRecord
    private data class JsonObjectToken(val accessToken: String?, val refreshToken: String?) {
        companion object {
            @JvmStatic
            fun parse(value: String?): JsonObjectToken {
                val `object`: JsonObject? = JsonParser.parseString(value).getAsJsonObject()
                return JsonObjectToken(
                    OpenDriveHttp.string(`object`, "access_token"),
                    OpenDriveHttp.string(`object`, "refresh_token")
                )
            }
        }
    }
}
