package com.wuming.android.tv.ui.custom

import android.view.View

/** Shared focus treatment for RecyclerView poster cards. */
object PosterFocusEffect {
    private const val FOCUSED_SCALE = 1.08f
    private const val NORMAL_SCALE = 1f
    private const val FOCUSED_ELEVATION = 8f
    private const val DURATION_MS = 200L

    @JvmStatic
    fun attach(view: View) {
        view.setOnFocusChangeListener { card, focused ->
            card.animate().cancel()
            card.animate()
                .scaleX(if (focused) FOCUSED_SCALE else NORMAL_SCALE)
                .scaleY(if (focused) FOCUSED_SCALE else NORMAL_SCALE)
                .setDuration(DURATION_MS)
                .start()
            card.translationZ = if (focused) FOCUSED_ELEVATION else 0f
            card.isSelected = focused
        }
    }

    @JvmStatic
    fun reset(view: View) {
        view.animate().cancel()
        view.scaleX = NORMAL_SCALE
        view.scaleY = NORMAL_SCALE
        view.translationZ = 0f
        view.isSelected = false
    }
}
