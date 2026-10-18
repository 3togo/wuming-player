package com.wuming.android.tv.ui.controller

import android.animation.ValueAnimator
import android.view.View

open class VideoController(
    activity: Any?,
) {
    private var activeAnimator: ValueAnimator? = null

    open fun createTransitionAnimator(
        view: View,
        fromHeight: Int,
        toHeight: Int,
        duration: Int,
    ): ValueAnimator {
        activeAnimator?.cancel()
        activeAnimator?.removeAllUpdateListeners()
        val animator = ValueAnimator.ofInt(fromHeight, toHeight)
        animator.duration = duration.toLong()
        animator.addUpdateListener { animation ->
            val value = animation.animatedValue as Int
            val params = view.layoutParams
            params.height = value
            view.layoutParams = params
        }
        activeAnimator = animator
        return animator
    }

    fun cancelAnimator() {
        activeAnimator?.cancel()
        activeAnimator?.removeAllUpdateListeners()
        activeAnimator = null
    }
}
