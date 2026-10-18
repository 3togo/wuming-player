package com.wuming.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton

open class CustomFabBehavior : FloatingActionButton.Behavior {
    constructor() : super()
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    override fun onStartNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: FloatingActionButton,
        directTargetChild: View,
        target: View,
        axes: Int,
        type: Int,
    ): Boolean =
        axes == ViewCompat.SCROLL_AXIS_VERTICAL ||
            super.onStartNestedScroll(coordinatorLayout, child, directTargetChild, target, axes, type)

    override fun onNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: FloatingActionButton,
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray,
    ) {
        super.onNestedScroll(coordinatorLayout, child, target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed)
        if ("top" == child.tag) {
            if (dyConsumed > 0 && child.visibility == View.INVISIBLE) {
                child.show()
            } else if (dyConsumed < 0 && child.visibility == View.VISIBLE) {
                onHide(child)
            }
        } else {
            if (dyConsumed > 0 && child.visibility == View.VISIBLE) {
                onHide(child)
            } else if (dyConsumed < 0 && child.visibility == View.INVISIBLE) {
                child.show()
            }
        }
    }

    private fun onHide(child: FloatingActionButton) {
        child.hide(
            object : FloatingActionButton.OnVisibilityChangedListener() {
                override fun onHidden(fab: FloatingActionButton) {
                    child.visibility = View.INVISIBLE
                }
            },
        )
    }
}
