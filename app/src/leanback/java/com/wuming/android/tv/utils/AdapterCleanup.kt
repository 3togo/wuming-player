package com.wuming.android.tv.utils

import androidx.leanback.widget.ItemBridgeAdapter
import androidx.recyclerview.widget.RecyclerView

internal object AdapterCleanup {
    @JvmStatic fun release(adapter: RecyclerView.Adapter<*>?) {
        if (adapter is ItemBridgeAdapter) adapter.setAdapter(null)
    }
}
