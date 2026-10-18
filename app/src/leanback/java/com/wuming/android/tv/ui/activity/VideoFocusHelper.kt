package com.wuming.android.tv.ui.activity

import android.view.View
import android.view.ViewParent
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.FocusOrder
import java.util.Arrays
import java.util.function.IntFunction

class VideoFocusHelper(
    private val finder: IntFunction<View?>,
) {
    fun findFocusDown(current: Int): Int = FocusOrder.after(DETAIL_FOCUS_ORDER, current, this::isFocusTargetAvailable)

    fun findFocusUp(current: Int): Int = FocusOrder.before(DETAIL_FOCUS_ORDER, current, this::isFocusTargetAvailable)

    fun isFocusTargetAvailable(id: Int): Boolean {
        val view = finder.apply(id) ?: return false
        if (!view.isEnabled || !isEffectivelyVisible(view)) return false
        if (view is RecyclerView) return (view.adapter?.itemCount ?: 0) > 0
        return view.isFocusable
    }

    companion object {
        @JvmField val DETAIL_FOCUS_ORDER: List<Int> =
            Arrays.asList(
                R.id.flag,
                R.id.quality,
                R.id.episode_reverse,
                R.id.episode_rank,
                R.id.episode,
                R.id.array,
                R.id.part,
                R.id.quick,
            )

        @JvmStatic fun isEffectivelyVisible(view: View?): Boolean {
            var current = view
            while (current != null) {
                if (current.visibility != View.VISIBLE) return false
                current = current.parent as? View
            }
            return true
        }

        @JvmStatic fun setNextFocusDown(
            target: Int,
            vararg views: View,
        ) {
            for (view in views) view.nextFocusDownId = targetOrSelf(target, view)
        }

        @JvmStatic fun setVerticalFocus(
            up: Int,
            down: Int,
            vararg views: View,
        ) {
            for (view in views) {
                view.nextFocusUpId = targetOrSelf(up, view)
                view.nextFocusDownId = targetOrSelf(down, view)
            }
        }

        @JvmStatic fun applyItemFocus(
            recycler: RecyclerView,
            up: Int,
            down: Int,
        ) {
            for (i in 0 until recycler.childCount) {
                val child = recycler.getChildAt(i)
                if (up != View.NO_ID) child.nextFocusUpId = focusTarget(up)
                if (down != View.NO_ID) child.nextFocusDownId = focusTarget(down)
            }
        }

        @JvmStatic fun focusTarget(target: Int): Int = if (target == 0) View.NO_ID else target

        @JvmStatic fun targetOrSelf(
            target: Int,
            fallback: View,
        ): Int = if (target == 0 || target == View.NO_ID) fallback.id else target
    }
}
