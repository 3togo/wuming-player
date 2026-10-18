package com.wuming.android.tv.ui.custom

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.setting.DisplaySettings
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.Util

open class CustomKeyDown private constructor(
    private val activity: Activity,
    private val videoView: View,
) : GestureDetector.SimpleOnGestureListener(),
    ScaleGestureDetector.OnScaleGestureListener {
    companion object {
        private const val DISTANCE = 100

        @JvmStatic fun create(
            activity: Activity,
            videoView: View,
        ): CustomKeyDown = CustomKeyDown(activity, videoView)
    }

    private val manager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scaleDetector = ScaleGestureDetector(activity, this)
    private val detector = GestureDetector(activity, this)
    private val listener = activity as Listener
    private var changeBright = false
    private var changeVolume = false
    private var changeSpeed = false
    private var changeScale = false
    private var changeTime = false
    private var multiTouch = false
    private var animating = false
    private var touch = false
    private var lock = false
    private var bright = 0f
    private var volume = 0f
    private var scale = 1f
    private var time = 0L

    open fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN) multiTouch = false
        if (action == MotionEvent.ACTION_POINTER_DOWN) multiTouch = true
        if (action == MotionEvent.ACTION_UP) listener.onTouchEnd()
        if (changeSpeed && action == MotionEvent.ACTION_UP) listener.onSpeedEnd()
        if (changeTime && action == MotionEvent.ACTION_UP) listener.onSeekEnd(time)
        return if (e.pointerCount == 2) scaleDetector.onTouchEvent(e) else detector.onTouchEvent(e)
    }

    open fun resetScale() {
        if (scale == 1f) return
        videoView
            .animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(250)
            .withEndAction {
                videoView.pivotY = videoView.height / 2f
                videoView.pivotX = videoView.width / 2f
                scale = 1f
            }.start()
    }

    open fun setLock(lock: Boolean) {
        this.lock = lock
    }

    open fun getScale(): Float = scale

    private fun isMultiple(e: MotionEvent): Boolean = e.pointerCount > 1

    private fun isEdge(e: MotionEvent): Boolean = ResUtil.isEdge(activity, e, ResUtil.getDimensionPixelSize(R.dimen.vs_24))

    private fun isSide(e: MotionEvent): Boolean {
        val four = ResUtil.getScreenWidth(activity) / 4
        return e.x <= four || e.x >= four * 3
    }

    private fun reset() {
        time = 0
        touch = true
        changeTime = false
        changeSpeed = false
        changeBright = false
        changeVolume = false
        bright = Util.getBrightness(activity)
        volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
    }

    override fun onDown(e: MotionEvent): Boolean {
        if (isMultiple(e) || isEdge(e) || changeScale ||
            lock
        ) {
            return true
        }
        reset()
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        if (multiTouch || isEdge(e) || changeScale ||
            lock
        ) {
            return
        }
        listener.onSpeedUp()
        changeSpeed = true
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float,
    ): Boolean {
        if (isMultiple(e1!!) || isEdge(e1) || changeScale || lock || changeSpeed) return true
        val deltaX = e2.x - e1.x
        val deltaY = e1.y - e2.y
        if (touch) checkFunc(kotlin.math.abs(deltaX), kotlin.math.abs(deltaY), e2)
        if (changeTime) {
            val dur = listener.getVideoDuration()
            // A full-width swipe covers the video duration when it is known.
            time = if (dur > 0 && videoView.width > 0) (deltaX / videoView.width * dur).toLong() else (deltaX * 50).toLong()
            listener.onSeeking(time)
        }
        if (changeBright) setBright(deltaY)
        if (changeVolume) setVolume(deltaY)
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (isMultiple(e) || isEdge(e) || changeScale || lock) return true
        val width = videoView.width
        val x = e.x
        if (x < width / 3f) {
            listener.onDoubleTapSeek(-10000)
        } else if (x > width * 2f / 3f) {
            listener.onDoubleTapSeek(10000)
        } else {
            listener.onDoubleTap()
        }
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (isMultiple(e) || isEdge(e) ||
            changeScale
        ) {
            return true
        }
        listener.onSingleTap()
        return true
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float,
    ): Boolean {
        if (isMultiple(e1!!) || isEdge(e1) || isSide(e1) || changeScale || lock || animating) return true
        checkFunc(e1, e2)
        return true
    }

    private fun checkFunc(
        distanceX: Float,
        distanceY: Float,
        e2: MotionEvent,
    ) {
        if (Math.sqrt((distanceX * distanceX + distanceY * distanceY).toDouble()).toFloat() <
            ResUtil.getDimensionPixelSize(R.dimen.vs_20)
        ) {
            return
        }
        if (distanceX >= distanceY) {
            changeTime = true
        } else if (isSide(e2)) {
            checkSide(e2)
        }
        touch = false
    }

    private fun checkFunc(
        e1: MotionEvent,
        e2: MotionEvent,
    ) {
        val dx = e2.x - e1.x
        val dy = e2.y - e1.y
        val angle = Math.toDegrees(Math.atan2(kotlin.math.abs(dy).toDouble(), kotlin.math.abs(dx).toDouble()))
        if (angle > 70 && e1.y - e2.y > DISTANCE) {
            videoView
                .animate()
                .translationYBy(
                    ResUtil.dp2px(if (DisplaySettings.isInvert()) 24 else -24).toFloat(),
                ).setDuration(150)
                .withStartAction {
                    animating =
                        true
                }.withEndAction {
                    videoView
                        .animate()
                        .translationY(
                            0f,
                        ).setDuration(100)
                        .withStartAction(listener::onFlingUp)
                        .withEndAction { animating = false }
                        .start()
                }.start()
        } else if (angle > 70 && e2.y - e1.y > DISTANCE) {
            videoView
                .animate()
                .translationYBy(
                    ResUtil.dp2px(if (DisplaySettings.isInvert()) -24 else 24).toFloat(),
                ).setDuration(150)
                .withStartAction {
                    animating =
                        true
                }.withEndAction {
                    videoView
                        .animate()
                        .translationY(0f)
                        .setDuration(100)
                        .withStartAction(listener::onFlingDown)
                        .withEndAction {
                            animating =
                                false
                        }.start()
                }.start()
        }
    }

    private fun checkSide(e2: MotionEvent) {
        val half =
            ResUtil.getScreenWidth(activity) / 2
        if (e2.x > half) changeVolume = true else changeBright = true
    }

    private fun setBright(deltaY: Float) {
        val height = videoView.measuredHeight
        var brightness = deltaY * 2f / height + bright
        if (brightness < 0) brightness = 0f
        if (brightness > 1f) brightness = 1f
        val attributes = activity.window.attributes
        attributes.screenBrightness = brightness
        activity.window.attributes = attributes
        listener.onBright((brightness * 100).toInt())
    }

    private fun setVolume(deltaY: Float) {
        val height = videoView.measuredHeight
        val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val deltaV = deltaY * 2f / height * maxVolume
        var index = volume + deltaV
        if (index > maxVolume) index = maxVolume.toFloat()
        if (index < 0) index = 0f
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, index.toInt(), 0)
        listener.onVolume((index / maxVolume * 100f).toInt())
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        changeScale =
            !(changeBright || changeVolume || changeSpeed || changeTime || lock)
        return changeScale
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
        App.post({ changeScale = false }, 500)
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        scale *= detector.scaleFactor
        scale = maxOf(1f, minOf(scale, 5f))
        videoView.pivotX = detector.focusX
        videoView.pivotY = detector.focusY
        videoView.scaleX = scale
        videoView.scaleY = scale
        return true
    }

    interface Listener {
        fun getVideoDuration(): Long

        fun onSeeking(time: Long)

        fun onSeekEnd(time: Long)

        fun onSpeedUp()

        fun onSpeedEnd()

        fun onBright(progress: Int)

        fun onVolume(progress: Int)

        fun onFlingUp()

        fun onFlingDown()

        fun onSingleTap()

        fun onDoubleTap()

        fun onDoubleTapSeek(time: Long)

        fun onTouchEnd()
    }
}
