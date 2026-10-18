package com.wuming.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatTextView
import com.wuming.android.tv.utils.KeyUtil

open class CustomUpDownView : AppCompatTextView {
    private var upListener: UpListener? = null
    private var downListener: DownListener? = null
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    open fun setUpListener(listener: UpListener?) {
        upListener = listener
    }

    open fun setDownListener(listener: DownListener?) {
        downListener = listener
    }

    private fun hasEvent(event: KeyEvent): Boolean =
        KeyUtil.isActionDown(event) &&
            ((upListener != null && KeyUtil.isUpKey(event)) || (downListener != null && KeyUtil.isDownKey(event)))

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (hasEvent(event)) onKeyDown(event) else super.dispatchKeyEvent(event)

    private fun onKeyDown(event: KeyEvent): Boolean {
        if (KeyUtil.isUpKey(event)) upListener?.onUp()
        if (KeyUtil.isDownKey(event)) downListener?.onDown()
        return true
    }

    fun interface UpListener {
        fun onUp()
    }

    fun interface DownListener {
        fun onDown()
    }
}
