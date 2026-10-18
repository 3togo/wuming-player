package com.wuming.android.tv.utils

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

open class AudioFocusHelper private constructor(
    context: Context,
) : AudioManager.OnAudioFocusChangeListener {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    companion object {
        @JvmStatic fun create(context: Context): AudioFocusHelper = AudioFocusHelper(context)
    }

    @Suppress("DEPRECATION")
    open fun requestFocus(): Boolean {
        if (hasFocus) return true
        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest ==
                    null
                ) {
                    focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setOnAudioFocusChangeListener(this).build()
                }
                audioManager.requestAudioFocus(focusRequest!!)
            } else {
                audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    @Suppress("DEPRECATION")
    open fun abandonFocus() {
        // Transient loss clears hasFocus but leaves the listener registered.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            audioManager.abandonAudioFocus(this)
        }
        hasFocus = false
    }

    open fun hasFocus(): Boolean = hasFocus

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
            }

            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}
        }
    }
}
