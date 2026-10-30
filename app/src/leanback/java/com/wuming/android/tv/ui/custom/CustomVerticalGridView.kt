package com.wuming.android.tv.ui.custom

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R

/** Touch scroll support preserving the leanback layout manager's focus position. */
@SuppressLint("RestrictedApi")
open class CustomVerticalGridView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0,
    ) : VerticalGridView(context, attrs, defStyle) {
        private var views: List<View>? = null
        private var pressDown = false
        private var pressUp = false
        private var moveTop = false
        private val touchScroll = GridTouchScroll(this, "CustomVerticalGridView")

        init {
            setMoveTop(true)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            touchScroll.onTouchEvent(ev)
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
