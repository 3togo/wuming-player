package com.wuming.android.tv.ui.dialog

import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import androidx.media3.ui.danmaku.DanmakuConfig
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.DialogDanmakuSettingBinding
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.setting.DanmakuSetting
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import java.util.Locale
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.IntConsumer
import java.util.function.IntFunction
import java.util.function.IntUnaryOperator
import java.util.function.LongConsumer

class DanmakuSettingPanel(
    private val binding: DialogDanmakuSettingBinding,
    private val player: PlayerManager?,
) {
    private var currentTab = 0

    fun bind() {
        bindAppearance()
        bindTiming()
        bindDensity()
        bindDisplay()
        bindTabs()
        showTab(0)
        binding.tabAppearance.requestFocus()
        binding.reset.setOnClickListener(this::onReset)
        binding.tabGroup.check(binding.tabAppearance.id)
    }

    private fun bindAppearance() {
        val appearance = binding.appearance
        setupSwitch(appearance.textBoldSwitch, DanmakuSetting.isTextBold(), DanmakuSetting::putTextBold)
        setupFloat(appearance.textSizeSlider, appearance.textSizeValue, DanmakuSetting.getTextScale(), "%.1f", DanmakuSetting::putTextScale)
        setupFloat(appearance.alphaSlider, appearance.alphaValue, DanmakuSetting.getTransparency(), "%.2f", DanmakuSetting::putTransparency)
        setupFloat(
            appearance.shadowAlphaSlider,
            appearance.shadowAlphaValue,
            DanmakuSetting.getShadowTransparency(),
            "%.2f",
            DanmakuSetting::putShadowTransparency,
        )
        setupFloat(
            appearance.strokeWidthSlider,
            appearance.strokeWidthValue,
            DanmakuSetting.getStrokeWidthMultiplier(),
            "%.2f",
            DanmakuSetting::putStrokeWidthMultiplier,
        )
        setupFloat(
            appearance.projectionOffsetXSlider,
            appearance.projectionOffsetXValue,
            DanmakuSetting.getProjectionOffsetX(),
            "%.2f",
            DanmakuSetting::putProjectionOffsetX,
        )
        setupFloat(
            appearance.projectionOffsetYSlider,
            appearance.projectionOffsetYValue,
            DanmakuSetting.getProjectionOffsetY(),
            "%.2f",
            DanmakuSetting::putProjectionOffsetY,
        )
        setupFloat(
            appearance.projectionAlphaSlider,
            appearance.projectionAlphaValue,
            DanmakuSetting.getProjectionTransparency(),
            "%.2f",
            DanmakuSetting::putProjectionTransparency,
        )
        setupChip(
            appearance.styleChipGroup,
            DanmakuSetting.getStyleMode(),
            this::styleChipForMode,
            this::styleModeForChip,
            this::onStyleModeChanged,
        )
        setupChip(
            appearance.colorChipGroup,
            DanmakuSetting.getColorMode(),
            this::colorChipForMode,
            this::colorModeForChip,
            this::onColorModeChanged,
        )
        updateStyleSubSettings(DanmakuSetting.getStyleMode())
        updateColorOverrideHint(DanmakuSetting.getColorMode())
    }

    private fun bindTiming() {
        val timing = binding.timing
        setupMs(timing.timeOffsetSlider, timing.timeOffsetValue, DanmakuSetting.getTimeOffsetMs(), DanmakuSetting::putTimeOffsetMs)
        setupMs(timing.durationSlider, timing.durationValue, DanmakuSetting.getDurationMs(), DanmakuSetting::putDurationMs)
        setupMs(
            timing.fixedDurationSlider,
            timing.fixedDurationValue,
            DanmakuSetting.getFixedDurationMs(),
            DanmakuSetting::putFixedDurationMs,
        )
    }

    private fun bindDensity() {
        val density = binding.density
        setupInt(density.maxOnScreenSlider, density.maxOnScreenValue, DanmakuSetting.getMaxOnScreen(), {
            it.toString()
        }, DanmakuSetting::putMaxOnScreen)
        setupFloat(
            density.scrollAreaSlider,
            density.scrollAreaValue,
            DanmakuSetting.getScrollAreaRatio(),
            "%.2f",
            DanmakuSetting::putScrollAreaRatio,
        )
        setupFloat(
            density.scrollGapSlider,
            density.scrollGapValue,
            DanmakuSetting.getScrollGapRatio(),
            "%.1f",
            DanmakuSetting::putScrollGapRatio,
        )
        setupFloat(
            density.lineSpacingSlider,
            density.lineSpacingValue,
            DanmakuSetting.getLineSpacing(),
            "%.1f",
            DanmakuSetting::putLineSpacing,
        )
        setupInt(
            density.maxScrollLinesSlider,
            density.maxScrollLinesValue,
            DanmakuSetting.getMaxScrollLines(),
            this::linesText,
            DanmakuSetting::putMaxScrollLines,
        )
        setupInt(
            density.maxTopLinesSlider,
            density.maxTopLinesValue,
            DanmakuSetting.getMaxTopLines(),
            this::linesText,
            DanmakuSetting::putMaxTopLines,
        )
        setupInt(
            density.maxBottomLinesSlider,
            density.maxBottomLinesValue,
            DanmakuSetting.getMaxBottomLines(),
            this::linesText,
            DanmakuSetting::putMaxBottomLines,
        )
        updateDependentControls()
    }

    private fun bindDisplay() {
        val display = binding.display
        setupSwitch(display.showScrollSwitch, DanmakuSetting.isShowScroll(), DanmakuSetting::putShowScroll, this::updateDependentControls)
        setupSwitch(display.showTopSwitch, DanmakuSetting.isShowTop(), DanmakuSetting::putShowTop, this::updateDependentControls)
        setupSwitch(display.showBottomSwitch, DanmakuSetting.isShowBottom(), DanmakuSetting::putShowBottom, this::updateDependentControls)
        setupSwitch(
            display.showReverseSwitch,
            DanmakuSetting.isShowReverse(),
            DanmakuSetting::putShowReverse,
            this::updateDependentControls,
        )
        setupSwitch(display.showPositionedSwitch, DanmakuSetting.isShowPositioned(), DanmakuSetting::putShowPositioned, null)
        setupSwitch(display.showSubtitleSwitch, DanmakuSetting.isShowSubtitle(), DanmakuSetting::putShowSubtitle, null)
        setupSwitch(display.showSpecialSwitch, DanmakuSetting.isShowSpecial(), DanmakuSetting::putShowSpecial, null)
    }

    private fun bindTabs() {
        val tabs = arrayOf(binding.tabAppearance, binding.tabTiming, binding.tabDensity, binding.tabDisplay)
        for (tab in tabs) checkOnFocus(tab)
        binding.tabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) for (i in tabs.indices) if (checkedId == tabs[i].id) showTab(i)
        }
    }

    private fun checkOnFocus(button: MaterialButton) {
        button.setOnFocusChangeListener { _, focused -> if (focused) binding.tabGroup.check(button.id) }
    }

    private fun onReset(view: View) {
        when (currentTab) {
            0 -> {
                DanmakuSetting.resetAppearance()
                bindAppearance()
                applyConfig()
            }

            1 -> {
                DanmakuSetting.resetTiming()
                bindTiming()
                applyConfig()
            }

            2 -> {
                DanmakuSetting.resetDensity()
                bindDensity()
                applyConfig()
            }

            3 -> {
                DanmakuSetting.resetDisplay()
                bindDisplay()
                updateDependentControls()
                applyConfig()
            }
        }
    }

    private fun showTab(index: Int) {
        val roots = arrayOf(binding.appearance.root, binding.timing.root, binding.density.root, binding.display.root)
        val tabs = arrayOf(binding.tabAppearance, binding.tabTiming, binding.tabDensity, binding.tabDisplay)
        for (i in roots.indices) roots[i].visibility = visibleIf(index == i)
        currentTab = index
        binding.reset.nextFocusDownId = tabs[currentTab].id
    }

    private fun updateStyleSubSettings(mode: Int) {
        val appearance = binding.appearance
        applyVisible(mode == DanmakuConfig.STYLE_SHADOW, appearance.shadowAlphaRow, appearance.shadowAlphaSlider)
        applyVisible(mode == DanmakuConfig.STYLE_STROKE, appearance.strokeWidthRow, appearance.strokeWidthSlider)
        applyVisible(
            mode == DanmakuConfig.STYLE_PROJECTION,
            appearance.projectionOffsetXRow,
            appearance.projectionOffsetXSlider,
            appearance.projectionOffsetYRow,
            appearance.projectionOffsetYSlider,
            appearance.projectionAlphaRow,
            appearance.projectionAlphaSlider,
        )
    }

    private fun applyVisible(
        visible: Boolean,
        vararg views: View,
    ) {
        val visibility = visibleIf(visible)
        for (view in views) {
            view.visibility =
                visibility
        }
    }

    private fun updateColorOverrideHint(mode: Int) {
        binding.appearance.colorOverrideHint.visibility =
            visibleIf(mode != DanmakuConfig.COLOR_MODE_DEFAULT)
    }

    private fun onStyleModeChanged(mode: Int) {
        DanmakuSetting.putStyleMode(mode)
        updateStyleSubSettings(mode)
    }

    private fun onColorModeChanged(mode: Int) {
        DanmakuSetting.putColorMode(mode)
        updateColorOverrideHint(mode)
    }

    private fun updateDependentControls() {
        val density = binding.density
        applyEnabled(density.maxScrollLinesRow, density.maxScrollLinesSlider, DanmakuSetting.isShowScroll())
        applyEnabled(density.maxTopLinesRow, density.maxTopLinesSlider, DanmakuSetting.isShowTop())
        applyEnabled(density.maxBottomLinesRow, density.maxBottomLinesSlider, DanmakuSetting.isShowBottom())
    }

    private fun applyEnabled(
        row: View,
        slider: Slider,
        enabled: Boolean,
    ) {
        row.alpha = if (enabled) 1f else 0.38f
        slider.isEnabled =
            enabled
    }

    private fun applyConfig() {
        player?.setDanmakuConfig(DanmakuSetting.getConfig())
    }

    private fun styleChipForMode(mode: Int): Int {
        val appearance = binding.appearance
        return when (mode) {
            DanmakuConfig.STYLE_NONE -> appearance.styleNone.id
            DanmakuConfig.STYLE_SHADOW -> appearance.styleShadow.id
            DanmakuConfig.STYLE_PROJECTION -> appearance.styleProjection.id
            else -> appearance.styleStroke.id
        }
    }

    private fun styleModeForChip(chipId: Int): Int {
        val appearance = binding.appearance
        return when (chipId) {
            appearance.styleNone.id -> DanmakuConfig.STYLE_NONE
            appearance.styleShadow.id -> DanmakuConfig.STYLE_SHADOW
            appearance.styleProjection.id -> DanmakuConfig.STYLE_PROJECTION
            else -> DanmakuConfig.STYLE_STROKE
        }
    }

    private fun colorChipForMode(mode: Int): Int {
        val appearance = binding.appearance
        return when (mode) {
            DanmakuConfig.COLOR_MODE_COLORFUL -> appearance.colorColorful.id
            DanmakuConfig.COLOR_MODE_GRADIENT -> appearance.colorGradient.id
            else -> appearance.colorDefault.id
        }
    }

    private fun colorModeForChip(chipId: Int): Int {
        val appearance = binding.appearance
        return when (chipId) {
            appearance.colorColorful.id -> DanmakuConfig.COLOR_MODE_COLORFUL
            appearance.colorGradient.id -> DanmakuConfig.COLOR_MODE_GRADIENT
            else -> DanmakuConfig.COLOR_MODE_DEFAULT
        }
    }

    private fun setupFloat(
        slider: Slider,
        label: TextView,
        value: Float,
        format: String,
        setter: (Float) -> Unit,
    ) {
        setupSlider(slider, label, value, { String.format(Locale.getDefault(), format, it) }, setter)
    }

    private fun setupInt(
        slider: Slider,
        label: TextView,
        value: Int,
        formatter: (Int) -> String,
        setter: (Int) -> Unit,
    ) {
        setupSlider(slider, label, value.toFloat(), { formatter(it.toInt()) }, { setter(it.toInt()) })
    }

    private fun setupMs(
        slider: Slider,
        label: TextView,
        valueMs: Long,
        setter: (Long) -> Unit,
    ) {
        setupSlider(slider, label, valueMs.toFloat(), { String.format(Locale.getDefault(), "%.1fs", it / 1000f) }, { setter(it.toLong()) })
    }

    private fun setupSlider(
        slider: Slider,
        label: TextView,
        initial: Float,
        formatter: (Float) -> String,
        setter: (Float) -> Unit,
    ) {
        val clamped = maxOf(slider.valueFrom, minOf(slider.valueTo, initial))
        slider.clearOnChangeListeners()
        slider.setLabelFormatter { formatter(it) }
        slider.value = clamped
        label.text = formatter(clamped)
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                setter(value)
                label.text = formatter(value)
                applyConfig()
            }
        }
    }

    private fun setupSwitch(
        button: CompoundButton,
        value: Boolean,
        setter: (Boolean) -> Unit,
        after: (() -> Unit)? = null,
    ) {
        button.setOnCheckedChangeListener(null)
        button.isChecked = value
        button.setOnCheckedChangeListener { _, checked ->
            setter(checked)
            after?.invoke()
            applyConfig()
        }
    }

    private fun setupChip(
        group: ChipGroup,
        initialMode: Int,
        chipForMode: (Int) -> Int,
        modeForChip: (Int) -> Int,
        onChange: (Int) -> Unit,
    ) {
        group.setOnCheckedStateChangeListener(null)
        group.check(chipForMode(initialMode))
        group.setOnCheckedStateChangeListener {
            _,
            checkedIds,
            ->
            if (checkedIds.isNotEmpty()) {
                onChange(modeForChip(checkedIds[0]))
                applyConfig()
            }
        }
    }

    private fun linesText(value: Int): String = if (value == 0) binding.root.context.getString(R.string.danmaku_auto) else value.toString()

    private fun visibleIf(condition: Boolean): Int = if (condition) View.VISIBLE else View.GONE
}
