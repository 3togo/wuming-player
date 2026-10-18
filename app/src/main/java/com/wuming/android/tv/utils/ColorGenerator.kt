package com.wuming.android.tv.utils

import com.github.bassaer.library.MDColor

object ColorGenerator {
    private val PALETTE_400: List<Int> = listOf(
        MDColor.RED_400, MDColor.PINK_400, MDColor.PURPLE_400, MDColor.DEEP_PURPLE_400,
        MDColor.INDIGO_400, MDColor.BLUE_400, MDColor.LIGHT_BLUE_400, MDColor.CYAN_400,
        MDColor.TEAL_400, MDColor.GREEN_400, MDColor.LIGHT_GREEN_400, MDColor.LIME_400,
        MDColor.YELLOW_400, MDColor.AMBER_400, MDColor.ORANGE_400, MDColor.DEEP_ORANGE_400,
        MDColor.BROWN_400, MDColor.GREY_400, MDColor.BLUE_GREY_400
    )

    private val PALETTE_700: List<Int> = listOf(
        MDColor.RED_700, MDColor.PINK_700, MDColor.PURPLE_700, MDColor.DEEP_PURPLE_700,
        MDColor.INDIGO_700, MDColor.BLUE_700, MDColor.LIGHT_BLUE_700, MDColor.CYAN_700,
        MDColor.TEAL_700, MDColor.GREEN_700, MDColor.LIGHT_GREEN_700, MDColor.LIME_700,
        MDColor.YELLOW_700, MDColor.AMBER_700, MDColor.ORANGE_700, MDColor.DEEP_ORANGE_700,
        MDColor.BROWN_700, MDColor.GREY_700, MDColor.BLUE_GREY_700
    )

    @JvmStatic
    fun get400(key: String): Int = PALETTE_400[(key.hashCode() and Int.MAX_VALUE) % PALETTE_400.size]

    @JvmStatic
    fun get700(key: String): Int = PALETTE_700[(key.hashCode() and Int.MAX_VALUE) % PALETTE_700.size]
}
