package com.wuming.android.tv.cloud.opendrive

import com.wuming.tv.core.cloud.OpenDriveFailure

/** No supported smart transcode is available, including the low-quality fallback. */
class QuarkSmartQualityException : OpenDriveFailure("quark", Reason.TRANSCODE_UNAVAILABLE) {
    companion object {
        private const val MESSAGE = "Quark smart quality unavailable: no accessible middle-quality stream"

        @JvmStatic
        fun matches(message: String?): Boolean {
            val failure = OpenDriveFailure.fromMessage(message)
            return MESSAGE == message || (failure != null && "quark" == failure.provider()
                    && failure.reason() == Reason.TRANSCODE_UNAVAILABLE)
        }
    }
}
