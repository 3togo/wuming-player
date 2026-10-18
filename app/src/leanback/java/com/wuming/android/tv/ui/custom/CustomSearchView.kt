package com.wuming.android.tv.ui.custom

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.animation.Animation
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.ResUtil

open class CustomSearchView : CustomEditText {
    private var flicker: Animation? = null
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        flicker = ResUtil.getAnim(R.anim.flicker)
    }

    override fun isFocused(): Boolean = true

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) startAnimation(flicker) else clearAnimation()
    }
}
