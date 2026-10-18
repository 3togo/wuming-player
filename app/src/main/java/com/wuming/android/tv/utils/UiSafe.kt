package com.wuming.android.tv.utils

import android.util.Log
import androidx.recyclerview.widget.RecyclerView

/**
 * Runs RecyclerView mutations only after the current layout pass has finished.
 *
 * Selection callbacks can be delivered while RecyclerView is computing a layout,
 * especially on Leanback devices. Calling notify methods synchronously from those
 * callbacks throws an IllegalStateException. This helper also drops stale
 * work after a view has been detached or its adapter has been replaced.
 */
object UiSafe {
    private const val TAG = "UiSafe"
    private const val MAX_LAYOUT_RETRIES = 12

    @JvmStatic
    fun notifyDataSetChanged(view: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        runWhenIdle(view, adapter) { adapter.notifyDataSetChanged() }
    }

    @JvmStatic
    fun notifyItemRangeChanged(view: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        runWhenIdle(view, adapter) {
            val count = adapter.itemCount
            if (count > 0) adapter.notifyItemRangeChanged(0, count)
        }
    }

    @JvmStatic
    fun notifyItemChanged(view: RecyclerView, adapter: RecyclerView.Adapter<*>, position: Int) {
        runWhenIdle(view, adapter) {
            if (position >= 0 && position < adapter.itemCount) {
                adapter.notifyItemChanged(position)
            }
        }
    }

    @JvmStatic
    fun scrollToPosition(view: RecyclerView?, position: Int) {
        if (view == null) return
        val adapter = view.adapter ?: return
        runWhenIdle(view, adapter) {
            if (position >= 0 && position < adapter.itemCount) {
                view.scrollToPosition(position)
            }
        }
    }

    @JvmStatic
    fun runWhenIdle(view: RecyclerView?, adapter: RecyclerView.Adapter<*>?, action: Runnable?) {
        if (view == null || adapter == null || action == null) return
        view.post { runWhenIdle(view, adapter, action, 0) }
    }

    private fun runWhenIdle(view: RecyclerView, adapter: RecyclerView.Adapter<*>,
                            action: Runnable, attempt: Int) {
        if (!view.isAttachedToWindow || view.adapter !== adapter) return
        if (view.isComputingLayout) {
            retry(view, adapter, action, attempt)
            return
        }
        // This runs on the view's main thread, so RecyclerView cannot begin another
        // layout between the state check and this synchronous mutation.
        action.run()
    }

    private fun retry(view: RecyclerView, adapter: RecyclerView.Adapter<*>,
                      action: Runnable, attempt: Int) {
        if (attempt >= MAX_LAYOUT_RETRIES) {
            Log.w(TAG, "Skipped RecyclerView update after repeated active layout passes")
            return
        }
        view.postOnAnimation { runWhenIdle(view, adapter, action, attempt + 1) }
    }
}
