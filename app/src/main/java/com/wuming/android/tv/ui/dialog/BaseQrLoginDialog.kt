package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.LifecycleCleanup
import com.wuming.android.tv.utils.QRCode
import com.wuming.android.tv.utils.QrSizing
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.wuming.android.tv.utils.Util
import java.lang.ref.WeakReference

/**
 * Shared QR login window. The QR square is measured, never fixed: estimate first,
 * then fit to the laid-out window in at most four passes. Fixed dimensions previously
 * clipped QR rows underneath the TV dialog buttons. Subclasses own only network flow.
 */
abstract class BaseQrLoginDialog protected constructor(
    activity: Activity?,
) {
    @JvmField protected val activityRef = WeakReference(activity)

    @JvmField protected var dialog: AlertDialog? = null

    @JvmField protected var qrView: ImageView? = null

    @JvmField protected var statusView: TextView? = null

    @JvmField protected var qrBitmap: Bitmap? = null

    @JvmField protected var lifecycleCleanup: LifecycleCleanup? = null

    @JvmField protected var requestId = 0

    @JvmField protected var completed = false
    private var progressView: ProgressBar? = null
    private var primaryButton: Button? = null
    private var secondaryButton: Button? = null
    private var qrSizePx = 0
    private var fitPasses = 0
    private var released = false

    protected abstract fun title(): CharSequence?

    protected abstract fun initialStatus(): CharSequence?

    @StringRes protected abstract fun primaryLabel(): Int

    @StringRes protected abstract fun secondaryLabel(): Int

    protected abstract fun onPrimary()

    protected abstract fun onSecondary()

    protected abstract fun onStart()

    /** Cancel every pending request/callback; must be idempotent. */
    protected abstract fun onStop()

    protected open fun onDismissed() {
    }

    protected open fun onFinish(success: Boolean) {
    }

    protected open fun onLifecycleEnd() {
    }

    protected open fun secondaryEnabledInitially(): Boolean = true

    protected open fun primaryEnabledInitially(): Boolean = true

    protected open fun onQrReady(qr: Bitmap) {
    }

    fun show() {
        val activity = activityRef.get()
        if (!isActive(activity)) return
        activity!!
        released = false
        qrSizePx = QrSizing.estimatePx(activity)
        dialog =
            TvAlertDialogBuilder(activity, R.style.DarkDialogStyle)
                .setTitle(title())
                .setView(buildContent(activity))
                .setPositiveButton(primaryLabel(), null)
                .setNeutralButton(secondaryLabel(), null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog!!.setOnShowListener { value ->
            val current = dialog
            if (value !== current || completed || !current.isShowing) return@setOnShowListener
            primaryButton = current.getButton(AlertDialog.BUTTON_POSITIVE)
            secondaryButton = current.getButton(AlertDialog.BUTTON_NEUTRAL)
            primaryButton!!.setOnClickListener {
                onPrimary()
            }
            secondaryButton!!.setOnClickListener {
                onSecondary()
            }
            current.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dismiss(false)
            }
            secondaryButton!!.isEnabled = secondaryEnabledInitially()
            primaryButton!!.isEnabled = primaryEnabledInitially()
            onStart()
        }
        dialog!!.setOnCancelListener {
            dismiss(false)
        }
        dialog!!.setOnDismissListener {
            onWindowDismissed()
        }
        dialog!!.show()
        if (activity is LifecycleOwner) {
            lifecycleCleanup =
                LifecycleCleanup(activity) {
                    onLifecycleEnd()
                    dismiss(false)
                }
        }
    }

    private fun buildContent(activity: Activity): LinearLayout {
        val content = LinearLayout(activity)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.setPadding(
            ResUtil.getDimensionPixelSize(R.dimen.vs_24),
            ResUtil.getDimensionPixelSize(R.dimen.vs_8),
            ResUtil.getDimensionPixelSize(R.dimen.vs_24),
            0,
        )
        statusView =
            TextView(activity).also {
                it.text = initialStatus()
                it.setTextColor(Color.WHITE)
                it.gravity = Gravity.CENTER
                com.wuming.android.tv.utils.TextScale.setRole(it, com.wuming.tv.core.design.TextRole.BODY)
                content.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        val progressSize = ResUtil.getDimensionPixelSize(R.dimen.vs_48)
        progressView =
            ProgressBar(activity).also {
                it.isIndeterminate = true
                val params = LinearLayout.LayoutParams(progressSize, progressSize)
                params.gravity = Gravity.CENTER_HORIZONTAL
                params.topMargin = ResUtil.getDimensionPixelSize(R.dimen.vs_16)
                content.addView(it, params)
            }
        qrView =
            ImageView(activity).also {
                it.adjustViewBounds = true
                it.scaleType = ImageView.ScaleType.CENTER_INSIDE
                it.setBackgroundColor(Color.WHITE)
                val params = LinearLayout.LayoutParams(qrSizePx, qrSizePx)
                params.gravity = Gravity.CENTER_HORIZONTAL
                params.topMargin = ResUtil.getDimensionPixelSize(R.dimen.vs_16)
                it.visibility = View.GONE
                content.addView(it, params)
            }
        return content
    }

    protected fun encodeQr(payload: String?): Bitmap? = QRCode.getBitmapPx(payload!!, QrSizing.encodePx(), 4)

    protected fun setQr(bitmap: Bitmap?) {
        val view = qrView
        if (view == null) {
            recycle(bitmap)
            return
        }
        view.setImageDrawable(null)
        recycleQr()
        qrBitmap = bitmap
        if (bitmap == null) {
            setLoading(true)
            return
        }
        view.setImageBitmap(bitmap)
        setLoading(false)
        fitPasses = 0
        scheduleFit()
        onQrReady(bitmap)
    }

    protected fun setLoading(loading: Boolean) {
        qrView?.visibility = if (loading) View.GONE else View.VISIBLE
        progressView?.visibility = if (loading) View.VISIBLE else View.GONE
    }

    protected fun hideQrArea() {
        qrView?.visibility = View.GONE
        progressView?.visibility = View.GONE
    }

    protected fun setStatus(resId: Int) {
        if (resId != 0) statusView?.setText(resId)
    }

    protected fun setStatus(text: CharSequence?) {
        if (text != null) statusView?.text = text
    }

    protected fun setSecondaryEnabled(enabled: Boolean) {
        secondaryButton?.isEnabled = enabled
    }

    protected fun setPrimaryEnabled(enabled: Boolean) {
        primaryButton?.isEnabled = enabled
    }

    protected fun captureQr(): Bitmap? {
        val view = qrView ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        return try {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        } catch (error: RuntimeException) {
            null
        } catch (error: OutOfMemoryError) {
            null
        }
    }

    private fun scheduleFit() {
        decorView()?.post {
            applyFit()
        }
    }

    private fun applyFit() {
        if (fitPasses++ >= MAX_FIT_PASSES) return
        val activity = activityRef.get()
        if (!isActive(activity)) return
        val decor = decorView() ?: return
        val view = qrView ?: return
        if (view.visibility != View.VISIBLE) return
        val current = view.height
        if (decor.height <= 0 || current <= 0) return
        val target = QrSizing.fitSquarePx(decor.height, current, QrSizing.maxWindowHeightPx(activity!!), qrSizePx)
        if (Math.abs(target - current) < QrSizing.MIN_ADJUST_PX) return
        qrSizePx = target
        QrSizing.applySquare(view, target)
        decor.post {
            applyFit()
        }
    }

    private fun decorView(): View? = dialog?.window?.decorView

    open fun dismiss() {
        dismiss(false)
    }

    protected fun dismiss(success: Boolean) {
        if (completed) return
        completed = true
        val current = dialog
        dialog = null
        current?.dismiss()
        // Release synchronously; the later looper-delivered dismiss callback is a no-op.
        onWindowDismissed()
        onFinish(success)
    }

    private fun onWindowDismissed() {
        if (released) return
        released = true
        requestId++
        dialog = null
        lifecycleCleanup?.close()
        lifecycleCleanup = null
        onStop()
        releaseViews()
        onDismissed()
    }

    private fun releaseViews() {
        qrView?.setImageDrawable(null)
        qrView = null
        statusView = null
        progressView = null
        primaryButton = null
        secondaryButton = null
        recycleQr()
    }

    protected fun recycleQr() {
        recycle(qrBitmap)
        qrBitmap = null
    }

    protected fun getActivity(): Activity? = activityRef.get()

    companion object {
        private const val MAX_FIT_PASSES = 4

        @JvmStatic protected fun isActive(activity: Activity?): Boolean = Util.isActive(activity)

        @JvmStatic protected fun recycle(bitmap: Bitmap?) {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }
}
