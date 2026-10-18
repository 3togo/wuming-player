package com.wuming.android.tv.utils

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.util.TypedValue
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.setting.DisplaySettings
import com.wuming.tv.core.design.TextRole
import com.wuming.tv.core.design.TypographyPolicy

/** Android port for semantic typography. Layout AutoSize must never determine mobile text size. */
object TextScale {
    /** Synchronous inflation/binding hook; does not install another root observer. */
    @JvmStatic fun bind(root: View) = walk(root)

    /** Explicit playback-overlay variant; ordinary controls never scale with geometry. */
    @JvmStatic fun setPlayerOverlayScale(view: TextView, scale: Float) {
        view.setTag(R.id.tag_typography_overlay_scale, scale.takeIf { it.isFinite() && it > 0f } ?: 1f)
        bind(view)
    }

    /** AlertDialog creates its standard text internally, outside the tagged XML layouts. */
    @JvmStatic fun applyDialog(root: View) {
        root.setTag(R.id.tag_typography_dialog, true)
        apply(root)
    }

    @JvmStatic fun apply(root: View?) {
        if (root == null) return
        walk(root)
        if (root.getTag(R.id.tag_typography_observer) != null) return
        // Recycler rows, asynchronously loaded details, and dialog contents join after inflation.
        // Global layout covers those additions without scanning every rendered video frame.
        val listener = ViewTreeObserver.OnGlobalLayoutListener { walk(root) }
        root.setTag(R.id.tag_typography_observer, listener)
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View) {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
            override fun onViewAttachedToWindow(view: View) {
                view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                view.viewTreeObserver.addOnGlobalLayoutListener(listener)
                walk(view)
            }
        })
    }

    @JvmStatic fun resetAndApply(root: View?) {
        if (root == null) return
        clearGuard(root)
        apply(root)
    }

    /** Assign runtime-created text the same role as its XML counterparts, without extra observers. */
    @JvmStatic fun setRole(view: TextView, role: TextRole) {
        view.setTag(R.id.tag_typography_role, role.name)
        bind(view)
    }

    @JvmStatic fun pixels(view: View, role: TextRole): Float = pixels(view.context, role)

    @JvmStatic fun pixels(context: Context, role: TextRole): Float {
        return resolvedSizes(context).getValue(role)
    }

    private fun resolvedSizes(context: Context): Map<TextRole, Float> {
        val resources = context.resources
        val configuration = resources.configuration
        val fontScale = configuration.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val television = Util.isLeanback()
        val nativeDensity = configuration.densityDpi / 160f
        // A configuration context supplies Android's nonlinear SP converter. Copy the
        // metrics so AutoSize's mutable layout density never becomes our text density.
        val nativeResources = context.createConfigurationContext(Configuration(configuration)).resources
        val metrics = DisplayMetrics().also { it.setTo(nativeResources.displayMetrics) }
        metrics.density = nativeDensity
        @Suppress("DEPRECATION")
        metrics.scaledDensity = nativeDensity * fontScale // Legacy fallback, not the Android 14 curve.
        val density = if (television) {
            // Preserve the distance-viewing 960x540 TV contract, independently of mutable metrics.
            val metrics = resources.displayMetrics
            ViewportScale.uniformScale(metrics.widthPixels, metrics.heightPixels, 960f, 540f)
        } else {
            configuration.densityDpi / 160f
        }
        val tabletScale = if (television) 1f else TypographyPolicy.tabletScale(
            configuration.smallestScreenWidthDp.toFloat(),
            DisplaySettings.getUiTextScaleBaseline(), DisplaySettings.getUiTextScaleCap(),
        )
        return TextRole.entries.associateWith { role ->
            val sp = TypographyPolicy.logicalSize(role, television) * tabletScale
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics) / nativeDensity * density
        }
    }

    private fun clearGuard(view: View) {
        view.setTag(R.id.tag_text_scaled, null)
        if (view is ViewGroup) for (i in 0 until view.childCount) clearGuard(view.getChildAt(i))
    }

    private fun walk(root: View) {
        val sizes = resolvedSizes(root.context)
        walk(root, sizes, root.getTag(R.id.tag_typography_dialog) == true)
    }

    private fun walk(view: View, sizes: Map<TextRole, Float>, dialog: Boolean) {
        ControlSpacing.apply(view)
        if (view is RecyclerView && view.getTag(R.id.tag_typography_recycler) == null) {
            // RecyclerView can attach rows during scrolling without a window-wide layout.
            view.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(child: View) = walk(child, resolvedSizes(child.context), dialog)
                override fun onChildViewDetachedFromWindow(child: View) = Unit
            })
            view.setTag(R.id.tag_typography_recycler, true)
        }
        // Toolbar creates its title/subtitle internally, so those views cannot carry XML tags.
        if (view is Toolbar) for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is TextView && child.getTag(R.id.tag_typography_role) == null) {
                val role = when {
                    !view.title.isNullOrEmpty() && TextUtils.equals(child.text, view.title) -> TextRole.TITLE
                    !view.subtitle.isNullOrEmpty() && TextUtils.equals(child.text, view.subtitle) -> TextRole.LABEL
                    else -> null
                }
                if (role != null) child.setTag(R.id.tag_typography_role, role.name)
            }
        }
        if (view is TextView) {
            val role = TextRole.fromName(view.getTag(R.id.tag_typography_role) as? String)
                ?: if (dialog) when (view.id) {
                    androidx.appcompat.R.id.alertTitle -> TextRole.TITLE
                    android.R.id.button1, android.R.id.button2, android.R.id.button3 -> TextRole.LABEL
                    else -> if (view.id != View.NO_ID &&
                        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() == "alertTitle"
                    ) TextRole.TITLE else TextRole.BODY
                } else null
            if (role != null) {
                val overlayScale = view.getTag(R.id.tag_typography_overlay_scale) as? Float ?: 1f
                val size = sizes.getValue(role) * overlayScale
                // The actual view is authoritative: cached targets cannot conceal a setter override.
                if (kotlin.math.abs(view.textSize - size) > 0.01f) {
                    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                }
                view.setTag(R.id.tag_text_scaled, size)
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), sizes, dialog)
    }
}
