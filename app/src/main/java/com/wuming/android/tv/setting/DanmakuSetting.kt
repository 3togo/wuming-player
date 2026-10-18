package com.wuming.android.tv.setting

import androidx.media3.ui.danmaku.DanmakuConfig
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.BeanUtils
import com.github.catvod.utils.Prefers

open class DanmakuSetting {
    companion object {
        @JvmStatic
        fun isLoad(): Boolean = Prefers.getBoolean(SettingsSchema.DANMAKU_LOAD)

        @JvmStatic
        fun putLoad(danmakuLoad: Boolean) {
            Prefers.put(SettingsSchema.DANMAKU_LOAD, danmakuLoad)
        }

        @JvmStatic
        fun isAuto(): Boolean = Prefers.getBoolean(SettingsSchema.DANMAKU_AUTO)

        @JvmStatic
        fun putAuto(auto: Boolean) {
            Prefers.put(SettingsSchema.DANMAKU_AUTO, auto)
        }

        @JvmStatic
        fun isSpiderFirst(): Boolean = Prefers.getBoolean(SettingsSchema.DANMAKU_SPIDER_FIRST)

        @JvmStatic
        fun putSpiderFirst(spiderFirst: Boolean) {
            Prefers.put(SettingsSchema.DANMAKU_SPIDER_FIRST, spiderFirst)
        }

        @JvmStatic
        fun getApiUrl(): String? = Prefers.getString(SettingsSchema.DANMAKU_API_URL, "")

        @JvmStatic
        fun putApiUrl(url: String?) {
            Prefers.put(SettingsSchema.DANMAKU_API_URL, url)
        }

        @JvmStatic
        fun isShow(): Boolean = Prefers.getBoolean(SettingsSchema.DANMAKU_SHOW)

        @JvmStatic
        fun putShow(danmakuShow: Boolean) {
            Prefers.put(SettingsSchema.DANMAKU_SHOW, danmakuShow)
        }

        @JvmStatic
        fun getTextScale(): Float = Prefers.getFloat(SettingsSchema.DANMAKU_TEXT_SCALE, 1f)

        @JvmStatic
        fun putTextScale(value: Float) {
            Prefers.put(SettingsSchema.DANMAKU_TEXT_SCALE, value)
        }

        @JvmStatic
        fun getTransparency(): Float = Prefers.getFloat(SettingsSchema.DANMAKU_TRANSPARENCY, 0f)

        @JvmStatic
        fun putTransparency(value: Float) {
            Prefers.put(SettingsSchema.DANMAKU_TRANSPARENCY, value)
        }

        @JvmStatic
        fun isTextBold(): Boolean = Prefers.getBoolean(SettingsSchema.DANMAKU_TEXT_BOLD)

        @JvmStatic
        fun putTextBold(value: Boolean) {
            Prefers.put(SettingsSchema.DANMAKU_TEXT_BOLD, value)
        }

        @JvmStatic
        fun getStyleMode(): Int = Prefers.getInt(SettingsSchema.DANMAKU_STYLE_MODE, DanmakuConfig.STYLE_STROKE)

        @JvmStatic
        fun putStyleMode(value: Int) {
            Prefers.put(SettingsSchema.DANMAKU_STYLE_MODE, value)
        }

        @JvmStatic
        fun getColorMode(): Int = Prefers.getInt(SettingsSchema.DANMAKU_COLOR_MODE, DanmakuConfig.COLOR_MODE_DEFAULT)

        @JvmStatic
        fun putColorMode(value: Int) {
            Prefers.put(SettingsSchema.DANMAKU_COLOR_MODE, value)
        }

        @JvmStatic
        fun getShadowTransparency(): Float = Prefers.getFloat(SettingsSchema.DANMAKU_SHADOW_TRANSPARENCY, 0.1f)

        @JvmStatic
        fun putShadowTransparency(value: Float) {
            Prefers.put(SettingsSchema.DANMAKU_SHADOW_TRANSPARENCY, value)
        }

        @JvmStatic
        fun getStrokeWidthMultiplier(): Float = Prefers.getFloat(SettingsSchema.DANMAKU_STROKE_WIDTH_MULTIPLIER, 0.12f)

        @JvmStatic
        fun putStrokeWidthMultiplier(value: Float) {
            Prefers.put(SettingsSchema.DANMAKU_STROKE_WIDTH_MULTIPLIER, value)
        }

        @JvmStatic
        fun getProjectionOffsetX(): Float = Prefers.getFloat(SettingsSchema.DANMAKU_PROJECTION_OFFSET_X, 0.08f)

        @JvmStatic
        fun putProjectionOffsetX(value: Float) {
            Prefers.put(SettingsSchema.DANMAKU_PROJECTION_OFFSET_X, value)
        }

        @JvmStatic
        fun getProjectionOffsetY(): Float = Prefers.getFloat(SettingsSchema.DANMAKU_PROJECTION_OFFSET_Y, 0.08f)

        @JvmStatic
        fun putProjectionOffsetY(value: Float) {
            Prefers.put(SettingsSchema.DANMAKU_PROJECTION_OFFSET_Y, value)
        }

        @JvmStatic
        fun getProjectionTransparency(): Float = Prefers.getFloat(SettingsSchema.DANMAKU_PROJECTION_TRANSPARENCY, 0.2f)

        @JvmStatic
        fun putProjectionTransparency(value: Float) {
            Prefers.put(SettingsSchema.DANMAKU_PROJECTION_TRANSPARENCY, value)
        }

        @JvmStatic
        fun getDurationMs(): Long = Prefers.getLong(SettingsSchema.DANMAKU_DURATION, 8000L)

        @JvmStatic
        fun putDurationMs(value: Long) {
            Prefers.put(SettingsSchema.DANMAKU_DURATION, value)
        }

        @JvmStatic
        fun getFixedDurationMs(): Long = Prefers.getLong(SettingsSchema.DANMAKU_FIXED_DURATION, 5000L)

        @JvmStatic
        fun putFixedDurationMs(value: Long) {
            Prefers.put(SettingsSchema.DANMAKU_FIXED_DURATION, value)
        }

        @JvmStatic
        fun getTimeOffsetMs(): Long = Prefers.getLong(SettingsSchema.DANMAKU_TIME_OFFSET, 0L)

        @JvmStatic
        fun putTimeOffsetMs(value: Long) {
            Prefers.put(SettingsSchema.DANMAKU_TIME_OFFSET, value)
        }

        @JvmStatic
        fun getMaxOnScreen(): Int = Prefers.getInt(SettingsSchema.DANMAKU_MAX_ON_SCREEN, 150)

        @JvmStatic
        fun putMaxOnScreen(value: Int) {
            Prefers.put(SettingsSchema.DANMAKU_MAX_ON_SCREEN, value)
        }

        @JvmStatic
        fun getScrollAreaRatio(): Float = Prefers.getFloat(SettingsSchema.DANMAKU_SCROLL_AREA_RATIO, 0.5f)

        @JvmStatic
        fun putScrollAreaRatio(value: Float) {
            Prefers.put(SettingsSchema.DANMAKU_SCROLL_AREA_RATIO, value)
        }

        @JvmStatic
        fun getMaxScrollLines(): Int = Prefers.getInt(SettingsSchema.DANMAKU_MAX_SCROLL_LINES, 0)

        @JvmStatic
        fun putMaxScrollLines(value: Int) {
            Prefers.put(SettingsSchema.DANMAKU_MAX_SCROLL_LINES, value)
        }

        @JvmStatic
        fun getMaxTopLines(): Int = Prefers.getInt(SettingsSchema.DANMAKU_MAX_TOP_LINES, 0)

        @JvmStatic
        fun putMaxTopLines(value: Int) {
            Prefers.put(SettingsSchema.DANMAKU_MAX_TOP_LINES, value)
        }

        @JvmStatic
        fun getMaxBottomLines(): Int = Prefers.getInt(SettingsSchema.DANMAKU_MAX_BOTTOM_LINES, 0)

        @JvmStatic
        fun putMaxBottomLines(value: Int) {
            Prefers.put(SettingsSchema.DANMAKU_MAX_BOTTOM_LINES, value)
        }

        @JvmStatic
        fun getLineSpacing(): Float = Prefers.getFloat(SettingsSchema.DANMAKU_LINE_SPACING, 1.4f)

        @JvmStatic
        fun putLineSpacing(value: Float) {
            Prefers.put(SettingsSchema.DANMAKU_LINE_SPACING, value)
        }

        @JvmStatic
        fun getScrollGapRatio(): Float = Prefers.getFloat(SettingsSchema.DANMAKU_SCROLL_GAP_RATIO, 0f)

        @JvmStatic
        fun putScrollGapRatio(value: Float) {
            Prefers.put(SettingsSchema.DANMAKU_SCROLL_GAP_RATIO, value)
        }

        @JvmStatic
        fun isShowScroll(): Boolean = Prefers.getBoolean(SettingsSchema.DANMAKU_SHOW_SCROLL, true)

        @JvmStatic
        fun putShowScroll(value: Boolean) {
            Prefers.put(SettingsSchema.DANMAKU_SHOW_SCROLL, value)
        }

        @JvmStatic
        fun isShowTop(): Boolean = Prefers.getBoolean(SettingsSchema.DANMAKU_SHOW_TOP, true)

        @JvmStatic
        fun putShowTop(value: Boolean) {
            Prefers.put(SettingsSchema.DANMAKU_SHOW_TOP, value)
        }

        @JvmStatic
        fun isShowBottom(): Boolean = Prefers.getBoolean(SettingsSchema.DANMAKU_SHOW_BOTTOM, true)

        @JvmStatic
        fun putShowBottom(value: Boolean) {
            Prefers.put(SettingsSchema.DANMAKU_SHOW_BOTTOM, value)
        }

        @JvmStatic
        fun isShowReverse(): Boolean = Prefers.getBoolean(SettingsSchema.DANMAKU_SHOW_REVERSE, true)

        @JvmStatic
        fun putShowReverse(value: Boolean) {
            Prefers.put(SettingsSchema.DANMAKU_SHOW_REVERSE, value)
        }

        @JvmStatic
        fun isShowPositioned(): Boolean = Prefers.getBoolean(SettingsSchema.DANMAKU_SHOW_POSITIONED, true)

        @JvmStatic
        fun putShowPositioned(value: Boolean) {
            Prefers.put(SettingsSchema.DANMAKU_SHOW_POSITIONED, value)
        }

        @JvmStatic
        fun isShowSubtitle(): Boolean = Prefers.getBoolean(SettingsSchema.DANMAKU_SHOW_SUBTITLE, true)

        @JvmStatic
        fun putShowSubtitle(value: Boolean) {
            Prefers.put(SettingsSchema.DANMAKU_SHOW_SUBTITLE, value)
        }

        @JvmStatic
        fun isShowSpecial(): Boolean = Prefers.getBoolean(SettingsSchema.DANMAKU_SHOW_SPECIAL, true)

        @JvmStatic
        fun putShowSpecial(value: Boolean) {
            Prefers.put(SettingsSchema.DANMAKU_SHOW_SPECIAL, value)
        }

        @JvmStatic
        fun getEffectiveApiUrl(): String? {
            val userUrl = getApiUrl()
            if (BeanUtils.isNotEmpty(userUrl)) return userUrl
            return VodConfig.get().getConfig().danmaku
        }

        @JvmStatic
        fun resetAppearance() {
            val config = DanmakuConfig.DEFAULT
            putTextScale(config.textScale)
            putTransparency(config.transparency)
            putTextBold(config.textBold)
            putStyleMode(config.styleMode)
            putShadowTransparency(config.shadowTransparency)
            putStrokeWidthMultiplier(config.strokeWidthMultiplier)
            putProjectionOffsetX(config.projectionOffsetXMultiplier)
            putProjectionOffsetY(config.projectionOffsetYMultiplier)
            putProjectionTransparency(config.projectionTransparency)
            putColorMode(config.colorMode)
        }

        @JvmStatic
        fun resetTiming() {
            val config = DanmakuConfig.DEFAULT
            putDurationMs(config.durationMs)
            putFixedDurationMs(config.fixedDurationMs)
            putTimeOffsetMs(config.timeOffsetMs)
        }

        @JvmStatic
        fun resetDensity() {
            val config = DanmakuConfig.DEFAULT
            putMaxOnScreen(config.maxOnScreen)
            putScrollAreaRatio(config.scrollAreaRatio)
            putScrollGapRatio(config.scrollGapRatio)
            putLineSpacing(config.lineSpacing)
            putMaxScrollLines(config.maxScrollLines)
            putMaxTopLines(config.maxTopLines)
            putMaxBottomLines(config.maxBottomLines)
        }

        @JvmStatic
        fun resetDisplay() {
            val config = DanmakuConfig.DEFAULT
            putShowScroll(config.showScroll)
            putShowTop(config.showTop)
            putShowBottom(config.showBottom)
            putShowReverse(config.showReverse)
            putShowPositioned(config.showPositioned)
            putShowSubtitle(config.showSubtitle)
            putShowSpecial(config.showSpecial)
        }

        @JvmStatic
        fun getConfig(): DanmakuConfig =
            DanmakuConfig
                .Builder()
                .setTextScale(getTextScale())
                .setTransparency(getTransparency())
                .setTextBold(isTextBold())
                .setStyleMode(getStyleMode())
                .setShadowTransparency(getShadowTransparency())
                .setStrokeWidthMultiplier(getStrokeWidthMultiplier())
                .setProjectionOffsetXMultiplier(getProjectionOffsetX())
                .setProjectionOffsetYMultiplier(getProjectionOffsetY())
                .setProjectionTransparency(getProjectionTransparency())
                .setColorMode(getColorMode())
                .setDurationMs(getDurationMs())
                .setFixedDurationMs(getFixedDurationMs())
                .setTimeOffsetMs(getTimeOffsetMs())
                .setMaxOnScreen(getMaxOnScreen())
                .setScrollAreaRatio(getScrollAreaRatio())
                .setScrollGapRatio(getScrollGapRatio())
                .setLineSpacing(getLineSpacing())
                .setMaxScrollLines(getMaxScrollLines())
                .setMaxTopLines(getMaxTopLines())
                .setMaxBottomLines(getMaxBottomLines())
                .setShowScroll(isShowScroll())
                .setShowTop(isShowTop())
                .setShowBottom(isShowBottom())
                .setShowReverse(isShowReverse())
                .setShowPositioned(isShowPositioned())
                .setShowSubtitle(isShowSubtitle())
                .setShowSpecial(isShowSpecial())
                .build()
    }
}
