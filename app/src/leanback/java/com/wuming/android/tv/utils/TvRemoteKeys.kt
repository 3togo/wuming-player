package com.wuming.android.tv.utils

import android.view.KeyEvent
import java.util.function.IntConsumer

/** Standard Android keys forwarded by HDMI-CEC, IR or Bluetooth remotes. */
object TvRemoteKeys {
    @JvmStatic fun command(code: Int): Int =
        when (code) {
            KeyEvent.KEYCODE_PROG_RED -> KeyEvent.KEYCODE_MENU

            KeyEvent.KEYCODE_PROG_GREEN -> KeyEvent.KEYCODE_CAPTIONS

            KeyEvent.KEYCODE_PROG_YELLOW -> KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK

            KeyEvent.KEYCODE_PROG_BLUE -> KeyEvent.KEYCODE_INFO

            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> code

            else -> KeyEvent.KEYCODE_UNKNOWN
        }

    /** Consume both edges; execute only on an uncancelled release. */
    @JvmStatic fun dispatch(
        event: KeyEvent,
        action: IntConsumer,
    ): Boolean {
        val command = command(event.keyCode)
        if (command == KeyEvent.KEYCODE_UNKNOWN) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) action.accept(command)
        return true
    }
}
