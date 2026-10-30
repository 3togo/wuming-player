package com.wuming.android.tv.ui.custom

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.leanback.widget.BaseGridView
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.utils.AppLog
import java.lang.reflect.Field

/** Keeps both grid orientations aligned to the first visible item after touch scrolling. */
@SuppressLint("RestrictedApi")
internal class GridTouchScroll(private val grid: BaseGridView, private val tag: String) {
    private var mFocusScrollStrategyField: Field? = null
    private var mFocusPositionField: Field? = null
    private var mTouchScrollEnding = false

    init {
        grid.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int,
                ) {
                    if (mTouchScrollEnding && newState == RecyclerView.SCROLL_STATE_IDLE) restoreScrollStrategy()
                }
            },
        )
    }

    private fun getFocusScrollStrategyField(): Field? {
        if (mFocusScrollStrategyField == null) {
            try {
                mFocusScrollStrategyField =
                    grid.layoutManager!!.javaClass.getDeclaredField("mFocusScrollStrategy").also { it.isAccessible = true }
            } catch (
                e: Exception,
            ) {
                AppLog.w(tag, "Cannot access mFocusScrollStrategy", e)
            }
        }
        return mFocusScrollStrategyField
    }

    private fun getFocusPositionField(): Field? {
        if (mFocusPositionField == null) {
            try {
                var clz: Class<*>? = grid.layoutManager!!.javaClass
                while (clz != null) {
                    try {
                        mFocusPositionField = clz.getDeclaredField("mFocusPosition").also { it.isAccessible = true }
                        break
                    } catch (e: NoSuchFieldException) {
                        clz = clz.superclass
                    }
                }
            } catch (e: Exception) {
                AppLog.w(tag, "Cannot access mFocusPosition", e)
            }
        }
        return mFocusPositionField
    }

    private fun updateFocusPosition(position: Int) {
        try {
            val field = getFocusPositionField()
            if (field != null && grid.layoutManager != null) field.setInt(grid.layoutManager, position)
        } catch (e: Exception) {
            AppLog.w(tag, "Cannot update mFocusPosition", e)
        }
    }

    private fun setFocusScrollStrategyDirect(strategy: Int) {
        try {
            getFocusScrollStrategyField()?.setInt(grid.layoutManager, strategy)
        } catch (
            e: Exception,
        ) {
            AppLog.w(tag, "Cannot set focusScrollStrategy", e)
        }
    }

    // Update the first visible item before restoring alignment, avoiding a snap to position zero.
    private fun restoreScrollStrategy() {
        mTouchScrollEnding = false
        val firstVisible = findFirstVisiblePosition()
        if (firstVisible >= 0) {
            updateFocusPosition(firstVisible)
            grid.setSelectedPosition(firstVisible)
        }
        setFocusScrollStrategyDirect(BaseGridView.FOCUS_SCROLL_ALIGNED)
    }

    private fun findFirstVisiblePosition(): Int {
        if (grid.layoutManager == null || grid.adapter == null || grid.childCount == 0) return -1
        var firstPosition = Int.MAX_VALUE
        for (i in 0 until grid.childCount) {
            val position = grid.getChildAdapterPosition(grid.getChildAt(i))
            if (position >= 0 && position < firstPosition) firstPosition = position
        }
        return if (firstPosition == Int.MAX_VALUE) -1 else firstPosition
    }

    fun onTouchEvent(ev: MotionEvent) {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                mTouchScrollEnding = false
                setFocusScrollStrategyDirect(BaseGridView.FOCUS_SCROLL_ITEM)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mTouchScrollEnding = true
                if (grid.scrollState == RecyclerView.SCROLL_STATE_IDLE) restoreScrollStrategy()
            }
        }
    }
}
