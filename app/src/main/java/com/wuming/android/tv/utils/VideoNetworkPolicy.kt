package com.wuming.android.tv.utils

import android.net.NetworkCapabilities
import androidx.media3.exoplayer.scheduler.Requirements
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.setting.AppSettings
import java.io.IOException

/** Controls only high-bandwidth video playback and offline downloads. */
object VideoNetworkPolicy {
    @JvmStatic
    fun isAllowed(): Boolean = blockedMessage() == 0

    @JvmStatic
    @Throws(IOException::class)
    fun requireAllowed() {
        val message = blockedMessage()
        if (message != 0) throw IOException(ResUtil.getString(message))
    }

    /** Returns a localized string resource, or zero when video transfer is allowed. */
    @JvmStatic
    fun blockedMessage(): Int {
        return when (com.wuming.tv.core.playback.VideoNetworkRules.blocked(isCellular(), isCellularRoaming(),
            AppSettings.isMobileVideoAllowed(), AppSettings.isRoamingVideoAllowed())) {
            com.wuming.tv.core.playback.VideoNetworkRules.Blocked.MOBILE_DATA -> R.string.video_mobile_data_blocked
            com.wuming.tv.core.playback.VideoNetworkRules.Blocked.ROAMING_DATA -> R.string.video_roaming_data_blocked
            null -> 0
        }
    }

    /** Returns warning text only when the current cellular connection is permitted for video. */
    @JvmStatic
    fun activeWarningMessage(): Int {
        return when (com.wuming.tv.core.playback.VideoNetworkRules.warning(isCellular(), isCellularRoaming(),
            AppSettings.isMobileVideoAllowed(), AppSettings.isRoamingVideoAllowed())) {
            com.wuming.tv.core.playback.VideoNetworkRules.Warning.MOBILE_DATA -> R.string.video_using_mobile_data
            com.wuming.tv.core.playback.VideoNetworkRules.Warning.ROAMING_DATA -> R.string.video_using_roaming_data
            null -> 0
        }
    }

    private fun isCellular(): Boolean {
        val capabilities = activeCapabilities()
        return capabilities != null &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun isCellularRoaming(): Boolean {
        val capabilities = activeCapabilities()
        return capabilities != null &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            isRoaming(capabilities)
    }

    private fun activeCapabilities(): NetworkCapabilities? =
        PlatformCapabilities.activeNetworkCapabilities(App.get())

    private fun isRoaming(capabilities: NetworkCapabilities): Boolean =
        PlatformCapabilities.isCellularRoaming(App.get(), capabilities)

    @JvmStatic
    fun downloadRequirementFlags(): Int =
        downloadRequirementFlags(AppSettings.isMobileVideoAllowed())

    @JvmStatic
    @JvmName("downloadRequirementFlags")
    internal fun downloadRequirementFlags(mobileAllowed: Boolean): Int =
        if (com.wuming.tv.core.playback.VideoNetworkRules.requiresUnmeteredNetwork(mobileAllowed)) Requirements.NETWORK_UNMETERED else Requirements.NETWORK

    @JvmStatic
    fun isNetworkVideo(url: String?): Boolean = UrlUtil.isHttp(url)
}
