package com.wuming.android.tv.model

import com.wuming.android.tv.api.SiteApi
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.bean.Site
import java.util.concurrent.Callable

class SearchTask(
    val site: Site,
    keyword: String?,
    val quick: Boolean,
    val page: String,
) : Callable<Result> {
    val keyword: String = keyword?.trim()?.ifEmpty { null } ?: ""

    @Throws(Exception::class)
    override fun call(): Result {
        if (quick && !site.isQuickSearch) return Result.empty()
        return SiteApi.searchContent(site, keyword, quick, page)
    }

    companion object {
        @JvmStatic
        fun create(site: Site, keyword: String?, quick: Boolean) = SearchTask(site, keyword, quick, "1")

        @JvmStatic
        fun create(site: Site, keyword: String?, quick: Boolean, page: String) = SearchTask(site, keyword, quick, page)
    }
}
