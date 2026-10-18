package com.wuming.android.tv.utils

import android.content.Context
import com.wuming.android.tv.R

object DepotUtil {
    @JvmStatic
    fun getDepotText(context: Context): String {
        val info = DepotManager.getDepotInfo(context)
        return context.getString(R.string.setting_depot_updated, info.vodCount, info.liveCount)
    }
}
