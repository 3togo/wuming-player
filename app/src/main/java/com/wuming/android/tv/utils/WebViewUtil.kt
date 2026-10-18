package com.wuming.android.tv.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.CookieManager
import com.wuming.android.tv.App
import com.wuming.android.tv.utils.AppLog
import java.util.Collections
import java.util.HashSet

open class WebViewUtil {
    companion object {
        private const val TAG = "WebViewUtil"
        private const val SYSTEM_SETTINGS_PACKAGE = "com.android.settings"
        private val BROWSER_PACKAGES =
            Collections.unmodifiableSet(
                HashSet(
                    java.util.Arrays.asList(
                        "com.android.chrome",
                        "com.mi.globalbrowser",
                        "com.huawei.browser",
                        "com.heytap.browser",
                        "com.vivo.browser",
                    ),
                ),
            )

        private fun installed(
            pm: PackageManager,
            pkg: String,
        ): Boolean =
            try {
                pm.getPackageInfo(pkg, PackageManager.GET_META_DATA)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

        @JvmStatic fun spoof(): String {
            val pm = App.get().packageManager
            return BROWSER_PACKAGES.firstOrNull { installed(pm, it) } ?: SYSTEM_SETTINGS_PACKAGE
        }

        @JvmStatic fun support(): Boolean =
            try {
                CookieManager.getInstance()
                App.get().packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)
            } catch (e: Throwable) {
                AppLog.d(TAG, "WebView support check failed", e)
                false
            }

        @JvmStatic fun openInBrowser(
            context: Context,
            url: String?,
        ) {
            try {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Open in browser"))
            } catch (
                e: Exception,
            ) {
                AppLog.e(TAG, e)
            }
        }
    }
}
