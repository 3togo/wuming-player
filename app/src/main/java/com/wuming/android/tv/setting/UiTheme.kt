package com.wuming.android.tv.setting

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.wuming.android.tv.R
import com.wuming.tv.core.design.Appearance
import com.wuming.tv.core.design.ColorTheme

/** Applies the persisted appearance mode and the shared theme identity through Android resource adapters. */
object UiTheme {
    const val APPEARANCE_SYSTEM = 0
    const val APPEARANCE_LIGHT = 1
    const val APPEARANCE_DARK = 2

    const val COLOR_CLASSIC = 0
    const val COLOR_AURORA = 1

    @JvmStatic
    fun applyTo(activity: AppCompatActivity) {
        activity.setTheme(styleFor(DisplaySettings.getColorTheme()))
        applySavedAppearance()
    }

    @JvmStatic
    fun styleFor(colorTheme: Int): Int =
        if (ColorTheme.fromId(colorTheme) == ColorTheme.AURORA) R.style.Theme_App_Aurora else R.style.Theme_App

    @JvmStatic
    fun nightModeFor(appearance: Int): Int =
        when (Appearance.fromId(appearance)) {
            Appearance.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Appearance.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

    @JvmStatic
    fun applySavedAppearance() {
        val mode = nightModeFor(DisplaySettings.getTheme())
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    @JvmStatic
    fun setAppearance(appearance: Int) {
        DisplaySettings.putTheme(appearance)
        applySavedAppearance()
    }
}
