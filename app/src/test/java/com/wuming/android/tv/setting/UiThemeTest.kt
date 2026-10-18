package com.wuming.android.tv.setting

import androidx.appcompat.app.AppCompatDelegate
import com.wuming.android.tv.R
import org.junit.Assert.assertEquals
import org.junit.Test

class UiThemeTest {
    @Test
    fun appearanceValuesMapToMatchingNightModes() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, UiTheme.nightModeFor(UiTheme.APPEARANCE_SYSTEM))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, UiTheme.nightModeFor(UiTheme.APPEARANCE_LIGHT))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, UiTheme.nightModeFor(UiTheme.APPEARANCE_DARK))
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, UiTheme.nightModeFor(Int.MAX_VALUE))
    }

    @Test
    fun auroraHasAnIndependentActivityStyle() {
        assertEquals(R.style.Theme_App, UiTheme.styleFor(UiTheme.COLOR_CLASSIC))
        assertEquals(R.style.Theme_App_Aurora, UiTheme.styleFor(UiTheme.COLOR_AURORA))
        assertEquals(R.style.Theme_App, UiTheme.styleFor(Int.MAX_VALUE))
    }
}
