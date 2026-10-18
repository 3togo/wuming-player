package com.wuming.tv.desktop.config

import java.util.prefs.Preferences

internal class DesktopPlaybackSettings(private val preferences: Preferences) {
    var privateViewing: Boolean
        get() = preferences.getBoolean("playback.privateViewing", false)
        set(value) = preferences.putBoolean("playback.privateViewing", value)

    var autoPlayNextEpisode: Boolean
        get() = preferences.getBoolean(KEY_AUTO_PLAY_NEXT, true)
        set(value) = preferences.putBoolean(KEY_AUTO_PLAY_NEXT, value)

    var autoPlayFirstEpisode: Boolean
        get() = preferences.getBoolean(KEY_AUTO_PLAY_FIRST, true)
        set(value) = preferences.putBoolean(KEY_AUTO_PLAY_FIRST, value)

    var restorePlaybackPosition: Boolean
        get() = preferences.getBoolean(KEY_RESTORE_POSITION, true)
        set(value) = preferences.putBoolean(KEY_RESTORE_POSITION, value)

    var defaultSpeed: Double
        get() = preferences.getDouble(KEY_DEFAULT_SPEED, 1.0)
        set(value) = preferences.putDouble(KEY_DEFAULT_SPEED, value)

    var prewarmNextEpisode: Boolean
        get() = preferences.getBoolean(KEY_PREWARM_NEXT, true)
        set(value) = preferences.putBoolean(KEY_PREWARM_NEXT, value)

    var fullscreenOnStart: Boolean
        get() = preferences.getBoolean(KEY_FULLSCREEN_START, false)
        set(value) = preferences.putBoolean(KEY_FULLSCREEN_START, value)

    private companion object {
        const val KEY_AUTO_PLAY_NEXT = "playback.autoPlayNextEpisode"
        const val KEY_AUTO_PLAY_FIRST = "playback.autoPlayFirstEpisode"
        const val KEY_RESTORE_POSITION = "playback.restorePosition"
        const val KEY_DEFAULT_SPEED = "playback.defaultSpeed"
        const val KEY_PREWARM_NEXT = "playback.prewarmNextEpisode"
        const val KEY_FULLSCREEN_START = "playback.fullscreenOnStart"
    }
}
