package com.wuming.tv.desktop.ui

import com.wuming.tv.core.design.Appearance
import com.wuming.tv.core.design.ColorTheme
import com.wuming.tv.core.design.DesignTokens
import com.wuming.tv.core.design.ThemePolicy
import com.wuming.tv.core.design.TextRole
import com.wuming.tv.core.design.TypographyPolicy
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.plaf.ComponentUI
import javax.swing.plaf.basic.BasicArrowButton
import javax.swing.plaf.basic.BasicScrollBarUI
import javax.swing.UIManager

/** Swing adapter for the shared semantic palette and component metrics. */
object DesktopTvTheme {
    val uiScale: Float by lazy {
        if (java.awt.GraphicsEnvironment.isHeadless()) 1f else runCatching {
            val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            minOf(bounds.width / 1920f, bounds.height / 1080f).coerceIn(1f, 1.5f)
        }.getOrDefault(1f)
    }

    fun px(value: Int): Int = (value * uiScale).toInt().coerceAtLeast(value)

    fun textSize(role: TextRole): Int = px(TypographyPolicy.logicalSize(role).toInt())

    /** Scale only explicitly assigned fonts and dimensions; UI defaults are already scaled in [install]. */
    fun scaleExplicitMetrics(root: java.awt.Component) {
        if (uiScale <= 1f) return
        if (root.font != null && root.font !is javax.swing.plaf.UIResource) {
            root.font = root.font.deriveFont(root.font.size2D * uiScale)
        }
        if (root.isPreferredSizeSet) root.preferredSize = root.preferredSize.scaled()
        if (root.isMinimumSizeSet) root.minimumSize = root.minimumSize.scaled()
        if (root.isMaximumSizeSet) root.maximumSize = root.maximumSize.scaled()
        ensureTextHeight(root)
        if (root is java.awt.Container) root.components.forEach(::scaleExplicitMetrics)
    }

    private fun ensureTextHeight(component: java.awt.Component) {
        val extra = when (component) {
            is javax.swing.JComboBox<*> -> 10
            is javax.swing.AbstractButton -> 8
            is javax.swing.text.JTextComponent -> 8
            is javax.swing.JSpinner -> 8
            else -> return
        }
        val insets = component.insets
        val required = component.getFontMetrics(component.font).height + insets.top + insets.bottom + px(extra)
        if (component.isPreferredSizeSet && component.preferredSize.height < required) {
            component.preferredSize = Dimension(component.preferredSize.width, required)
        }
        if (component.isMinimumSizeSet && component.minimumSize.height < required) {
            component.minimumSize = Dimension(component.minimumSize.width, required)
        }
        if (component.isMaximumSizeSet && component.maximumSize.height in 1 until required) {
            component.maximumSize = Dimension(component.maximumSize.width, required)
        }
    }

    private fun Dimension.scaled(): Dimension = Dimension(
        width.scaledMetric(),
        height.scaledMetric(),
    )

    private fun Int.scaledMetric(): Int = when {
        this <= 0 || this == Int.MAX_VALUE -> this
        else -> (this * uiScale).toInt()
    }

    private var palette = ThemePolicy.palette(ColorTheme.CLASSIC, Appearance.DARK, true)
    val BACKGROUND: Color get() = SemanticColor(0, palette.background)
    val BACKGROUND_RAISED: Color get() = SemanticColor(1, palette.backgroundRaised)
    val SURFACE: Color get() = SemanticColor(2, palette.surface)
    val SURFACE_RAISED: Color get() = SemanticColor(3, palette.surfaceRaised)
    val TILE: Color get() = SemanticColor(4, palette.tile)
    val TILE_SELECTED: Color get() = SemanticColor(5, palette.tileSelected)
    val CHIP: Color get() = SemanticColor(6, palette.chip)
    val CHIP_SELECTED: Color get() = SemanticColor(7, palette.chipSelected)
    val TEXT: Color get() = SemanticColor(8, palette.text)
    val MUTED_TEXT: Color get() = SemanticColor(9, palette.mutedText)
    val SUBTLE_TEXT: Color get() = SemanticColor(10, palette.subtleText)
    val ACCENT: Color get() = SemanticColor(11, palette.accent)
    val ACCENT_STRONG: Color get() = SemanticColor(12, palette.accentStrong)
    val ACCENT_SOFT: Color get() = SemanticColor(13, palette.accentSoft)
    val POSITIVE: Color get() = SemanticColor(14, palette.positive)
    val DIVIDER: Color get() = SemanticColor(15, palette.divider)
    val SELECTION: Color get() = SemanticColor(16, palette.selection)
    val ON_ACCENT: Color get() = SemanticColor(17, palette.onAccent)

    fun install(theme: ColorTheme = ColorTheme.CLASSIC, appearance: Appearance = Appearance.SYSTEM,
                systemDark: Boolean = false) {
        palette = ThemePolicy.palette(theme, appearance, systemDark)
        // A deterministic delegate set is essential: GTK/Synth paints some widgets from native
        // style data and ignores UIManager colors (notably sliders, spinners and dialogs).
        javax.swing.plaf.metal.MetalLookAndFeel.setCurrentTheme(DesktopMetalTheme())
        UIManager.setLookAndFeel(javax.swing.plaf.metal.MetalLookAndFeel())
        mapOf(
            "Panel.background" to BACKGROUND,
            "RootPane.background" to BACKGROUND,
            "Label.foreground" to TEXT,
            "Button.background" to SURFACE,
            "Button.foreground" to TEXT,
            "Button.disabledText" to MUTED_TEXT,
            "ToggleButton.background" to TILE,
            "ToggleButton.foreground" to TEXT,
            "ToggleButton.select" to TILE_SELECTED,
            "TextField.background" to SURFACE,
            "TextField.foreground" to TEXT,
            "TextField.caretForeground" to TEXT,
            "TextArea.background" to SURFACE,
            "TextArea.foreground" to TEXT,
            "TextArea.caretForeground" to TEXT,
            "TextArea.selectionBackground" to SELECTION,
            "TextArea.selectionForeground" to TEXT,
            "List.background" to SURFACE,
            "List.foreground" to TEXT,
            "List.selectionBackground" to SELECTION,
            "List.selectionForeground" to TEXT,
            "ComboBox.background" to SURFACE,
            "ComboBox.foreground" to TEXT,
            "ComboBox.selectionBackground" to SELECTION,
            "ComboBox.selectionForeground" to TEXT,
            "ScrollPane.background" to BACKGROUND,
            "Viewport.background" to SURFACE,
            "ScrollBar.background" to SURFACE,
            "ScrollBar.foreground" to TILE_SELECTED,
            "ScrollBar.track" to SURFACE,
            "ScrollBar.trackHighlight" to TILE,
            "ScrollBar.thumb" to TILE_SELECTED,
            "ScrollBar.thumbHighlight" to MUTED_TEXT,
            "ScrollBar.thumbShadow" to TILE,
            "ScrollBar.thumbDarkShadow" to BACKGROUND,
            "SplitPane.background" to BACKGROUND,
            "SplitPaneDivider.draggingColor" to ACCENT,
            "ToolTip.background" to SURFACE,
            "ToolTip.foreground" to TEXT,
            "Label.disabledForeground" to MUTED_TEXT,
        ).forEach { (key, color) -> UIManager.put(key, javax.swing.plaf.ColorUIResource(color)) }
        installWidgetDefaults()
        val font = javax.swing.plaf.FontUIResource(preferredFontFamily(), java.awt.Font.BOLD, textSize(TextRole.LABEL))
        listOf("Button", "ToggleButton", "Label", "TextField", "FormattedTextField", "TextArea", "List",
            "ComboBox", "CheckBox", "RadioButton", "Spinner", "PasswordField", "TextPane", "EditorPane",
            "Table", "TableHeader", "Tree", "MenuBar", "PopupMenu", "MenuItem", "RadioButtonMenuItem",
            "CheckBoxMenuItem", "Menu", "OptionPane", "ProgressBar", "Slider", "TabbedPane", "ToolTip").forEach {
            UIManager.put("$it.font", font)
        }
        listOf("CheckBox", "RadioButton", "TabbedPane", "Spinner", "FormattedTextField", "PopupMenu", "MenuItem").forEach {
            UIManager.put("$it.background", javax.swing.plaf.ColorUIResource(BACKGROUND))
            UIManager.put("$it.foreground", javax.swing.plaf.ColorUIResource(TEXT))
        }
        UIManager.put("ButtonUI", DesktopButtonUIFactory::class.java.name)
        UIManager.put("ToggleButtonUI", DesktopButtonUIFactory::class.java.name)
        UIManager.put("ComboBoxUI", DesktopComboBoxUIFactory::class.java.name)
        val padding = javax.swing.plaf.BorderUIResource.EmptyBorderUIResource(
            px(DesignTokens.CONTROL_PADDING_VERTICAL), px(DesignTokens.CONTROL_PADDING_HORIZONTAL),
            px(DesignTokens.CONTROL_PADDING_VERTICAL), px(DesignTokens.CONTROL_PADDING_HORIZONTAL),
        )
        UIManager.put("Button.border", padding)
        UIManager.put("ToggleButton.border", padding)
        UIManager.put("TextField.border", javax.swing.plaf.BorderUIResource.CompoundBorderUIResource(
            javax.swing.BorderFactory.createLineBorder(DIVIDER),
            javax.swing.BorderFactory.createEmptyBorder(px(8), px(11), px(8), px(11)),
        ))
        UIManager.put("ComboBox.border", javax.swing.plaf.BorderUIResource.CompoundBorderUIResource(
            javax.swing.BorderFactory.createLineBorder(DIVIDER),
            javax.swing.BorderFactory.createEmptyBorder(px(5), px(8), px(5), px(8)),
        ))
        UIManager.put("TabbedPane.background", BACKGROUND)
        UIManager.put("TabbedPane.foreground", MUTED_TEXT)
        UIManager.put("TabbedPane.selected", SURFACE_RAISED)
        UIManager.put("TabbedPane.contentAreaColor", SURFACE)
        UIManager.put("TabbedPane.focus", ACCENT)
        UIManager.put("TabbedPane.light", DIVIDER)
        UIManager.put("TabbedPane.shadow", DIVIDER)
        UIManager.put("TabbedPane.darkShadow", BACKGROUND)
        UIManager.put("TabbedPane.borderHightlightColor", DIVIDER)
        UIManager.put("TabbedPane.tabInsets", java.awt.Insets(px(11), px(18), px(11), px(18)))
        UIManager.put("TabbedPane.contentBorderInsets", java.awt.Insets(1, 0, 0, 0))
        UIManager.put("CheckBox.icon", DesktopCheckBoxIcon())
        UIManager.put("ScrollPane.border", javax.swing.BorderFactory.createEmptyBorder())
        UIManager.put("List.focusCellHighlightBorder", javax.swing.BorderFactory.createLineBorder(ACCENT, 2))
        UIManager.put("ProgressBar.foreground", ACCENT_STRONG)
        UIManager.put("ProgressBar.background", TILE)
        UIManager.put("SplitPane.dividerSize", 6)
        UIManager.put("ScrollBar.width", px(10))
        UIManager.put("SliderUI", DesktopSliderUIFactory::class.java.name)
        UIManager.put("ScrollBarUI", DesktopDarkScrollBarUIFactory::class.java.name)
    }

    private fun installWidgetDefaults() {
        for (control in listOf("Button", "ToggleButton")) {
            UIManager.put("$control.margin", javax.swing.plaf.InsetsUIResource(
                px(DesignTokens.CONTROL_PADDING_VERTICAL), px(DesignTokens.CONTROL_PADDING_HORIZONTAL),
                px(DesignTokens.CONTROL_PADDING_VERTICAL), px(DesignTokens.CONTROL_PADDING_HORIZONTAL),
            ))
        }
        fun put(key: String, color: Color) = UIManager.put(key, javax.swing.plaf.ColorUIResource(color))
        val controls = listOf("Panel", "Label", "Button", "ToggleButton", "CheckBox", "RadioButton", "ComboBox",
            "Spinner", "Slider", "ProgressBar", "TabbedPane", "MenuBar", "Menu", "MenuItem", "PopupMenu",
            "CheckBoxMenuItem", "RadioButtonMenuItem", "OptionPane", "FileChooser", "ToolBar", "ToolTip")
        controls.forEach { control ->
            put("$control.background", BACKGROUND)
            put("$control.foreground", TEXT)
            put("$control.disabledForeground", MUTED_TEXT)
            put("$control.disabledText", MUTED_TEXT)
            put("$control.selectionBackground", SELECTION)
            put("$control.selectionForeground", TEXT)
        }
        listOf("TextField", "FormattedTextField", "PasswordField", "TextArea", "TextPane", "EditorPane",
            "List", "Table", "Tree", "TableHeader").forEach { control ->
            put("$control.background", SURFACE)
            put("$control.foreground", TEXT)
            put("$control.caretForeground", TEXT)
            put("$control.inactiveForeground", MUTED_TEXT)
            put("$control.inactiveBackground", TILE)
            put("$control.selectionBackground", SELECTION)
            put("$control.selectionForeground", TEXT)
        }
        listOf("control", "window", "menu").forEach { put(it, BACKGROUND) }
        listOf("text", "TableHeader.background", "ComboBox.background", "Spinner.background").forEach { put(it, SURFACE) }
        listOf("controlText", "textText", "menuText", "OptionPane.messageForeground").forEach { put(it, TEXT) }
        listOf("controlShadow", "controlDkShadow", "controlHighlight", "controlLtHighlight", "Separator.foreground",
            "Table.gridColor", "TextField.shadow", "TextField.darkShadow", "TextField.light").forEach { put(it, DIVIDER) }
        put("textHighlight", SELECTION)
        put("textHighlightText", TEXT)
        put("textInactiveText", MUTED_TEXT)
        put("ProgressBar.selectionBackground", TEXT)
        put("ProgressBar.selectionForeground", ON_ACCENT)
        put("TitledBorder.titleColor", TEXT)
        put("Slider.focus", ACCENT)
    }

    private class SemanticColor(val role: Int, rgb: Int) : Color(rgb, true)

    internal fun currentColor(color: Color): Color =
        if (color is SemanticColor) semanticColors()[color.role] else color

    private fun semanticColors() = listOf(BACKGROUND, BACKGROUND_RAISED, SURFACE, SURFACE_RAISED, TILE,
        TILE_SELECTED, CHIP, CHIP_SELECTED, TEXT, MUTED_TEXT, SUBTLE_TEXT, ACCENT, ACCENT_STRONG,
        ACCENT_SOFT, POSITIVE, DIVIDER, SELECTION, ON_ACCENT)

    /** Update explicit palette assignments as well as UIResource defaults on existing windows. */
    internal fun refresh(theme: ColorTheme, appearance: Appearance, systemDark: Boolean,
                         roots: List<java.awt.Component> = java.awt.Window.getWindows().filter { it.isDisplayable }) {
        val previous = semanticColors()
        install(theme, appearance, systemDark)
        val current = semanticColors()
        val fallback = previous.zip(current).reversed().toMap()
        // Equal RGB values can represent distinct roles (surface/on-accent, muted/subtle).
        val colors = object : AbstractMap<Color, Color>() {
            override val entries get() = fallback.entries
            override fun get(key: Color): Color? =
                if (key is SemanticColor) current[key.role] else fallback[key]
        }
        val selections = mutableListOf<Triple<javax.swing.text.JTextComponent, Int, Int>>()
        fun recolor(component: java.awt.Component) {
            colors[component.background]?.let { component.background = it }
            colors[component.foreground]?.let { component.foreground = it }
            if (component is DesktopCardPanel) component.refreshFill(colors)
            if (component is JComponent && component.border !is javax.swing.plaf.UIResource) {
                component.border = recolorBorder(component.border, colors)
            }
            if (component is javax.swing.text.JTextComponent) {
                selections += Triple(component, component.caret.dot, component.caret.mark)
                colors[component.caretColor]?.let { component.caretColor = it }
                colors[component.selectionColor]?.let { component.selectionColor = it }
                colors[component.selectedTextColor]?.let { component.selectedTextColor = it }
            }
            if (component is java.awt.Container) component.components.forEach(::recolor)
        }
        roots.forEach { root ->
            recolor(root)
            javax.swing.SwingUtilities.updateComponentTreeUI(root)
            root.invalidate()
            if (root is java.awt.Container) root.validate()
            root.repaint()
        }
        selections.forEach { (field, dot, mark) ->
            field.caret.setDot(mark.coerceAtMost(field.document.length))
            field.caret.moveDot(dot.coerceAtMost(field.document.length))
        }
    }

    private fun recolorBorder(border: javax.swing.border.Border?, colors: Map<Color, Color>): javax.swing.border.Border? =
        when (border) {
            is javax.swing.border.MatteBorder -> border.borderInsets.let { insets ->
                border.matteColor?.let { javax.swing.BorderFactory.createMatteBorder(insets.top, insets.left,
                    insets.bottom, insets.right, colors[it] ?: it) } ?: border
            }
            is javax.swing.border.LineBorder -> javax.swing.border.LineBorder(colors[border.lineColor] ?: border.lineColor,
                border.thickness, border.roundedCorners)
            is javax.swing.border.CompoundBorder -> javax.swing.border.CompoundBorder(
                recolorBorder(border.outsideBorder, colors), recolorBorder(border.insideBorder, colors))
            is javax.swing.border.TitledBorder -> border.apply {
                setBorder(recolorBorder(getBorder(), colors))
                colors[titleColor]?.let { titleColor = it }
            }
            else -> border
        }

    fun monitorSystemAppearance() {
        val settings = com.wuming.tv.desktop.config.DesktopAppearanceSettings()
        val detector = LinuxSystemAppearance()
        val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "desktop-appearance").apply { isDaemon = true }
        }
        var last = palette
        executor.scheduleWithFixedDelay({
            runCatching {
                val theme = settings.colorTheme
                val appearance = settings.appearance
                val dark = appearance == Appearance.DARK || (appearance == Appearance.SYSTEM && detector.isDark())
                val next = ThemePolicy.palette(theme, appearance, dark)
                if (next != last) {
                    javax.swing.SwingUtilities.invokeAndWait { refresh(theme, appearance, dark) }
                    last = next
                }
                if (java.awt.Window.getWindows().none { it.isDisplayable }) executor.shutdown()
            }
        }, 0, 3, java.util.concurrent.TimeUnit.SECONDS)
    }

    // Logical fonts retain the JDK's per-glyph fallback. A physical CJK font
    // renders Chinese but turns emoji and playback symbols into missing-glyph boxes.
    private fun preferredFontFamily(): String = java.awt.Font.SANS_SERIF
}

class DesktopDarkScrollBarUI : BasicScrollBarUI() {

    override fun configureScrollBarColors() {
        thumbColor = DesktopTvTheme.TILE_SELECTED
        thumbHighlightColor = DesktopTvTheme.MUTED_TEXT
        thumbLightShadowColor = DesktopTvTheme.TILE_SELECTED
        thumbDarkShadowColor = DesktopTvTheme.BACKGROUND
        trackColor = DesktopTvTheme.SURFACE
        trackHighlightColor = DesktopTvTheme.TILE
    }

    override fun paintTrack(graphics: Graphics, component: JComponent, bounds: Rectangle) {
        val g = graphics.create() as java.awt.Graphics2D
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = DesktopTvTheme.SURFACE
        g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
        g.dispose()
    }

    override fun paintThumb(graphics: Graphics, component: JComponent, bounds: Rectangle) {
        if (!component.isEnabled || bounds.isEmpty) return
        val g = graphics.create() as java.awt.Graphics2D
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)

        // Calculate thumb opacity based on hover state
        val isHovered = !java.awt.GraphicsEnvironment.isHeadless() &&
            (component as javax.swing.JScrollBar).mousePosition?.let(bounds::contains) == true
        val alpha = if (isHovered) 200 else 140

        g.color = java.awt.Color(
            DesktopTvTheme.TILE_SELECTED.red,
            DesktopTvTheme.TILE_SELECTED.green,
            DesktopTvTheme.TILE_SELECTED.blue,
            alpha
        )
        val arc = DesktopTvTheme.px(6)
        val x = bounds.x + 2
        val y = bounds.y + 2
        val width = (bounds.width - 4).coerceAtLeast(1)
        val height = (bounds.height - 4).coerceAtLeast(1)
        g.fillRoundRect(x, y, width, height, arc, arc)
        g.dispose()
    }

    override fun createDecreaseButton(orientation: Int): JButton = darkArrow(orientation)

    override fun createIncreaseButton(orientation: Int): JButton = darkArrow(orientation)

    override fun getMinimumThumbSize(): Dimension = Dimension(12, 32)

    override fun getMaximumThumbSize(): Dimension = Dimension(12, Int.MAX_VALUE)

    private fun darkArrow(orientation: Int) = BasicArrowButton(
        orientation,
        DesktopTvTheme.TILE,
        DesktopTvTheme.MUTED_TEXT,
        DesktopTvTheme.TEXT,
        DesktopTvTheme.SURFACE,
    ).apply {
        border = null
        isFocusable = false
        preferredSize = Dimension(12, 18)
    }

}

class DesktopDarkScrollBarUIFactory {
    companion object {
        @JvmStatic
        fun createUI(component: JComponent): ComponentUI = DesktopDarkScrollBarUI()
    }
}

/** Flat rounded controls with an explicit keyboard focus ring; retains Swing's action bindings. */
class DesktopButtonUI : javax.swing.plaf.basic.BasicButtonUI() {
    override fun installUI(component: JComponent) {
        super.installUI(component)
        (component as javax.swing.AbstractButton).apply {
            isOpaque = false
            isRolloverEnabled = true
        }
    }

    override fun paint(graphics: Graphics, component: JComponent) {
        val button = component as javax.swing.AbstractButton
        val g = graphics.create() as java.awt.Graphics2D
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        val isPrimary = button.getClientProperty("style") == "primary"
        val isQuiet = button.getClientProperty("style") == "quiet"
        val arc = DesktopTvTheme.px(DesignTokens.CORNER_SMALL * 2)

        // Draw shadow for primary buttons
        if (isPrimary && button.isEnabled) {
            g.color = java.awt.Color(0, 0, 0, 40)
            g.fillRoundRect(2, 2, component.width - 2, component.height - 2, arc, arc)
        }

        // Draw background
        g.color = when {
            !button.isEnabled -> DesktopTvTheme.TILE
            button.model.isPressed || button.isSelected -> DesktopTvTheme.SELECTION
            isPrimary -> DesktopTvTheme.ACCENT_STRONG
            isQuiet -> DesktopTvTheme.SURFACE
            button.model.isRollover -> DesktopTvTheme.TILE_SELECTED
            else -> DesktopTvTheme.TILE
        }
        g.fillRoundRect(1, 1, component.width - 2, component.height - 2, arc, arc)

        // Draw focus ring
        if (button.hasFocus() && button.isEnabled) {
            g.color = DesktopTvTheme.ACCENT
            g.stroke = java.awt.BasicStroke(DesktopTvTheme.px(DesignTokens.FOCUS_WIDTH).toFloat())
            g.drawRoundRect(1, 1, component.width - 3, component.height - 3, arc, arc)
        }

        // Draw hover border for quiet buttons
        if (isQuiet && button.model.isRollover && button.isEnabled && !button.hasFocus()) {
            g.color = DesktopTvTheme.DIVIDER
            g.stroke = java.awt.BasicStroke(1f)
            g.drawRoundRect(1, 1, component.width - 3, component.height - 3, arc, arc)
        }

        g.dispose()
        super.paint(graphics, component)
    }

    override fun paintText(graphics: Graphics, button: javax.swing.AbstractButton, textRect: Rectangle, text: String) {
        val isPrimary = button.getClientProperty("style") == "primary"
        graphics.color = when {
            !button.isEnabled -> DesktopTvTheme.MUTED_TEXT
            isPrimary && !button.model.isPressed && !button.isSelected -> DesktopTvTheme.ON_ACCENT
            else -> button.foreground
        }
        javax.swing.plaf.basic.BasicGraphicsUtils.drawStringUnderlineCharAt(
            graphics, text, button.displayedMnemonicIndex, textRect.x,
            textRect.y + graphics.fontMetrics.ascent,
        )
    }

    override fun paintButtonPressed(graphics: Graphics, button: javax.swing.AbstractButton) = Unit

    override fun getPreferredSize(component: JComponent): Dimension {
        val size = super.getPreferredSize(component)
        return Dimension(size.width.coerceAtLeast(80), size.height.coerceAtLeast(DesktopTvTheme.px(DesignTokens.CONTROL_MIN_HEIGHT)))
    }
}

/** Compact high-contrast check mark that stays legible across Linux look-and-feels. */
class DesktopCheckBoxIcon : javax.swing.Icon, javax.swing.plaf.UIResource {
    override fun getIconWidth(): Int = 20
    override fun getIconHeight(): Int = 20

    override fun paintIcon(component: java.awt.Component, graphics: Graphics, x: Int, y: Int) {
        val button = component as javax.swing.AbstractButton
        val g = graphics.create() as java.awt.Graphics2D
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = if (button.isSelected) DesktopTvTheme.ACCENT_STRONG else DesktopTvTheme.SURFACE_RAISED
        g.fillRoundRect(x, y, iconWidth, iconHeight, 7, 7)
        g.color = if (button.hasFocus()) DesktopTvTheme.ACCENT else DesktopTvTheme.DIVIDER
        g.drawRoundRect(x, y, iconWidth - 1, iconHeight - 1, 7, 7)
        if (button.isSelected) {
            g.color = DesktopTvTheme.ON_ACCENT
            g.stroke = java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            g.drawLine(x + 5, y + 10, x + 9, y + 14)
            g.drawLine(x + 9, y + 14, x + 16, y + 6)
        }
        g.dispose()
    }
}

class DesktopButtonUIFactory {
    companion object {
        @JvmStatic fun createUI(component: JComponent): ComponentUI = DesktopButtonUI()
    }
}

/** Basic Swing combo layout with font-metric-driven height instead of GTK's fixed text row. */
class DesktopComboBoxUI : javax.swing.plaf.basic.BasicComboBoxUI() {
    override fun installDefaults() {
        super.installDefaults()
        if (comboBox.renderer == null || comboBox.renderer is javax.swing.plaf.UIResource) {
            comboBox.renderer = DesktopComboBoxRenderer()
        }
    }

    override fun getMinimumSize(component: JComponent): Dimension = roomy(super.getMinimumSize(component), component)

    override fun getPreferredSize(component: JComponent): Dimension = roomy(super.getPreferredSize(component), component)

    override fun createArrowButton(): JButton = BasicArrowButton(
        javax.swing.SwingConstants.SOUTH,
        DesktopTvTheme.TILE,
        DesktopTvTheme.MUTED_TEXT,
        DesktopTvTheme.TEXT,
        DesktopTvTheme.SURFACE,
    ).apply {
        border = javax.swing.BorderFactory.createEmptyBorder(0, DesktopTvTheme.px(8), 0, DesktopTvTheme.px(8))
        isFocusable = false
        accessibleContext.accessibleName = "Open choices"
    }

    private fun roomy(size: Dimension, component: JComponent): Dimension {
        val metrics = component.getFontMetrics(component.font)
        val insets = component.insets
        val textHeight = metrics.height + insets.top + insets.bottom + DesktopTvTheme.px(8)
        return Dimension(size.width, maxOf(size.height, textHeight))
    }
}

class DesktopComboBoxRenderer : javax.swing.DefaultListCellRenderer(), javax.swing.plaf.UIResource {
    override fun getListCellRendererComponent(
        list: javax.swing.JList<*>?,
        value: Any?,
        index: Int,
        selected: Boolean,
        focus: Boolean,
    ): java.awt.Component = super.getListCellRendererComponent(list, value, index, selected, focus).also {
        border = javax.swing.BorderFactory.createEmptyBorder(
            DesktopTvTheme.px(5),
            DesktopTvTheme.px(9),
            DesktopTvTheme.px(5),
            DesktopTvTheme.px(9),
        )
        verticalAlignment = javax.swing.SwingConstants.CENTER
    }
}

class DesktopComboBoxUIFactory {
    companion object {
        @JvmStatic fun createUI(component: JComponent): ComponentUI = DesktopComboBoxUI()
    }
}

/** Metal icons and borders use semantic colors too, including file chooser and spinner internals. */
private class DesktopMetalTheme : javax.swing.plaf.metal.DefaultMetalTheme() {
    override fun getPrimary1() = javax.swing.plaf.ColorUIResource(DesktopTvTheme.DIVIDER)
    override fun getPrimary2() = javax.swing.plaf.ColorUIResource(DesktopTvTheme.SELECTION)
    override fun getPrimary3() = javax.swing.plaf.ColorUIResource(DesktopTvTheme.SURFACE_RAISED)
    override fun getSecondary1() = javax.swing.plaf.ColorUIResource(DesktopTvTheme.DIVIDER)
    override fun getSecondary2() = javax.swing.plaf.ColorUIResource(DesktopTvTheme.TILE_SELECTED)
    override fun getSecondary3() = javax.swing.plaf.ColorUIResource(DesktopTvTheme.SURFACE)
    override fun getBlack() = javax.swing.plaf.ColorUIResource(DesktopTvTheme.TEXT)
    override fun getWhite() = javax.swing.plaf.ColorUIResource(DesktopTvTheme.BACKGROUND)
}

class DesktopSliderUI(slider: javax.swing.JSlider) : javax.swing.plaf.basic.BasicSliderUI(slider) {
    override fun paintTrack(graphics: Graphics) {
        val g = graphics.create() as java.awt.Graphics2D
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = DesktopTvTheme.TILE_SELECTED
        if (slider.orientation == javax.swing.SwingConstants.HORIZONTAL) {
            g.fillRoundRect(trackRect.x, trackRect.y + trackRect.height / 2 - 3, trackRect.width, 6, 6, 6)
        } else {
            g.fillRoundRect(trackRect.x + trackRect.width / 2 - 3, trackRect.y, 6, trackRect.height, 6, 6)
        }
        g.dispose()
    }

    override fun paintThumb(graphics: Graphics) {
        val g = graphics.create() as java.awt.Graphics2D
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = if (slider.isEnabled) DesktopTvTheme.ACCENT else DesktopTvTheme.MUTED_TEXT
        g.fillRoundRect(thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height, 10, 10)
        g.dispose()
    }

    override fun paintFocus(graphics: Graphics) {
        graphics.color = DesktopTvTheme.ACCENT
        graphics.drawRect(focusRect.x, focusRect.y, focusRect.width - 1, focusRect.height - 1)
    }
}

class DesktopSliderUIFactory {
    companion object {
        @JvmStatic fun createUI(component: JComponent): ComponentUI = DesktopSliderUI(component as javax.swing.JSlider)
    }
}
