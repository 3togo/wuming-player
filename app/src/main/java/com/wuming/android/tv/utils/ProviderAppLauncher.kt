package com.wuming.android.tv.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.wuming.android.tv.R
import com.wuming.android.tv.cloud.CloudStorageProvider

/** Opens a validated provider link or reviewed file-manager route in its official app. */
object ProviderAppLauncher {
    @JvmStatic fun supports(payload: String?): Boolean = ProviderQrLauncher.targetForPayload(payload) != null

    @JvmStatic fun open(
        activity: Activity?,
        payload: String?,
    ): Boolean {
        val target = ProviderQrLauncher.targetForPayload(payload)
        if (activity == null || activity.isFinishing || target == null) return false
        val link = Intent(Intent.ACTION_VIEW, Uri.parse(payload)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(target.packageName)
        try {
            if (link.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(link)
            } else {
                val launch =
                    activity.packageManager.getLaunchIntentForPackage(target.packageName)
                        ?: throw ActivityNotFoundException(target.packageName)
                activity.startActivity(launch)
            }
            return true
        } catch (
            error: ActivityNotFoundException,
        ) {
            return missing(activity, target.displayName)
        } catch (error: SecurityException) {
            return missing(activity, target.displayName)
        }
    }

    @JvmStatic fun open(
        activity: Activity?,
        provider: CloudStorageProvider?,
    ): Boolean {
        if (activity == null || activity.isFinishing || provider == null) return false
        val packageName = provider.androidPackage
        try {
            val launchUrl = provider.androidLaunchUrl
            val intent =
                if (launchUrl == null) {
                    activity.packageManager.getLaunchIntentForPackage(packageName)
                } else {
                    val link = Intent(Intent.ACTION_VIEW, Uri.parse(launchUrl)).addCategory(Intent.CATEGORY_BROWSABLE)
                    val launchActivity = provider.androidLaunchActivity
                    if (launchActivity ==
                        null
                    ) {
                        link.setPackage(packageName)
                    } else {
                        link.component = ComponentName(packageName, launchActivity)
                    }
                    link
                }
            if (intent == null) throw ActivityNotFoundException(packageName)
            activity.startActivity(intent)
            return true
        } catch (
            error: ActivityNotFoundException,
        ) {
            return missing(activity, provider.displayName)
        } catch (
            error: SecurityException,
        ) {
            return missing(activity, provider.displayName)
        }
    }

    private fun missing(
        activity: Activity,
        name: String,
    ): Boolean {
        Notify.show(activity.getString(R.string.cloud_storage_app_missing, name))
        return false
    }
}
