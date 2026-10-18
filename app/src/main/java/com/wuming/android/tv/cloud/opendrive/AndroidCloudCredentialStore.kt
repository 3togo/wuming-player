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

        @JvmStatic
        private fun mirrorCookies(providerId: String?, credential: String) {
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
