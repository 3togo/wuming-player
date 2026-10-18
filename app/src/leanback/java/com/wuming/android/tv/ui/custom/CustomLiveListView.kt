package com.wuming.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.leanback.widget.VerticalGridView
import com.wuming.android.tv.utils.KeyUtil

open class CustomLiveListView : VerticalGridView {
    private var listener: Callback? = null
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    open fun setListener(listener: Callback?) {
        this.listener = listener
    }

    private fun onKeyDown(): Boolean {
        if (selectedPosition != adapter!!.itemCount - 1) return false
        selectedPosition = 0
        return true
    }

    private fun onKeyUp(): Boolean {
        if (selectedPosition != 0) return false
        selectedPosition = adapter!!.itemCount - 1
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (visibility == View.GONE || event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (visibility == View.VISIBLE) listener?.setUITimer()
        if (KeyUtil.isDownKey(event)) return onKeyDown()
        if (KeyUtil.isUpKey(event)) return onKeyUp()
        return super.dispatchKeyEvent(event)
    }

    fun interface Callback {
        fun setUITimer()
    }
}
