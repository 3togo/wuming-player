package com.wuming.android.tv.cloud.opendrive

import com.github.catvod.net.PersistentCookieJar
import com.wuming.tv.core.runtime.cloudlogin.CloudCredentialStore
import com.wuming.android.tv.utils.AppLog
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Bridges the shared Kotlin QR-login providers (`cloud-login-runtime-core`)
 * onto Android's credential storage.
 *
 *
 * The Kotlin providers only ever read/write a single opaque credential string
 * per provider id. AliPan stores an OAuth token JSON; Baidu/Quark/UC store a
 * `Cookie` header string. Android's storage code already reads AliPan tokens
 * from [OpenDriveTokenStore] and cookie providers from [PersistentCookieJar],
 * so this store keeps [OpenDriveTokenStore] authoritative (lossless for both
 * formats) and mirrors cookie providers into the persistent jar so that the
 * existing `AccountCredentialBackup` export/import keeps covering them.
 */
class AndroidCloudCredentialStore : CloudCredentialStore {
    override fun read(providerId: String): String {
        val stored: String = OpenDriveTokenStore.get(providerId)
        if (!stored.codePoints().allMatch { Character.isWhitespace(it) }) return stored
        if (isCookieProvider(providerId)) return rebuildCookieHeader(providerId)
        return ""
    }

    override fun write(providerId: String, credential: String) {
        if (credential.codePoints().allMatch { Character.isWhitespace(it) }
        ) {
            return
        }
        OpenDriveTokenStore.put(providerId, credential)
        if (isCookieProvider(providerId)) mirrorCookies(providerId, credential)
    }

    override fun remove(providerId: String) {
        OpenDriveTokenStore.clear(providerId)
        if (isCookieProvider(providerId)) {
            val host: String = hostFor(providerId)!!
            try {
                PersistentCookieJar.get().clear(host)
                PersistentCookieJar.get().clearWebView("https://" + host + "/")
            } catch (e: IllegalStateException) {
                AppLog.w("CloudCredential", "cookie clear failed provider=$providerId: ${e.message}")
            }
        }
    }

    companion object {
        @JvmStatic
        private fun isCookieProvider(providerId: String?): Boolean =
            "baidu" == providerId || "quark" == providerId || "uc" == providerId

        /**
         * Returns the cookie-provider id encoded in a vault reference
         * (e.g. "quark" for "vault:opendrive/quark/token"), or null when the
         * reference does not belong to a provider whose session lives in the cookie jar.
         */
        @JvmStatic
        fun cookieProviderOf(reference: String?): String? {
            if (reference == null || !reference.startsWith("vault:opendrive/")) return null
            val providerId: String = reference
                .removePrefix("vault:opendrive/")
                .substringBefore('/')
            return if (isCookieProvider(providerId)) providerId else null
        }

        @JvmStatic
        private fun hostFor(providerId: String?): String? {
            if ("baidu" == providerId) return "baidu.com"
            if ("quark" == providerId) return "quark.cn"
            if ("uc" == providerId) return "uc.cn"
            return providerId
        }

        @JvmStatic
        private fun rebuildCookieHeader(providerId: String?): String {
            val header: StringBuilder = StringBuilder()
            try {
                for (cookie: Cookie in PersistentCookieJar.get()
                    .loadForStorageDomain(hostFor(providerId))) {
                    if (header.length > 0) header.append("; ")
                    header.append(cookie.name).append('=').append(cookie.value)
                }
            } catch (e: IllegalStateException) {
                AppLog.w("CloudCredential", "cookie rebuild failed provider=$providerId: ${e.message}")
            }
            return header.toString()
        }

        /**
         * Re-establishes one cookie provider's jar mirror after a vault restore: the storage
         * domain is cleared first so a previous account's stale cookies cannot survive beside
         * the restored session, then the vault token is mirrored exactly as a login would.
         */
        @JvmStatic
        fun restoreCookieMirror(providerId: String?, credential: String) {
            if (!isCookieProvider(providerId) ||
                credential.codePoints().allMatch { Character.isWhitespace(it) }
            ) {
                return
            }
            try {
                val host: String = hostFor(providerId)!!
                PersistentCookieJar.get().clear(host)
                mirrorCookies(providerId, credential)
            } catch (e: IllegalStateException) {
                AppLog.w("CloudCredential", "cookie mirror failed provider=$providerId: ${e.message}")
            }
        }

        @JvmStatic
        fun mirrorCookies(providerId: String?, credential: String) {
            try {
                val url: HttpUrl? = ("https://" + hostFor(providerId) + "/").toHttpUrlOrNull()
                if (url == null) return
                PersistentCookieJar.get().saveFromResponse(
                    url,
                    PersistentCookieJar.parseCookieHeader(credential, hostFor(providerId)!!)
                )
            } catch (e: Exception) {
                AppLog.w("CloudCredential", "cookie mirror failed provider=$providerId: ${e.message}")
            }
        }

    }
}
