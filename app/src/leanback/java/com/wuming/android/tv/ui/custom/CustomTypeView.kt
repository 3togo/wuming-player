package com.wuming.android.tv.ui.custom

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatTextView
import com.wuming.android.tv.App
import com.wuming.android.tv.utils.KeyUtil

open class CustomTypeView : AppCompatTextView {
    private var listener: Listener? = null
    private var coolDown = false
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    open fun setListener(listener: Listener?) {
        this.listener = listener
    }

    private fun hasEvent(event: KeyEvent): Boolean = !coolDown && KeyUtil.isActionDown(event) && KeyUtil.isUpKey(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (hasEvent(event)) onKeyDown() else super.dispatchKeyEvent(event)

    private fun onKeyDown(): Boolean {
        App.post({ coolDown = false }, 3000)
        listener!!.onRefresh()
        coolDown = true
        return true
    }

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        App.post({ coolDown = false }, 500)
        if (focused) coolDown = true
    }

    fun interface Listener {
        fun onRefresh()
    }
}
