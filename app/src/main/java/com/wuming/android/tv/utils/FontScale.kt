package com.wuming.android.tv.utils

import com.wuming.android.tv.App
import com.wuming.tv.core.design.TextRole

/** Compatibility entry points: geometry and column count never determine UI text size. */
object FontScale {
    private fun size(role: TextRole): Int = Math.round(TextScale.pixels(App.get(), role))

    @JvmStatic fun siteText(): Int = size(TextRole.LABEL)
    @JvmStatic fun siteDialogText(@Suppress("UNUSED_PARAMETER") spanCount: Int): Int = size(TextRole.LABEL)
    @JvmStatic fun rankText(): Int = size(TextRole.LABEL)
    @JvmStatic fun rankName(): Int = size(TextRole.LABEL)
    @JvmStatic fun rankScore(): Int = size(TextRole.LABEL)
    @JvmStatic fun rankStats(): Int = size(TextRole.CAPTION)
    @JvmStatic fun vodName(): Int = size(TextRole.LABEL)
    @JvmStatic fun vodBadge(): Int = size(TextRole.LABEL)
    @JvmStatic fun vodNameTv(): Int = size(TextRole.LABEL)
    @JvmStatic fun vodBadgeTv(): Int = size(TextRole.LABEL)
    @JvmStatic fun dialogText(@Suppress("UNUSED_PARAMETER") ratio: Float): Int = size(TextRole.BODY)
    @JvmStatic fun dialogText14(): Int = size(TextRole.LABEL)
    @JvmStatic fun dialogText16(): Int = size(TextRole.BODY)
}
