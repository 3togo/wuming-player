package com.wuming.android.tv.utils

import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.wuming.android.tv.ui.custom.CustomSeekView
import com.google.android.material.tabs.TabLayout

/** Breaks observer links from retained adapters/players to a destroyed view tree. */
object ViewCleanup {
    @JvmStatic
    fun release(view: View?) {
        if (view == null) return
        // Visit children before adapters remove their attached holders/pages.
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) release(view.getChildAt(i))
        }
        if (view is TabLayout) view.setupWithViewPager(null)
        if (view is ViewPager) {
            view.clearOnPageChangeListeners()
            view.adapter = null
        }
        if (view is RecyclerView) {
            view.stopScroll()
            view.clearOnScrollListeners()
            val adapter = view.adapter
            view.adapter = null
            // ItemBridgeAdapter also observes the retained Leanback ObjectAdapter.
            AdapterCleanup.release(adapter)
        }
        if (view is PlayerView) view.player = null
        if (view is CustomSeekView) view.setPlayer(null)
        view.animate().cancel()
        view.clearAnimation()
    }
}
