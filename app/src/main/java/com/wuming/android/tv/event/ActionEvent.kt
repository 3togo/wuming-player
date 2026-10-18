package com.wuming.android.tv.event

import com.wuming.android.tv.BuildConfig

object ActionEvent {
    const val STOP: String = BuildConfig.APPLICATION_ID + ".stop"
    const val PREV: String = BuildConfig.APPLICATION_ID + ".prev"
    const val NEXT: String = BuildConfig.APPLICATION_ID + ".next"
    const val LOOP: String = BuildConfig.APPLICATION_ID + ".loop"
    const val PLAY: String = BuildConfig.APPLICATION_ID + ".play"
    const val PAUSE: String = BuildConfig.APPLICATION_ID + ".pause"
    const val AUDIO: String = BuildConfig.APPLICATION_ID + ".audio"
    const val REPLAY: String = BuildConfig.APPLICATION_ID + ".replay"
}
