package com.wuming.android.tv.utils

import kotlin.math.max

object GridSpecCalculator {
    @JvmStatic
    fun calculate(
        screenWidth: Int, reservedWidth: Int, columns: Int, extraSpace: Int,
        aspectRatio: Float, nameTextHeight: Int, gapRatio: Float, outerRatio: Float
    ): IntArray {
        val safeScreenWidth = max(1, screenWidth)
        val safeColumns = max(1, columns)
        val safeAspectRatio = if (aspectRatio > 0) aspectRatio else 1f
        val gap = max(0, (safeScreenWidth * gapRatio).toInt())
        val outer = max(0, (safeScreenWidth * outerRatio).toInt())
        val gridAreaWidth = safeScreenWidth - max(0, reservedWidth)
        val available = gridAreaWidth - outer - max(0, extraSpace)
        val width = max(1, (available - gap * (safeColumns - 1)) / safeColumns)
        val margin = gap / 2
        val imageHeight = max(1, (width / safeAspectRatio).toInt())
        val height = imageHeight + max(0, nameTextHeight)
        return intArrayOf(width, height, margin, imageHeight)
    }
}
