package com.wuming.android.tv.ui.custom

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.AppLog
import java.lang.reflect.Field
import java.util.Arrays
import java.util.Objects

/** Touch scroll support preserving the leanback layout manager's focus position. */
@SuppressLint("RestrictedApi")
open class CustomVerticalGridView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0,
    ) : VerticalGridView(context, attrs, defStyle) {
        companion object {
            private const val TAG = "CustomVerticalGridView"
        }

        private var views: List<View>? = null
        private var pressDown = false
        private var pressUp = false
        private var moveTop = false
        private var mFocusScrollStrategyField: Field? = null
        private var mFocusPositionField: Field? = null
        private var mTouchScrollEnding = false

        init {
            setMoveTop(true)
            addOnScrollListener(
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
                        layoutManager!!.javaClass.getDeclaredField("mFocusScrollStrategy").also { it.isAccessible = true }
                } catch (
                    e: Exception,
                ) {
                    AppLog.w(TAG, "Cannot access mFocusScrollStrategy", e)
                }
            }
            return mFocusScrollStrategyField
        }

        private fun getFocusPositionField(): Field? {
            if (mFocusPositionField == null) {
                try {
                    var clz: Class<*>? = layoutManager!!.javaClass
                    while (clz != null) {
                        try {
                            mFocusPositionField = clz.getDeclaredField("mFocusPosition").also { it.isAccessible = true }
                            break
                        } catch (e: NoSuchFieldException) {
                            clz = clz.superclass
                        }
                    }
                } catch (e: Exception) {
                    AppLog.w(TAG, "Cannot access mFocusPosition", e)
                }
            }
            return mFocusPositionField
        }

        private fun updateFocusPosition(position: Int) {
            try {
                val field = getFocusPositionField()
                if (field != null && layoutManager != null) field.setInt(layoutManager, position)
            } catch (e: Exception) {
                AppLog.w(TAG, "Cannot update mFocusPosition", e)
            }
        }

        private fun setFocusScrollStrategyDirect(strategy: Int) {
            try {
                getFocusScrollStrategyField()?.setInt(layoutManager, strategy)
            } catch (
                e: Exception,
            ) {
                AppLog.w(TAG, "Cannot set focusScrollStrategy", e)
            }
        }

        // Update the first visible item before restoring alignment, avoiding a snap to position zero.
        private fun restoreScrollStrategy() {
            mTouchScrollEnding = false
            val firstVisible = findFirstVisiblePosition()
            if (firstVisible >= 0) {
                updateFocusPosition(firstVisible)
                setSelectedPosition(firstVisible)
            }
            setFocusScrollStrategyDirect(BaseGridView.FOCUS_SCROLL_ALIGNED)
        }

        private fun findFirstVisiblePosition(): Int {
            if (layoutManager == null || adapter == null || childCount == 0) return -1
            var firstPosition = Int.MAX_VALUE
            for (i in 0 until childCount) {
                val position = getChildAdapterPosition(getChildAt(i))
                if (position >= 0 && position < firstPosition) firstPosition = position
            }
            return if (firstPosition == Int.MAX_VALUE) -1 else firstPosition
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    mTouchScrollEnding = false
                    setFocusScrollStrategyDirect(BaseGridView.FOCUS_SCROLL_ITEM)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mTouchScrollEnding = true
                    if (scrollState == RecyclerView.SCROLL_STATE_IDLE) restoreScrollStrategy()
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun initAttributes(
            context: Context,
            attrs: AttributeSet?,
        ) {
            super.initAttributes(context, attrs)
            setOnChildViewHolderSelectedListener(
                object : OnChildViewHolderSelectedListener() {
                    override fun onChildViewHolderSelected(
                        parent: RecyclerView,
                        child: RecyclerView.ViewHolder?,
                        position: Int,
                        subposition: Int,
                    ) {
                        if (pressDown && position == 1) hideHeader()
                        if (pressUp && position == 0) showHeader()
                    }
                },
            )
        }

        open fun setHeader(
            activity: FragmentActivity?,
            vararg layoutIds: Int,
        ) {
            if (activity != null) views = layoutIds.map { activity.findViewById<View>(it) }.filterNotNull()
        }

        open fun setMoveTop(moveTop: Boolean) {
            this.moveTop = moveTop
        }

        open fun hideHeader() {
            views?.forEach { it.visibility = View.INVISIBLE }
        }

        open fun showHeader() {
            views?.forEach { it.visibility = View.VISIBLE }
        }

        open fun isHeaderVisible(): Boolean = views?.any { it.id == R.id.recycler && it.visibility == View.VISIBLE } ?: false

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return moveTop && moveToTop()
            pressUp = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
            pressDown = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            return super.dispatchKeyEvent(event)
        }

        open fun moveToTop(): Boolean {
            val views = views ?: return false
            if (selectedPosition == 0 || adapter == null || adapter!!.itemCount == 0) return false
            for (view in views) if (view.id == R.id.recycler) view.requestFocus()
            scrollToPosition(0)
            showHeader()
            return true
        }
    }
