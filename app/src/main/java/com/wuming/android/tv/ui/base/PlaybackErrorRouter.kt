package com.wuming.android.tv.ui.base

import com.wuming.android.tv.bean.QrChallenge
import com.wuming.android.tv.cloud.opendrive.QuarkSmartQualityException

/** Classifies playback errors for the activity's recovery flow. */
internal class PlaybackErrorRouter private constructor(
    private val kind: Kind,
    private val displayMessage: String,
    private val challenge: QrChallenge?,
) {
    enum class Kind { QUARK_SMART_QUALITY, UNSUPPORTED_FORMAT, BILIBILI_LOGIN, QR_CHALLENGE, GENERIC }

    fun kind(): Kind = kind

    fun displayMessage(): String = displayMessage

    fun challenge(): QrChallenge? = challenge

    companion object {
        @JvmStatic fun isQuarkSmartQuality(message: String?): Boolean = QuarkSmartQualityException.matches(message)

        @JvmStatic fun isUnsupportedFormat(
            message: String,
            unsupportedText: String?,
        ): Boolean = unsupportedText != null && (unsupportedText == message || message.startsWith(unsupportedText + "\n"))

        @JvmStatic fun classify(
            message: String,
            bilibiliLoginError: Boolean,
        ): PlaybackErrorRouter {
            if (bilibiliLoginError) return PlaybackErrorRouter(Kind.BILIBILI_LOGIN, message, null)
            val challenge = QrChallenge.from(message)
            if (challenge.hasImage()) return PlaybackErrorRouter(Kind.QR_CHALLENGE, message, challenge)
            return PlaybackErrorRouter(Kind.GENERIC, message, null)
        }
    }
}
