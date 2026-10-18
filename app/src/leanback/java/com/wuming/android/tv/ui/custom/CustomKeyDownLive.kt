package com.wuming.android.tv.ui.custom

import android.content.Context
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import com.wuming.android.tv.App
import com.wuming.android.tv.Constant
import com.wuming.android.tv.utils.KeyUtil
import java.lang.ref.WeakReference

open class CustomKeyDownLive private constructor(
    context: Context,
) : GestureDetector.SimpleOnGestureListener() {
    private class FindRunnable(
        listener: Listener,
        private val text: StringBuilder,
    ) : Runnable {
        private val listenerRef = WeakReference(listener)

        override fun run() {
            listenerRef.get()?.onFind(text.toString())
            text.setLength(0)
        }
    }

    private val text = StringBuilder()
    private val listener = context as Listener
    private val detector = GestureDetector(context, this)
    private val runnable = FindRunnable(listener, text)
    private var holdTime = 0L
    private var centerLongPressed = false

    companion object {
        @JvmStatic fun create(context: Context): CustomKeyDownLive = CustomKeyDownLive(context)
    }

    open fun onTouchEvent(e: MotionEvent): Boolean = detector.onTouchEvent(e)

    open fun hasEvent(event: KeyEvent): Boolean =
        KeyUtil.isEnterKey(event) || KeyUtil.isUpKey(event) || KeyUtil.isDownKey(event) || KeyUtil.isLeftKey(event) ||
            KeyUtil.isRightKey(event) ||
            KeyUtil.isDigitKey(event) ||
            KeyUtil.isMenuKey(event) ||
            (text.isNotEmpty() && KeyUtil.isBackKey(event))

    open fun onKeyDown(event: KeyEvent): Boolean {
        if (centerLongPressed && KeyUtil.isEnterKey(event) && KeyUtil.isActionUp(event)) {
            centerLongPressed = false
            return true
        }
        if (!listener.dispatch(true)) return false
        if (event.isCanceled) return true
        check(event)
        return true
    }

    private fun check(event: KeyEvent) {
        if (text.isNotEmpty() && KeyUtil.isBackKey(event)) {
            if (KeyUtil.isActionUp(event)) {
                App.removeCallbacks(runnable)
                text.setLength(0)
                listener.onShow("")
            }
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isEnterKey(event) && event.repeatCount == 0) {
            centerLongPressed = false
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isLeftKey(event)) {
            listener.onSeeking(subTime())
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isRightKey(event)) {
            listener.onSeeking(addTime())
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isUpKey(event)) {
            listener.onKeyUp()
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event)) {
            listener.onKeyDown()
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isLeftKey(event)) {
            listener.onKeyLeft(holdTime)
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isRightKey(event)) {
            listener.onKeyRight(holdTime)
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isDigitKey(event)) {
            onKeyDown(event.keyCode)
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isEnterKey(event)) {
            if (centerLongPressed) {
                centerLongPressed = false
            } else if (text.isNotEmpty()) {
                App.removeCallbacks(runnable)
                runnable.run()
            } else {
                listener.onKeyCenter()
            }
        } else if (KeyUtil.isMenuKey(event) || (event.isLongPress && KeyUtil.isEnterKey(event))) {
            centerLongPressed = KeyUtil.isEnterKey(event)
            listener.onMenu()
        }
    }

    private fun onKeyDown(keyCode: Int) {
        if (text.length >= 4) return
        text.append(getNumber(keyCode))
        listener.onShow(text.toString())
        App.post(runnable, 2000)
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (listener.dispatch(false)) listener.onDoubleTap()
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (listener.dispatch(false)) listener.onSingleTap()
        return true
    }

    private fun getNumber(keyCode: Int): Int =
        if (keyCode >=
            KeyEvent.KEYCODE_NUMPAD_0
        ) {
            keyCode - KeyEvent.KEYCODE_NUMPAD_0
        } else {
            keyCode - KeyEvent.KEYCODE_0
        }

    private fun addTime(): Long {
        holdTime += Constant.INTERVAL_SEEK
        return holdTime
    }

    private fun subTime(): Long {
        holdTime -= Constant.INTERVAL_SEEK
        return holdTime
    }

    open fun release() {
        App.removeCallbacks(runnable)
        text.setLength(0)
    }

    open fun reset() {
        holdTime = 0
    }

    interface Listener {
        fun dispatch(check: Boolean): Boolean

        fun onShow(number: String)

        fun onFind(number: String)

        fun onSeeking(time: Long)

        fun onKeyUp()

        fun onKeyDown()

        fun onKeyLeft(time: Long)

        fun onKeyRight(time: Long)

        fun onKeyCenter()

        fun onMenu()

        fun onSingleTap()

        fun onDoubleTap()
    }
}
