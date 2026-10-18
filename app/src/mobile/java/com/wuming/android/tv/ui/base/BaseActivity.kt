package com.wuming.android.tv.ui.base

import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.DisplayCutout
import android.view.ViewGroup
import androidx.annotation.NonNull
import androidx.core.view.WindowCompat
import com.wuming.android.tv.setting.UiTheme
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.ViewportScale
import me.jessyan.autosize.AutoSizeCompat
import me.jessyan.autosize.internal.CustomAdapt

abstract class BaseActivity : BaseActivityCore(), CustomAdapt {

    private var lastOrientation: Int = 0
    private var adaptedOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    private var resourcesHacked: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        UiTheme.applyTo(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lastOrientation = resources.configuration.orientation
    }

    override fun onPostCreate() {}

    override fun getResources(): Resources {
        val resources = super.getResources()
        val orientation = resources.configuration.orientation
        if (!resourcesHacked || orientation != adaptedOrientation) {
            adaptedOrientation = orientation
            resourcesHacked = false
            try {
                applyAutoSize(resources)
            } catch (e: Exception) {
                android.util.Log.w("BaseActivity", "Failed to apply auto size", e)
            }
            resourcesHacked = true
        }
        return resources
    }

    private fun applyAutoSize(resources: Resources) {
        val config = resources.configuration
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            AutoSizeCompat.autoConvertDensityBaseOnHeight(
                resources, getSizeInDp()
            )
        } else {
            AutoSizeCompat.autoConvertDensityBaseOnWidth(
                resources, getSizeInDp()
            )
        }
    }

    override fun isBaseOnWidth(): Boolean =
        super.getResources().configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    override fun getSizeInDp(): Float = ViewportScale.MOBILE_SHORT_EDGE_DESIGN_DP

    override fun onConfigurationChanged(@NonNull newConfig: Configuration) {
        val previousOrientation = lastOrientation
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != previousOrientation) {
            lastOrientation = newConfig.orientation
            resourcesHacked = false
            onOrientationChanged(newConfig)
        }
    }

    protected open fun onOrientationChanged(newConfig: Configuration) {}

    protected fun setPadding(layout: ViewGroup) {
        setPadding(layout, false)
    }

    protected fun setPadding(layout: ViewGroup, leftOnly: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cutout: DisplayCutout? = ResUtil.getDisplay(this).cutout
        if (cutout == null) return
        val top = cutout.safeInsetTop
        val left = cutout.safeInsetLeft
        val right = cutout.safeInsetRight
        val bottom = cutout.safeInsetBottom
        val padding = left or right or top or bottom
        layout.setPadding(padding, 0, if (leftOnly) 0 else padding, 0)
    }

    protected fun noPadding(layout: ViewGroup) {
        layout.setPadding(0, 0, 0, 0)
    }

    private fun enableEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}
