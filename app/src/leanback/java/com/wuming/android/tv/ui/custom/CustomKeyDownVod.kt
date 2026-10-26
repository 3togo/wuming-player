package com.wuming.android.tv.ui.custom

import android.app.Activity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import com.wuming.android.tv.App
import com.wuming.android.tv.Constant
import com.wuming.android.tv.utils.KeyUtil

open class CustomKeyDownVod private constructor(
    activity: Activity,
) : GestureDetector.SimpleOnGestureListener() {
    private val detector = GestureDetector(activity, this)
    private val listener = activity as Listener
    private var changeSpeed = false
    private var full = false
    private var holdTime = 0L
    private var seekEndRunnable: Runnable? = null

    companion object {
        @JvmStatic fun create(activity: Activity): CustomKeyDownVod = CustomKeyDownVod(activity)
    }

    open fun onTouchEvent(e: MotionEvent): Boolean = full && detector.onTouchEvent(e)

    open fun setFull(full: Boolean) {
        this.full = full
    }

    open fun hasEvent(event: KeyEvent): Boolean =
        KeyUtil.isEnterKey(event) || KeyUtil.isUpKey(event) || KeyUtil.isDownKey(event) || KeyUtil.isLeftKey(event) ||
            KeyUtil.isRightKey(event)

    open fun onKeyDown(event: KeyEvent): Boolean {
        check(event)
        return true
    }

    private fun check(event: KeyEvent) {
        if (KeyUtil.isActionDown(event) && KeyUtil.isLeftKey(event)) {
            listener.onSeeking(subTime())
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isRightKey(event)) {
            listener.onSeeking(addTime())
        } else if (KeyUtil.isActionUp(event) &&
            (KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event))
        ) {
            val snapshot = holdTime
            seekEndRunnable?.let { App.removeCallbacks(it) }
            val callback = Runnable {
                seekEndRunnable = null
                listener.onSeekEnd(snapshot)
            }
            seekEndRunnable = callback
            App.post(callback, 250)
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isUpKey(event)) {
            if (changeSpeed) listener.onSpeedEnd() else listener.onKeyUp()
            changeSpeed = false
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isDownKey(event)) {
            listener.onKeyDown()
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isEnterKey(event)) {
            listener.onKeyCenter()
        } else if (event.isLongPress && KeyUtil.isUpKey(event)) {
            listener.onSpeedUp()
            changeSpeed = true
        }
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        listener.onDoubleTap()
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        listener.onSingleTap()
        return true
    }

    private fun addTime(): Long {
        holdTime += Constant.INTERVAL_SEEK
        return holdTime
    }

    private fun subTime(): Long {
        holdTime -= Constant.INTERVAL_SEEK
        return holdTime
    }

    open fun reset() {
        holdTime = 0
    }

    /** Release the delayed key-up commit before the owning TV screen is destroyed. */
    open fun release() {
        seekEndRunnable?.let { App.removeCallbacks(it) }
        seekEndRunnable = null
    }

    interface Listener {
        fun onSeeking(time: Long)

        fun onSeekEnd(time: Long)

        fun onSpeedUp()

        fun onSpeedEnd()

        fun onKeyUp()

        fun onKeyDown()

        fun onKeyCenter()

        fun onSingleTap()

        fun onDoubleTap()
    }
}
