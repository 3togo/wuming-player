package com.wuming.android.tv.cloud.opendrive

import com.wuming.android.tv.R
import com.wuming.tv.core.cloud.OpenDriveFailure
import com.wuming.tv.core.playback.OpenDrivePlaybackHints

object OpenDriveMessages {

    @JvmStatic
    fun failure(message: String): Int {
        val failure = OpenDriveFailure.fromMessage(message) ?: return 0
        return when (failure.reason()) {
            OpenDriveFailure.Reason.AUTH_REQUIRED -> R.string.error_cloud_auth_required
            OpenDriveFailure.Reason.QUOTA_EXHAUSTED -> R.string.error_cloud_quota
            OpenDriveFailure.Reason.TRANSCODE_UNAVAILABLE -> R.string.error_cloud_transcode
            OpenDriveFailure.Reason.RESOURCE_UNAVAILABLE -> R.string.error_cloud_resource
            OpenDriveFailure.Reason.ACCESS_DENIED -> R.string.error_cloud_access
            OpenDriveFailure.Reason.NETWORK -> R.string.error_cloud_network
            OpenDriveFailure.Reason.PROVIDER_ERROR -> R.string.error_cloud_provider
        }
    }

    @JvmStatic
    fun fallback(headers: Map<String, String>): Int =
        when (OpenDrivePlaybackHints.fallbackReason(headers)) {
            OpenDrivePlaybackHints.FallbackReason.QUARK_NOT_VIDEO -> R.string.quark_smart_using_original
            OpenDrivePlaybackHints.FallbackReason.UC_QUOTA -> R.string.cloud_using_original_quota
            OpenDrivePlaybackHints.FallbackReason.ALI_SHARED_ORIGINAL_FORBIDDEN -> R.string.cloud_using_saved_original
            OpenDrivePlaybackHints.FallbackReason.BAIDU_PREVIEW_REJECTED -> R.string.cloud_using_original_preview
            OpenDrivePlaybackHints.FallbackReason.NONE -> 0
        }
}
