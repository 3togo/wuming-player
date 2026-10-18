package com.wuming.android.tv.utils

import android.net.TrafficStats
import android.view.View
import android.widget.TextView
import com.wuming.android.tv.App

open class Traffic {
    companion object {
        private val UID = App.get().applicationInfo.uid
        private const val UNIT_KB = " KB/s"
        private const val UNIT_MB = " MB/s"

        @Volatile private var lastTotalRxBytes = 0L

        @Volatile private var lastTimeStamp = 0L

        @Volatile private var initialized = false

        @JvmStatic fun setSpeed(view: TextView) {
            if (TrafficStats.getUidRxBytes(UID) == TrafficStats.UNSUPPORTED.toLong()) return
            view.visibility = View.VISIBLE
            view.text = getSpeed()
        }

        @JvmStatic @Synchronized
        private fun getSpeed(): String {
            val nowTimeStamp = System.currentTimeMillis()
            val nowTotalRxBytes = TrafficStats.getUidRxBytes(UID) / 1024
            val result: String
            if (!initialized) {
                result = String.format(java.util.Locale.getDefault(), "%5d", 0) + UNIT_KB
                initialized = true
            } else {
                val diff = maxOf(nowTotalRxBytes - lastTotalRxBytes, 0)
                val speed = diff * 1000 / maxOf(nowTimeStamp - lastTimeStamp, 1)
                result =
                    if (speed <
                        1000
                    ) {
                        String.format(java.util.Locale.getDefault(), "%5d", speed) + UNIT_KB
                    } else {
                        String.format(java.util.Locale.getDefault(), "%5.1f", speed / 1024f) + UNIT_MB
                    }
            }
            lastTimeStamp = nowTimeStamp
            lastTotalRxBytes = nowTotalRxBytes
            return result
        }

        @JvmStatic fun reset() {
            lastTotalRxBytes = 0
            lastTimeStamp = 0
            initialized = false
        }
    }
}
