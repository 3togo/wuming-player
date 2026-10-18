package com.wuming.android.tv.utils

open class SiteListConfig {
    companion object {
        private const val REF_WIDTH_DP = 960f

        private fun getScale(): Float = (ResUtil.getScreenWidth() / ResUtil.getDisplayMetrics().density) / REF_WIDTH_DP

        private fun scale(refValue: Float): Int = Math.round(refValue * getScale())

        @JvmStatic fun getButtonHeight(screenDiagonal: Double): Int = scale(26f)

        @JvmStatic fun getIconSize(screenDiagonal: Double): Int = scale(14f)

        @JvmStatic fun getDialogIconSize(screenDiagonal: Double): Int = scale(22f)

        @JvmStatic fun getRankingRowHeight(screenDiagonal: Double): Int = scale(44f)

        @JvmStatic fun getRankingPadding(screenDiagonal: Double): Int = scale(4f)
    }
}
