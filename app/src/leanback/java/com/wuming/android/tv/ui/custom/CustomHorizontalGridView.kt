package com.wuming.android.tv.ui.custom

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.leanback.widget.HorizontalGridView

/** Touch scroll support preserving the leanback layout manager's focus position. */
@SuppressLint("RestrictedApi")
open class CustomHorizontalGridView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0,
    ) : HorizontalGridView(context, attrs, defStyle) {
        private val touchScroll = GridTouchScroll(this, "CustomHorizontalGridView")

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            touchScroll.onTouchEvent(ev)
            return super.dispatchTouchEvent(ev)
        }
    }
