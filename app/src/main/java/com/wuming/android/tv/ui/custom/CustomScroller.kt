package com.wuming.android.tv.ui.custom

import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Result

open class CustomScroller(
    private val callback: Callback?,
) : RecyclerView.OnScrollListener() {
    private var loading = false
    private var enable = true
    private var page = 1

    override fun onScrolled(
        view: RecyclerView,
        dx: Int,
        dy: Int,
    ) {
        if (isDisable() || isLoading() || view.scrollState == RecyclerView.SCROLL_STATE_IDLE || callback == null) return
        if (!view.canScrollVertically(1) && dy > 0) callback.onLoadMore((++page).toString())
    }

    open fun reset() {
        enable = true
        page = 1
    }

    open fun addPage(): Int = ++page

    open fun first(): Boolean = page == 1

    open fun setPage(page: Int) {
        this.page = page
    }

    open fun isLoading(): Boolean = loading

    open fun setLoading(loading: Boolean) {
        this.loading = loading
    }

    open fun isDisable(): Boolean = !enable

    open fun setEnable(pageCount: Int) {
        enable = page < pageCount || pageCount == 0
    }

    open fun endLoading(result: Result) {
        if (result.getList().isEmpty()) page--
        setEnable(result.getPageCount())
        setLoading(false)
    }

    fun interface Callback {
        fun onLoadMore(page: String)
    }
}
