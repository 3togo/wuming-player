package com.wuming.tv.guardspider

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/**
 * Translucent launcher used by the desktop bridge to bring up the spider helper service on the
 * device: `adb shell am start -n com.wuming.tv.guardspider/.MainActivity`. It starts the foreground
 * service after resolving local-network permission, then finishes. The service runs the
 * GMS-dependent provider with real Google Play Services.
 */
open class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), 37)
            return
        }
        startHelper()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 37) startHelper()
    }

    private fun startHelper() {
        // Internet-only providers can still run when local-network access is denied.
        startForegroundService(Intent(this, SpiderHelperService::class.java))
        finish()
    }
}
