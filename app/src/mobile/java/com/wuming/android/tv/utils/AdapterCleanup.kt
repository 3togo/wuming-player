package com.wuming.android.tv.utils

import androidx.recyclerview.widget.RecyclerView

internal object AdapterCleanup {
    @JvmStatic fun release(adapter: RecyclerView.Adapter<*>?) { // No additional ObjectAdapter subscription on mobile.
    }
}
