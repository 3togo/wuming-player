package com.wuming.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.wuming.android.tv.R
import java.util.Formatter
import java.util.Locale
import java.util.concurrent.TimeUnit

open class CustomSeekView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr),
        Player.Listener,
        TimeBar.OnScrubListener {
        companion object {
            private const val MAX_UPDATE_INTERVAL_MS = 1000
            private const val MIN_UPDATE_INTERVAL_MS = 200
        }

        private val timeBuilder = StringBuilder()
        private val timeFormatter = Formatter(timeBuilder, Locale.getDefault())
        private val positionView: TextView
        private val durationView: TextView
        private val timeBar: DefaultTimeBar
        private val runnable: Runnable
        private var currentDuration = 0L
        private var currentPosition = 0L
        private var currentBuffered = 0L
        private var scrubbing = false
        private var attached = false
        private var player: Player? = null

        init {
            LayoutInflater.from(context).inflate(R.layout.view_control_seek, this)
            positionView = findViewById(R.id.position)
            durationView = findViewById(R.id.duration)
            timeBar = findViewById(R.id.timeBar)
            runnable = Runnable { updateProgress() }
            timeBar.addListener(this)
            resetView()
        }

        open fun setPlayer(player: Player?) {
            removeCallbacks(runnable)
            if (attached) this.player?.removeListener(this)
            this.player = player
            if (attached) player?.addListener(this)
            if (player == null) resetView()
            if (attached) updateTimeline()
        }

        private fun stringToTime(time: Long): String = Util.getStringForTime(timeBuilder, timeFormatter, time)

        private fun updateTimeline() {
            if (!attached || player == null) return
            val duration = maxOf(player!!.duration, 0)
            currentDuration = duration
            setKeyTimeIncrement(duration)
            timeBar.setDuration(duration)
            durationView.text = stringToTime(duration)
            updateProgress()
        }

        private fun updateProgress() {
            removeCallbacks(runnable)
            if (!attached || player == null) return
            val position = player!!.currentPosition
            val buffered = player!!.bufferedPosition
            val duration = maxOf(player!!.duration, 0)
            if (duration !=
                currentDuration
            ) {
                currentDuration = duration
                setKeyTimeIncrement(duration)
                timeBar.setDuration(duration)
                durationView.text =
                    stringToTime(duration)
            }
            if (position != currentPosition) {
                currentPosition = position
                if (!scrubbing) {
                    timeBar.setPosition(position)
                    positionView.text = stringToTime(position)
                }
            }
            if (buffered != currentBuffered) {
                currentBuffered = buffered
                timeBar.setBufferedPosition(buffered)
            }
            postDelayed(runnable, if (player!!.isPlaying) delayMs(position) else MAX_UPDATE_INTERVAL_MS.toLong())
        }

        private fun resetView() {
            positionView.setText(R.string.player_time_zero)
            durationView.setText(R.string.player_time_zero)
            currentPosition = 0
            timeBar.setPosition(currentPosition)
            currentDuration = 0
            timeBar.setDuration(currentDuration)
            currentBuffered = 0
            timeBar.setBufferedPosition(currentBuffered)
        }

        private fun setKeyTimeIncrement(duration: Long) {
            when {
                duration > TimeUnit.HOURS.toMillis(3) -> timeBar.setKeyTimeIncrement(TimeUnit.MINUTES.toMillis(5))
                duration > TimeUnit.MINUTES.toMillis(30) -> timeBar.setKeyTimeIncrement(TimeUnit.MINUTES.toMillis(1))
                duration > TimeUnit.MINUTES.toMillis(15) -> timeBar.setKeyTimeIncrement(TimeUnit.SECONDS.toMillis(30))
                duration > TimeUnit.MINUTES.toMillis(10) -> timeBar.setKeyTimeIncrement(TimeUnit.SECONDS.toMillis(15))
                duration > 0 -> timeBar.setKeyTimeIncrement(TimeUnit.SECONDS.toMillis(10))
            }
        }

        private fun delayMs(position: Long): Long {
            val speed = player!!.playbackParameters.speed
            val mediaTimeUntilNextFullSecondMs = 1000 - position % 1000
            val mediaTimeDelayMs = minOf(timeBar.preferredUpdateDelay, mediaTimeUntilNextFullSecondMs)
            val delayMs = (mediaTimeDelayMs / maxOf(speed, 0.1f)).toLong()
            return Util.constrainValue(delayMs, MIN_UPDATE_INTERVAL_MS.toLong(), MAX_UPDATE_INTERVAL_MS.toLong())
        }

        private fun seekToTimeBarPosition(positionMs: Long) {
            if (player ==
                null
            ) {
                return
            }
            player!!.seekTo(positionMs)
            updateProgress()
            player!!.play()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            attached = true
            player?.addListener(this)
            updateTimeline()
        }

        override fun onDetachedFromWindow() {
            attached = false
            removeCallbacks(runnable)
            player?.removeListener(this)
            super.onDetachedFromWindow()
        }

        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int,
        ) {
            resetView()
        }

        override fun onScrubStart(
            timeBar: TimeBar,
            position: Long,
        ) {
            scrubbing = true
            positionView.text = stringToTime(position)
        }

        override fun onScrubMove(
            timeBar: TimeBar,
            position: Long,
        ) {
            positionView.text = stringToTime(position)
        }

        override fun onScrubStop(
            timeBar: TimeBar,
            position: Long,
            canceled: Boolean,
        ) {
            scrubbing = false
            if (!canceled) seekToTimeBarPosition(position)
        }
    }
