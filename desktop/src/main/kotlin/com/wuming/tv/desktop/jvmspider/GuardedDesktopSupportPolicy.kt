package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec

/**
 * Desktop support is an explicit decision, not an inference from a provider class name.
 *
 * Entries are measured through the isolated desktop host. Verification is scoped to the
 * capability named by the status; catalogue success does not establish playable media.
 * Unlisted providers stay unverified until measured the same way.
 */
enum class GuardedDesktopSupport(val label: String, val activationAllowed: Boolean) {
    VERIFIED("verified desktop", true),
    VERIFIED_CATALOG("verified desktop catalogue · playback unverified", true),
    VERIFIED_PLAYBACK_URL("verified desktop catalogue · playback URL resolved", true),
    VERIFIED_INDEX("verified desktop discovery", true),
    CLOUD_SEARCH("verified desktop cloud search", true),
    UNVERIFIED("unverified desktop", false),
}

object GuardedDesktopSupportPolicy {
    private val verifiedProviders = setOf(
        "NewCz",
        "Jpys",
        "Libvio",
        "T4",
        "Bttwoo",
        "AppTT",
        "Anime1",
        // Measured 2026-09-07 through the guarded host: home + category both returned data.
        "Alllive",
        "App99",
        "Appgz",
        "Auete",
        "Dm84",
        "Doubao",
        "FirstAid",
        "JPJ",
        "Kanqiu",
        "Music",
        "SixV",
        "Tingshu275",
        "YCyz",
        // Measured 2026-09-08 through the plain DEX host: home returned categories or items.
        "Config",
        "Ddyy",
        "Douban",
        "Dyyj",
        "Fmys",
        "Guazi",
        "Hmer",
        "HongGuo",
        "Ikanbot",
        "Jinpai",
        "Kugou",
        "Kwps",
        "Living",
        "Nfdj",
        "Qqys",
        "Wwys",
        "Xb6v",
        "Yhdm",
        "Ysj",
    )
    private val cloudProviders = setOf(
        "MyDrive",
        "Seedhub",
        "S_zps",
        "YpanSo",
        "BpanSo",
        "KkSs",
        "UuSs",
        "WoGG",
    )
    private val verifiedIndexProviders = setOf("DouDou")
    // 2026-09-16: home, category, search and episode details passed on Linux without
    // Android bridges. Playback returned no usable URL. See docs/desktop-site-verification.md.
    private val verifiedCatalogProviders = setOf("Nmyswv", "Bili")

    fun classify(site: JvmSpiderSiteSpec): GuardedDesktopSupport {
        val provider = site.api.removePrefix("csp_").removeSuffix("Guard")
        return when (provider) {
            in verifiedProviders -> GuardedDesktopSupport.VERIFIED
            in verifiedCatalogProviders -> GuardedDesktopSupport.VERIFIED_CATALOG
            in verifiedIndexProviders -> GuardedDesktopSupport.VERIFIED_INDEX
            in cloudProviders -> GuardedDesktopSupport.CLOUD_SEARCH
            else -> GuardedDesktopSupport.UNVERIFIED
        }
    }
}
