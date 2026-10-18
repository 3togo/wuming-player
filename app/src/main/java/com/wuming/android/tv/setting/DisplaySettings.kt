package com.wuming.android.tv.setting

import com.github.catvod.utils.Prefers

open class DisplaySettings {
    companion object {
        @JvmStatic
        fun getWall(): Int = Prefers.getInt("wall", 1)

        @JvmStatic
        fun putWall(wall: Int) {
            Prefers.put("wall", wall)
        }

        @JvmStatic
        fun getWallType(): Int = Prefers.getInt("wall_type", 0)

        @JvmStatic
        fun putWallType(type: Int) {
            Prefers.put("wall_type", type)
        }

        @JvmStatic
        fun getTheme(): Int = Prefers.getInt("theme", 0).coerceIn(0, 2)

        @JvmStatic
        fun putTheme(theme: Int) {
            Prefers.put("theme", theme.coerceIn(0, 2))
        }

        @JvmStatic
        fun getColorTheme(): Int = Prefers.getInt("color_theme", 0).coerceIn(0, 1)

        @JvmStatic
        fun putColorTheme(theme: Int) {
            Prefers.put("color_theme", theme.coerceIn(0, 1))
        }

        @JvmStatic
        fun getSize(): Int = Prefers.getInt("size", 2)

        @JvmStatic
        fun putSize(size: Int) {
            Prefers.put("size", size)
        }

        @JvmStatic
        fun getScale(): Int = Prefers.getInt("scale")

        @JvmStatic
        fun putScale(scale: Int) {
            Prefers.put("scale", scale)
        }

        // ---- UI text auto-scaling dials (width-dependent, rotation-stable) ----

        /** Upper bound for the auto text-scale factor on wide screens. Default 1.25. */
        @JvmStatic
        fun getUiTextScaleCap(): Float = Prefers.getFloat("ui_text_scale_cap", 1.25f)

        @JvmStatic
        fun putUiTextScaleCap(cap: Float) {
            Prefers.put("ui_text_scale_cap", cap)
        }

        /** Reference width (dp) mapped to a 1.0x scale. Default 360 (phone baseline). */
        @JvmStatic
        fun getUiTextScaleBaseline(): Float = Prefers.getFloat("ui_text_scale_baseline", 360f)

        @JvmStatic
        fun putUiTextScaleBaseline(baseline: Float) {
            Prefers.put("ui_text_scale_baseline", baseline)
        }

        @JvmStatic
        fun getLiveScale(): Int = Prefers.getInt("scale_live", getScale())

        @JvmStatic
        fun putLiveScale(scale: Int) {
            Prefers.put("scale_live", scale)
        }

        @JvmStatic
        fun isInvert(): Boolean = Prefers.getBoolean("invert")

        @JvmStatic
        fun putInvert(invert: Boolean) {
            Prefers.put("invert", invert)
        }

        @JvmStatic
        fun isAcross(): Boolean = Prefers.getBoolean("across", true)

        @JvmStatic
        fun putAcross(across: Boolean) {
            Prefers.put("across", across)
        }

        @JvmStatic
        fun isChange(): Boolean = Prefers.getBoolean("change", true)

        @JvmStatic
        fun putChange(change: Boolean) {
            Prefers.put("change", change)
        }

        @JvmStatic
        fun isZhuyin(): Boolean = Prefers.getBoolean("zhuyin")

        @JvmStatic
        fun putZhuyin(zhuyin: Boolean) {
            Prefers.put("zhuyin", zhuyin)
        }

        @JvmStatic
        fun isGridSearchMode(): Boolean = Prefers.getBoolean("grid_search_mode", true)

        @JvmStatic
        fun putGridSearchMode(gridSearchMode: Boolean) {
            Prefers.put("grid_search_mode", gridSearchMode)
        }

        @JvmStatic
        fun isStrictSearch(): Boolean = Prefers.getBoolean("strict_search", false)

        @JvmStatic
        fun putStrictSearch(strictSearch: Boolean) {
            Prefers.put("strict_search", strictSearch)
        }

        @JvmStatic
        fun getSearchView(): Int = Prefers.getInt("search_view", 0)

        @JvmStatic
        fun putSearchView(searchView: Int) {
            Prefers.put("search_view", searchView)
        }

        @JvmStatic
        fun getSiteListLayout(): Int = Prefers.getInt("site_list_layout", 0)

        @JvmStatic
        fun putSiteListLayout(layout: Int) {
            Prefers.put("site_list_layout", layout)
        }

        /**
         * Search site limit: maximum number of sites to search concurrently.
         * 0 = search all searchable sites (no limit, default)
         */
        @JvmStatic
        fun getSearchLimit(): Int = Prefers.getInt("search_limit", 0)

        @JvmStatic
        fun putSearchLimit(limit: Int) {
            Prefers.put("search_limit", limit)
        }

        @JvmStatic
        fun getSiteMode(): Int = Prefers.getInt("site_mode")

        @JvmStatic
        fun putSiteMode(mode: Int) {
            Prefers.put("site_mode", mode)
        }

        /**
         * DLNA casting mode:
         * 0 = relay through phone (CDN -> Phone -> TV)
         * 1 = direct CDN URL (CDN -> TV, no auth headers forwarded)
         * 2 = auto (default) - uses direct mode when no auth headers are needed,
         *     falls back to relay when custom headers (Cookie, Authorization, etc.)
         *     are required for the CDN to serve the stream.
         */
        @JvmStatic
        fun getDlnaCastMode(): Int = Prefers.getInt("dlna_cast_mode", 2)

        @JvmStatic
        fun putDlnaCastMode(mode: Int) {
            Prefers.put("dlna_cast_mode", mode)
        }
    }
}
