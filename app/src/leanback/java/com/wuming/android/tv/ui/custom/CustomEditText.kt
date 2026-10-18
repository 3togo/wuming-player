package com.wuming.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.AppCompatEditText
import com.wuming.android.tv.utils.KeyUtil

open class CustomEditText : AppCompatEditText {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    private fun focusSearch(event: KeyEvent): View? {
        if (KeyUtil.isUpKey(event)) return parent.focusSearch(this, FOCUS_UP)
        if (KeyUtil.isDownKey(event)) return parent.focusSearch(this, FOCUS_DOWN)
        if (KeyUtil.isLeftKey(event) && selectionStart == 0) return parent.focusSearch(this, FOCUS_LEFT)
        if (KeyUtil.isRightKey(event) && selectionStart == text!!.length) return parent.focusSearch(this, FOCUS_RIGHT)
        return null
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val view = focusSearch(event)
        return view?.requestFocus() ?: super.onKeyDown(keyCode, event)
    }
}
