package com.wuming.android.tv.cloud.opendrive

import android.app.Activity
import com.wuming.android.tv.cloud.CloudStorageProvider
import com.wuming.android.tv.ui.activity.CloudStorageWebActivity
import com.wuming.android.tv.ui.activity.WebDavConnectorActivity

object OpenDriveLoginLauncher {

    private const val PREFIX = "OpenDriveLogin:"

    @JvmStatic
    fun open(activity: Activity?, action: String?): Boolean {
        if (activity == null || action == null || !action.startsWith(PREFIX)) return false
        val providerId = action.substring(PREFIX.length)
        if (providerId == "webdav") {
            if (!WebDavConnectorFeature.isEnabled()) return false
            WebDavConnectorActivity.start(activity)
            return true
        }
        for (provider in CloudStorageProvider.values()) {
            if (provider.id != providerId) continue
            CloudStorageWebActivity.start(activity, provider)
            return true
        }
        return false
    }
}
