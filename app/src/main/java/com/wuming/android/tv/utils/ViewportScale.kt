package com.wuming.android.tv.utils

import com.wuming.android.tv.App

/** Uniform dimensions follow the limiting viewport axis; mobile text uses stable width. */
object ViewportScale {
    const val MOBILE_SHORT_EDGE_DESIGN_DP = 360f
    const val MOBILE_PORTRAIT_DESIGN_WIDTH_DP = 360f
    const val MOBILE_LANDSCAPE_DESIGN_WIDTH_DP = 600f
    const val TV_DESIGN_WIDTH_DP = 960f
    const val TV_DESIGN_HEIGHT_DP = 540f
    const val TV_REFERENCE_WIDTH_PX = 1920f
    const val TV_REFERENCE_HEIGHT_PX = 1080f

    @JvmStatic fun isWidthConstrained(
        width: Int,
        height: Int,
        designWidth: Float,
        designHeight: Float,
    ): Boolean = width * designHeight <= height * designWidth

    @JvmStatic fun mobileDesignWidth(landscape: Boolean): Float =
        if (landscape) {
            MOBILE_LANDSCAPE_DESIGN_WIDTH_DP
        } else {
            MOBILE_PORTRAIT_DESIGN_WIDTH_DP
        }

    @JvmStatic fun baseWidthDp(): Float {
        val sw =
            App
                .get()
                .resources.configuration.smallestScreenWidthDp
                .toFloat()
        return if (sw > 0) sw else MOBILE_PORTRAIT_DESIGN_WIDTH_DP
    }

    @JvmStatic fun useWidthForTv(
        width: Int,
        height: Int,
    ): Boolean = isWidthConstrained(width, height, TV_DESIGN_WIDTH_DP, TV_DESIGN_HEIGHT_DP)

    @JvmStatic fun uniformScale(
        width: Int,
        height: Int,
        referenceWidth: Float,
        referenceHeight: Float,
    ): Float {
        if (width <= 0 || height <= 0 || referenceWidth <= 0 || referenceHeight <= 0) return 1f
        return Math.min(width / referenceWidth, height / referenceHeight)
    }

    @JvmStatic fun scaleTvPx(referencePx: Float): Int = scaleTvPx(referencePx, ResUtil.getScreenWidth(), ResUtil.getScreenHeight())

    @JvmStatic fun scaleTvPx(
        referencePx: Float,
        width: Int,
        height: Int,
    ): Int =
        Math.max(
            1,
            Math.round(referencePx * uniformScale(width, height, TV_REFERENCE_WIDTH_PX, TV_REFERENCE_HEIGHT_PX)),
        )

    /**
     * Convert a dimension authored in the 960x540 dp TV design space to screen pixels.
     *
     * [scaleTvPx] maps from the 1920x1080 pixel space instead, so passing it a dp value
     * shrinks it by the display density -- on a 1080p TV at 320dpi that is exactly half.
     */
    @JvmStatic fun scaleTvDp(designDp: Float): Int = scaleTvDp(designDp, ResUtil.getScreenWidth(), ResUtil.getScreenHeight())

    @JvmStatic fun scaleTvDp(
        designDp: Float,
        width: Int,
        height: Int,
    ): Int =
        Math.max(
            1,
            Math.round(designDp * uniformScale(width, height, TV_DESIGN_WIDTH_DP, TV_DESIGN_HEIGHT_DP)),
        )
}
