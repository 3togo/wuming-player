package com.wuming.android.tv.ui.holder

import android.view.View
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.ui.base.VodClickListener
import java.lang.ref.WeakReference

/** Dispatches clicks to the currently bound card without retaining its screen listener. */
internal class VodCardClicks(root: View, listener: VodClickListener?) {
    private val listenerRef = WeakReference(listener)
    var item: Vod? = null

    init {
        root.setOnClickListener {
            val vod = item
            val currentListener = listenerRef.get()
            if (vod != null && currentListener != null) currentListener.onItemClick(vod)
        }
        root.setOnLongClickListener {
            val vod = item
            val currentListener = listenerRef.get()
            if (vod != null && currentListener != null) currentListener.onLongClick(vod) else false
        }
    }
}
