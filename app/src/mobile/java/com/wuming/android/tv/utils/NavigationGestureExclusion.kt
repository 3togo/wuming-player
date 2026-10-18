package com.wuming.android.tv.utils

import android.graphics.Rect
import android.os.Build
import android.view.View
import java.util.Collections

/** Applies system gesture exclusion to bottom navigation when supported. */
object NavigationGestureExclusion {
    private const val MIN_GESTURE_EXCLUSION_API = Build.VERSION_CODES.Q

    @JvmStatic fun apply(
        navigation: View?,
        enabled: Boolean,
    ) {
        if (!enabled || navigation == null || Build.VERSION.SDK_INT < MIN_GESTURE_EXCLUSION_API) return
        navigation.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view.width <= 0 || view.height <= 0) return@addOnLayoutChangeListener
            view.systemGestureExclusionRects = Collections.singletonList(Rect(0, 0, view.width, view.height))
        }
    }
}
