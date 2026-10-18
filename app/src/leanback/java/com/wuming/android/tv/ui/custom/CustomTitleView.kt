package com.wuming.android.tv.ui.custom

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.animation.Animation
import androidx.appcompat.widget.AppCompatTextView
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.impl.SiteCallback
import com.wuming.android.tv.utils.KeyUtil
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.SiteRanker
import java.util.ArrayList

open class CustomTitleView : AppCompatTextView {
    private var listener: Listener? = null
    private var flicker: Animation? = null
    private var coolDown = false

    private fun getHome(): Site = VodConfig.get().getHome()
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        flicker = ResUtil.getAnim(R.anim.flicker)
    }

    open fun setListener(listener: Listener?) {
        this.listener = listener
        setOnClickListener { listener!!.showDialog() }
    }

    private fun hasEvent(event: KeyEvent): Boolean =
        !getHome().isEmpty() && (KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event) || (KeyUtil.isUpKey(event) && !coolDown))

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (focused) startAnimation(flicker) else clearAnimation()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!hasEvent(event)) return super.dispatchKeyEvent(event)
        onKeyDown(event)
        return true
    }

    private fun onKeyDown(event: KeyEvent) {
        if (KeyUtil.isActionDown(event) && KeyUtil.isUpKey(event)) {
            onKeyUp()
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isLeftKey(event)) {
            listener!!.setSite(getSite(false))
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isRightKey(event)) {
            listener!!.setSite(getSite(true))
        }
    }

    private fun onKeyUp() {
        App.post({ coolDown = false }, 3000)
        listener!!.onRefresh()
        coolDown = true
    }

    private fun getSite(next: Boolean): Site {
        val items = getSites()
        if (items.isEmpty()) return Site()
        var position = items.indexOf(getHome())
        if (position < 0) position = 0
        position = if (next) (position + 1) % items.size else (position - 1 + items.size) % items.size
        return items[position]
    }

    private fun getSites(): List<Site> {
        val sites = ArrayList<Site>()
        for (site in VodConfig.get().getSites()) if (!site.isHide()) sites.add(site)
        // Keep the first "All" entry ahead of ranked sites.
        val allSite = if (sites.isEmpty()) null else sites.removeAt(0)
        val ranked = SiteRanker.get().rankByPerformance(sites)
        val result = ArrayList<Site>()
        if (allSite != null) result.add(allSite)
        result.addAll(ranked)
        return result
    }

    interface Listener : SiteCallback {
        fun showDialog()

        fun onRefresh()
    }
}
