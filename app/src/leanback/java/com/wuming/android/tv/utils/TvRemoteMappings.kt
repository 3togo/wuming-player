package com.wuming.android.tv.utils

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/** App-local playback mappings. Navigation and system keys retain their meaning. */
class TvRemoteMappings(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("tv_remote_mappings", Context.MODE_PRIVATE)

    companion object {
        @JvmStatic fun canAssign(key: Int): Boolean {
            if (key <= KeyEvent.KEYCODE_UNKNOWN || key > KeyEvent.getMaxKeyCode()) return false
            return when (key) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_SLEEP,
                KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_SPACE,
                -> false

                else -> true
            }
        }

        @JvmStatic fun targets(): IntArray =
            intArrayOf(
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_CAPTIONS,
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_0,
                KeyEvent.KEYCODE_1,
                KeyEvent.KEYCODE_2,
                KeyEvent.KEYCODE_3,
                KeyEvent.KEYCODE_4,
                KeyEvent.KEYCODE_5,
                KeyEvent.KEYCODE_6,
                KeyEvent.KEYCODE_7,
                KeyEvent.KEYCODE_8,
                KeyEvent.KEYCODE_9,
            )
    }

    fun target(key: Int): Int {
        if (!canAssign(key)) return key
        val mapped = preferences.getInt(key.toString(), key)
        for (target in targets()) if (mapped == target) return mapped
        return key
    }

    fun assign(
        key: Int,
        target: Int,
    ) {
        require(canAssign(key)) { "Reserved remote key" }
        for (allowed in targets()) {
            if (target == allowed) {
                preferences.edit().putInt(key.toString(), target).apply()
                return
            }
        }
        throw IllegalArgumentException("Unsupported remote action")
    }

    fun remove(key: Int) {
        preferences.edit().remove(key.toString()).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    fun translate(event: KeyEvent): KeyEvent {
        val key = target(event.keyCode)
        if (key == event.keyCode) return event
        return KeyEvent(
            event.downTime,
            event.eventTime,
            event.action,
            key,
            event.repeatCount,
            event.metaState,
            event.deviceId,
            event.scanCode,
            event.flags,
            event.source,
        )
    }
}
