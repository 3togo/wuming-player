package com.wuming.android.tv.utils

import android.Manifest
import android.os.Build
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.wuming.android.tv.impl.PermissionCallback
import com.permissionx.guolindev.PermissionX
import java.util.function.Consumer

open class PermissionUtil {
    companion object {
        /** Casting and LAN sources require Nearby devices access on Android 17+. */
        @JvmStatic fun requestLocalNetwork(
            activity: FragmentActivity,
            callback: Consumer<Boolean>,
        ) {
            if (Build.VERSION.SDK_INT < 37) {
                callback.accept(true)
                return
            }
            PermissionX.init(activity).permissions(Manifest.permission.ACCESS_LOCAL_NETWORK)
                .request(PermissionCallback(callback))
        }

        @JvmStatic fun requestAudio(
            activity: FragmentActivity,
            callback: Consumer<Boolean>,
        ) {
            PermissionX.init(activity).permissions(Manifest.permission.RECORD_AUDIO).request(PermissionCallback(callback))
        }

        /** Android 11+ app-specific directories need no storage permission request. */
        @JvmStatic fun requestFile(
            activity: FragmentActivity,
            callback: Consumer<Boolean>,
        ) {
            if (Build.VERSION.SDK_INT >= 30) {
                callback.accept(true)
                return
            }
            PermissionX.init(activity).permissions(Manifest.permission.WRITE_EXTERNAL_STORAGE).request(PermissionCallback(callback))
        }

        @JvmStatic fun requestFile(
            fragment: Fragment,
            callback: Consumer<Boolean>,
        ) {
            if (Build.VERSION.SDK_INT >= 30) {
                callback.accept(true)
                return
            }
            PermissionX.init(fragment).permissions(Manifest.permission.WRITE_EXTERNAL_STORAGE).request(PermissionCallback(callback))
        }
    }
}
