package com.wuming.android.tv.cloud.opendrive

import com.google.gson.JsonObject
import com.wuming.tv.core.cloud.Pan123Provider
import com.wuming.tv.core.media.MediaFileClassifier

/** Android account, cookie, HTTP and encrypted-vault bindings. */
internal object AndroidPan123Provider {
    @JvmStatic fun create(): Pan123Provider = Pan123Provider(object : Pan123Provider.Port {
        override fun token() = OpenDriveTokenStore.get("123pan")
        override fun clearCredentials() {
            OpenDriveTokenStore.clear("123pan")
            try {
                val cookies = com.github.catvod.net.PersistentCookieJar.get()
                cookies.clear("123pan.com")
                cookies.clear("123pan.cn")
                cookies.clear("123684.com")
                cookies.clearWebView("https://yun.123pan.cn/")
            } catch (_: IllegalStateException) { }
        }
        override fun get(url: String, query: Map<String, String>, headers: Map<String, String>): JsonObject =
            OpenDriveHttp.get(url, query, headers)
        override fun post(url: String, body: JsonObject, headers: Map<String, String>): JsonObject =
            OpenDriveHttp.postJson(url, body, headers)
        override fun authExpired() = OpenDriveLog.authExpired("123pan")
        override fun invalidUrl(itemId: String) = OpenDriveLog.resolveInvalid("123pan", itemId, "url_invalid", "")
        override fun userAgent() = OpenDriveHttp.USER_AGENT
    }, OpenDriveHandleVault.shared, MediaFileClassifier::isCloudVideoFileName)
}
