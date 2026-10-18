package com.wuming.android.tv.ui.custom

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.LinearLayoutCompat
import com.wuming.android.tv.R
import java.util.ArrayList

/** Rebuilds horizontal D-pad links for visible actions without moving focus. */
open class VisibleFocusLinearLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayoutCompat(context, attrs, defStyleAttr) {
        private val focusableChildren = ArrayList<View>()
        private var wrapAround = false

        init {
            if (attrs != null) {
                val values = context.obtainStyledAttributes(attrs, R.styleable.VisibleFocusLinearLayout, defStyleAttr, 0)
                wrapAround = values.getBoolean(R.styleable.VisibleFocusLinearLayout_focusWrapAround, false)
                values.recycle()
            }
        }

        override fun onLayout(
            changed: Boolean,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) {
            super.onLayout(changed, left, top, right, bottom)
            updateFocusLinks()
        }

        open fun updateFocusLinks() {
            focusableChildren.clear()
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.VISIBLE && child.isEnabled && child.isFocusable &&
                    child.id != View.NO_ID
                ) {
                    focusableChildren.add(child)
                }
            }
            val size = focusableChildren.size
            for (i in 0 until size) {
                val child = focusableChildren[i]
                val previous =
                    if (i > 0) {
                        focusableChildren[i - 1]
                    } else if (wrapAround && size > 1) {
                        focusableChildren[size - 1]
                    } else {
                        null
                    }
                val next =
                    if (i + 1 < size) {
                        focusableChildren[i + 1]
                    } else if (wrapAround && size > 1) {
                        focusableChildren[0]
                    } else {
                        null
                    }
                if (previous != null) child.nextFocusLeftId = previous.id
                if (next != null) child.nextFocusRightId = next.id
            }
        }
    }
