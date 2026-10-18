package com.wuming.android.tv.ui.base

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import androidx.annotation.NonNull
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.setting.UiTheme
import com.wuming.android.tv.utils.Util
import com.wuming.android.tv.utils.UiSafe
import com.wuming.android.tv.utils.ViewportScale
import me.jessyan.autosize.AutoSizeCompat

abstract class BaseActivity : BaseActivityCore() {

    private var lastOrientation: Int = 0
    private var resourcesHacked: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applyTo(this)
        super.onCreate(savedInstanceState)
        Util.hideSystemUI(this)
        lastOrientation = resources.configuration.orientation
    }

    override fun onPostCreate() {}

    protected open fun notifyItemChanged(view: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        UiSafe.notifyDataSetChanged(view, adapter)
    }

    private fun applyResourceHacks(resources: Resources) {
        try {
            val width = resources.displayMetrics.widthPixels
            val height = resources.displayMetrics.heightPixels
            if (ViewportScale.useWidthForTv(width, height)) {
                AutoSizeCompat.autoConvertDensityBaseOnWidth(resources, ViewportScale.TV_DESIGN_WIDTH_DP)
            } else {
                AutoSizeCompat.autoConvertDensityBaseOnHeight(resources, ViewportScale.TV_DESIGN_HEIGHT_DP)
            }
            resourcesHacked = true
        } catch (e: Exception) {
            android.util.Log.w("BaseActivity", "Failed to apply resource hacks", e)
        }
    }

    override fun getResources(): Resources {
        val resources = super.getResources()
        if (!resourcesHacked || resources.configuration.orientation != lastOrientation) {
            lastOrientation = resources.configuration.orientation
            resourcesHacked = false
            applyResourceHacks(resources)
        }
        return resources
    }

    override fun onConfigurationChanged(@NonNull newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resourcesHacked = false
        Util.hideSystemUI(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Util.hideSystemUI(this)
    }
}
