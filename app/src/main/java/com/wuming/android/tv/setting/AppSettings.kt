package com.wuming.android.tv.setting

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.wuming.android.tv.App
import com.wuming.android.tv.api.provider.ProviderClient
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Prefers
import com.wuming.tv.core.source.GitHubMirror

open class AppSettings {
    companion object {
        private const val RESTART_MODE = "restart_mode"
        private const val LEGACY_TLS = "legacy_tls"
        private const val TLS_POLICY_INITIALIZED = "tls_policy_initialized"
        private const val LINK_ROUTE = "link_route"
        private const val MOBILE_VIDEO = "mobile_video"
        private const val ROAMING_VIDEO = "roaming_video"

        const val LINK_ROUTE_DIRECT = 0
        const val LINK_ROUTE_BUILTIN = 1

        /*
         * The settings preference file is also used by providers for login state.
         * Clear only app-owned UI/player keys so restart modes never log users out.
         */
        private val SESSION_SETTING_KEYS =
            arrayOf(
                "keyword",
                "wall",
                "wall_type",
                "reset",
                "render",
                "size",
                "scale",
                "scale_live",
                "buffer",
                "video_info",
                "background",
                "site_mode",
                "sync_mode",
                "incognito",
                "boot_live",
                "invert",
                "across",
                "change",
                "update",
                "caption",
                "tunnel",
                "audio_prefer",
                "video_prefer",
                "prefer_aac",
                "danmaku_load",
                "adblock",
                "purify",
                "theme",
                "color_theme",
                "danmaku_show",
                "zhuyin",
                "grid_search_mode",
                "strict_search",
                "search_view",
                "site_list_layout",
                "speed",
                "subtitle_text_size",
                "subtitle_position",
                MOBILE_VIDEO,
                ROAMING_VIDEO,
                LINK_ROUTE,
            )

        @JvmStatic
        fun initializeTlsPolicy() {
            synchronized(AppSettings::class.java) {
                val preferences = Prefers.getPrefers()
                if (preferences.getBoolean(TLS_POLICY_INITIALIZED, false)) return@synchronized
                val legacyTls = initialLegacyTlsValue(preferences.all.keys)
                preferences
                    .edit()
                    .putBoolean(LEGACY_TLS, legacyTls)
                    .putBoolean(TLS_POLICY_INITIALIZED, true)
                    .apply()
            }
        }

        @JvmStatic
        fun initialLegacyTlsValue(existingKeys: Set<String>?): Boolean {
            if (existingKeys.isNullOrEmpty()) return false
            for (key in existingKeys) {
                if (TLS_POLICY_INITIALIZED != key && LEGACY_TLS != key) return true
            }
            return false
        }

        @JvmStatic
        fun isLegacyTlsEnabled(): Boolean = Prefers.getBoolean(LEGACY_TLS)

        @JvmStatic
        fun putLegacyTlsEnabled(enabled: Boolean) {
            OkHttp.setLegacyTlsEnabled(enabled)
            ProviderClient.get().applyLegacyTlsPolicy(enabled)
        }

        @JvmStatic
        fun getDoh(): String? = Prefers.getString("doh")

        @JvmStatic
        fun putDoh(doh: String?) {
            Prefers.put("doh", doh)
        }

        /** The mirror this device picked; empty means GitHub content is fetched direct. */
        @JvmStatic
        fun getGithubProxyHost(): String = GitHubMirror.hostOf(
            Prefers.getString(GitHubMirror.HOST_KEY, null),
            Prefers.getInt(GitHubMirror.LEGACY_INDEX_KEY, GitHubMirror.DEFAULT_INDEX),
        )

        @JvmStatic
        fun putGithubProxyHost(host: String?) {
            Prefers.put(GitHubMirror.HOST_KEY, host.orEmpty())
        }

        @JvmStatic
        fun getLinkRoute(): Int = Prefers.getInt(LINK_ROUTE, defaultLinkRoute())

        @JvmStatic
        fun defaultLinkRoute(): Int = LINK_ROUTE_BUILTIN

        @JvmStatic
        fun putLinkRoute(route: Int) {
            Prefers.put(LINK_ROUTE, if (route == LINK_ROUTE_BUILTIN) LINK_ROUTE_BUILTIN else LINK_ROUTE_DIRECT)
        }

        @JvmStatic
        fun getKeyword(): String? = Prefers.getString("keyword")

        @JvmStatic
        fun putKeyword(keyword: String?) {
            Prefers.put("keyword", keyword)
        }

        @JvmStatic
        fun getHot(): String? = Prefers.getString("hot")

        @JvmStatic
        fun putHot(hot: String?) {
            Prefers.put("hot", hot)
        }

        @JvmStatic
        fun getReset(): Int = Prefers.getInt("reset", 0)

        @JvmStatic
        fun putReset(reset: Int) {
            Prefers.put("reset", reset)
        }

        @JvmStatic
        fun isIncognito(): Boolean = Prefers.getBoolean("incognito")

        @JvmStatic
        fun putIncognito(incognito: Boolean) {
            Prefers.put("incognito", incognito)
        }

        @JvmStatic
        fun isMobileVideoAllowed(): Boolean = Prefers.getBoolean(MOBILE_VIDEO, false)

        @JvmStatic
        fun putMobileVideoAllowed(allowed: Boolean) {
            Prefers.put(MOBILE_VIDEO, allowed)
            com.wuming.android.tv.player.offline.OfflinePlaybackStore
                .updateRequirements()
        }

        @JvmStatic
        fun isRoamingVideoAllowed(): Boolean = Prefers.getBoolean(ROAMING_VIDEO, false)

        @JvmStatic
        fun putRoamingVideoAllowed(allowed: Boolean) {
            Prefers.put(ROAMING_VIDEO, allowed)
            com.wuming.android.tv.player.offline.OfflinePlaybackStore
                .updateRequirements()
        }

        @JvmStatic
        fun isBootLive(): Boolean = Prefers.getBoolean("boot_live")

        @JvmStatic
        fun putBootLive(boot: Boolean) {
            Prefers.put("boot_live", boot)
        }

        @JvmStatic
        fun getUpdate(): Boolean = Prefers.getBoolean("update", true)

        @JvmStatic
        fun putUpdate(update: Boolean) {
            Prefers.put("update", update)
        }

        @JvmStatic
        fun isAdblock(): Boolean = Prefers.getBoolean("adblock", true)

        @JvmStatic
        fun putAdblock(adblock: Boolean) {
            Prefers.put("adblock", adblock)
        }

        @JvmStatic
        fun isPurify(): Boolean = Prefers.getBoolean("purify", true)

        @JvmStatic
        fun putPurify(purify: Boolean) {
            Prefers.put("purify", purify)
        }

        @JvmStatic
        fun getSubscriptions(): String? = Prefers.getString("subscriptions")

        @JvmStatic
        fun putSubscriptions(json: String?) {
            Prefers.put("subscriptions", json)
        }

        @JvmStatic
        fun getSyncMode(): Int = Prefers.getInt("sync_mode")

        @JvmStatic
        fun putSyncMode(mode: Int) {
            Prefers.put("sync_mode", mode)
        }

        @JvmStatic
        fun getRelayUrl(): String? = Prefers.getString("relay_url", "https://ua.fongmi.eu.org/box.php?url=")

        @JvmStatic
        fun putRelayUrl(url: String?) {
            Prefers.put("relay_url", url)
        }

        @JvmStatic
        fun getRestartMode(): Int = Prefers.getInt(RESTART_MODE, 0)

        @JvmStatic
        fun putRestartMode(mode: Int) {
            Prefers.put(RESTART_MODE, mode)
        }

        @JvmStatic
        fun consumeRestartMode(): Int = consumeRestartMode(Prefers.getPrefers())

        @JvmStatic
        fun consumeRestartMode(preferences: SharedPreferences): Int {
            val mode = preferences.getInt(RESTART_MODE, 0)
            if (mode == 1 || mode == 2) {
                preferences.edit().putInt(RESTART_MODE, 0).commit()
            }
            return mode
        }

        @JvmStatic
        fun shouldClearSession(): Boolean = shouldClearSession(getRestartMode())

        @JvmStatic
        fun shouldClearSession(mode: Int): Boolean = mode >= 1 && mode <= 4

        @JvmStatic
        fun shouldClearAll(): Boolean = shouldClearAll(getRestartMode())

        @JvmStatic
        fun shouldClearAll(mode: Int): Boolean = mode == 2 || mode == 4

        @JvmStatic
        fun clearSessionSettings() {
            clearSessionSettings(Prefers.getPrefers())
        }

        @JvmStatic
        fun clearSessionSettings(preferences: SharedPreferences) {
            val editor = preferences.edit()
            for (key in SESSION_SETTING_KEYS) editor.remove(key)
            editor.apply()
        }

        @JvmStatic
        fun hasCaption(): Boolean = Intent(Settings.ACTION_CAPTIONING_SETTINGS).resolveActivity(App.get().getPackageManager()) != null

        @JvmStatic
        fun hasFileManager(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                (
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + App.get().getPackageName()),
                    ).resolveActivity(App.get().getPackageManager()) !=
                        null ||
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).resolveActivity(App.get().getPackageManager()) != null
                )
    }
}
