package com.wuming.tv.core.catalog

/**
 * Platform-neutral read-only views over the catalogue domain. The Android [com.wuming.android.tv.bean]
 * entities implement these directly, and the desktop [CatalogItem]/[CatalogSource] satisfy the same
 * contract, so UI and policy code can read a VOD or a site without depending on an Android bean.
 */
interface VodView {
    val id: String
    val name: String
    val typeName: String
    val pic: String
    val remarks: String
    val year: String
    val area: String
    val director: String
    val actor: String
    val content: String
}

interface SiteView {
    val key: String
    val name: String
    val api: String
    val ext: String
    val jar: String
    val click: String
    val playUrl: String
}

interface ConfigView {
    val id: Int
    val type: Int
    val url: String
    val name: String
    val desc: String
}

/**
 * A catalogue candidate that can be ranked by a preference profile. Lives in [catalog-core] (not
 * [com.wuming.tv.core.library.recommend]) so every consumer of [CatalogItem] already has it on the
 * classpath; the ranking policy in `user-library-core` depends on this module.
 */
interface RecommendationCandidate {
    val itemId: String
    val typeName: String
    val year: String
    val area: String
    val director: String
    val actor: String
    val content: String
}
