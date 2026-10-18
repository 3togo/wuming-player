package com.wuming.android.tv

import com.wuming.android.tv.bean.Style
import com.wuming.android.tv.setting.DisplaySettings
import com.wuming.android.tv.utils.FontScale
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.ResUtil
import kotlin.math.roundToInt

class LeanbackProduct : ProductCore() {

    init {
        ProductCore.setInstance(this)
    }

    override fun deviceTypeImpl(): Int = 0
    override fun nameTextSizeImpl(): Int = FontScale.vodNameTv()
    override fun badgeTextSizeImpl(): Int = FontScale.vodBadgeTv()

    companion object {
        /*
         * REGRESSION GUARDRAIL:
         * Leanback search/collection uses the shared five-column contract. General
         * grids may honor the user's explicit size preference, but provider Style
         * must never alter the count; every poster is rendered at the shared 3:4
         * ratio. See the regression-prevention playbook.
         */
        @JvmField
        val SEARCH_COLUMN: Int = LayoutConfig.SEARCH_GRID_SPAN_COUNT_LEANBACK

        @JvmStatic
        fun getColumn(): Int = kotlin.math.abs(DisplaySettings.getSize() - 7)

        @JvmStatic
        fun calculateItemSize(): IntArray {
            val column = getColumn()
            val space = ResUtil.getDimensionPixelSize(R.dimen.vs_48) + ResUtil.getDimensionPixelSize(R.dimen.vs_16) * (column - 1)
            val width = (ResUtil.getScreenWidth() - space) / column
            val height = Math.round(width / LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO)
            return intArrayOf(width, height)
        }

        @JvmStatic
        fun getColumn(style: Style): Int = getColumn()

        @JvmStatic
        fun getSpec(style: Style): IntArray {
            val column = getColumn(style)
            val itemGap = ResUtil.getDimensionPixelSize(R.dimen.vs_16)
            var space = ResUtil.getDimensionPixelSize(R.dimen.vs_48) + itemGap * (column - 1)
            if (style.isOval()) space += itemGap * column
            return getSpec(space, column, style)
        }

        @JvmStatic
        fun getSearchSpec(style: Style): IntArray {
            val column = SEARCH_COLUMN
            // Site list is horizontal at top, so grid uses full screen width
            val itemGap = ResUtil.getDimensionPixelSize(R.dimen.vs_16)
            var space = ResUtil.getDimensionPixelSize(R.dimen.vs_48) + itemGap * (column - 1)
            if (style.isOval()) space += itemGap * column
            val base = ResUtil.getScreenWidth() - space
            val width = base / column
            return posterSpec(width)
        }

        @JvmStatic
        fun getSearchSiteWidth(): Int = ResUtil.getScreenWidth() / (SEARCH_COLUMN + 1)

        @JvmStatic
        fun getSpec(space: Int, column: Int, style: Style): IntArray {
            val base = ResUtil.getScreenWidth() - space
            val width = base / column
            return posterSpec(width)
        }

        @JvmStatic
        fun getEms(): Int = kotlin.math.min(
            (ResUtil.getScreenWidth().toFloat() / ResUtil.getDimensionPixelSize(R.dimen.font_headline)).roundToInt(),
            35
        )

        private fun posterSpec(width: Int): IntArray {
            // REGRESSION GUARDRAIL: Leanback and mobile deliberately share this
            // 3:4 poster contract. Provider metadata must not select another ratio.
            val imageHeight = (width / LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO).roundToInt()
            val nameHeight = (getNameTextSize() * 1.3f).roundToInt() +
                ResUtil.getDimensionPixelSize(R.dimen.vs_12)
            return intArrayOf(width, imageHeight + nameHeight, 0, imageHeight)
        }
    }
}
