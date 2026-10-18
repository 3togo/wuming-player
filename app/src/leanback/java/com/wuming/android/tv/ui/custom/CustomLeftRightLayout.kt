package com.wuming.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.LinearLayout
import com.wuming.android.tv.utils.KeyUtil

open class CustomLeftRightLayout : LinearLayout {
    private var leftListener: LeftListener? = null
    private var rightListener: RightListener? = null
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    open fun setLeftListener(listener: LeftListener?) {
        leftListener = listener
    }

    open fun setRightListener(listener: RightListener?) {
        rightListener = listener
    }

    private fun hasEvent(event: KeyEvent): Boolean =
        KeyUtil.isActionDown(event) &&
            ((leftListener != null && KeyUtil.isLeftKey(event)) || (rightListener != null && KeyUtil.isRightKey(event)))

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (hasEvent(event)) onKeyDown(event) else super.dispatchKeyEvent(event)

    private fun onKeyDown(event: KeyEvent): Boolean {
        if (KeyUtil.isLeftKey(event)) leftListener?.onLeft()
        if (KeyUtil.isRightKey(event)) rightListener?.onRight()
        return true
    }

    fun interface LeftListener {
        fun onLeft()
    }

    fun interface RightListener {
        fun onRight()
    }
}
