package com.wuming.android.tv.utils

import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.cloud.CloudStorageProvider
import java.util.Locale

object DownloadedCatVod {
    @JvmStatic
    fun findCloudSite(): Site? = findCloudSite(VodConfig.get().sites)

    @JvmStatic
    @JvmName("findCloudSite")
    internal fun findCloudSite(sites: List<Site>): Site? {
        return com.wuming.tv.core.source.CloudSiteSelectionPolicy.select(sites, ::score)
    }

    private fun score(site: Site?): Int {
        if (site == null) return 0
        return com.wuming.tv.core.source.CloudSiteSelectionPolicy.score(site.type, site.api, site.jar, site.key,
            site.name, VodConfig.OPEN_DRIVE_KEY, CloudStorageProvider.QUARK.matches(site))
    }
}
