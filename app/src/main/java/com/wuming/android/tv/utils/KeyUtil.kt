package com.wuming.android.tv.utils

import android.view.KeyEvent

object KeyUtil {
    @JvmStatic
    fun isActionDown(event: KeyEvent): Boolean = event.action == KeyEvent.ACTION_DOWN

    @JvmStatic
    fun isActionUp(event: KeyEvent): Boolean = event.action == KeyEvent.ACTION_UP

    @JvmStatic
    fun isEnterKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        event.keyCode == KeyEvent.KEYCODE_ENTER ||
        event.keyCode == KeyEvent.KEYCODE_SPACE ||
        event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    @JvmStatic
    fun isUpKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
        event.keyCode == KeyEvent.KEYCODE_PAGE_UP ||
        event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS

    @JvmStatic
    fun isDownKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
        event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
        event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT

    @JvmStatic
    fun isLeftKey(event: KeyEvent): Boolean = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT

    @JvmStatic
    fun isRightKey(event: KeyEvent): Boolean = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    @JvmStatic
    fun isBackKey(event: KeyEvent): Boolean = event.keyCode == KeyEvent.KEYCODE_BACK

    @JvmStatic
    fun isDigitKey(event: KeyEvent): Boolean =
        (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) ||
        (event.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9)

    @JvmStatic
    fun isMenuKey(event: KeyEvent): Boolean = isActionUp(event) && event.keyCode == KeyEvent.KEYCODE_MENU

    @JvmStatic
    fun isMediaPlayPause(event: KeyEvent): Boolean = isActionUp(event) && (
        event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
        event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE)

    @JvmStatic
    fun isMediaStop(event: KeyEvent): Boolean = isActionUp(event) && event.keyCode == KeyEvent.KEYCODE_MEDIA_STOP

    @JvmStatic
    fun isMediaRewind(event: KeyEvent): Boolean = isActionUp(event) && event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND

    @JvmStatic
    fun isMediaFastForward(event: KeyEvent): Boolean = isActionUp(event) && event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
}
