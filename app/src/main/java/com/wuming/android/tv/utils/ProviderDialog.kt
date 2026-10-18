package com.wuming.android.tv.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.google.android.material.button.MaterialButton
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Host wording for provider-owned LoginShow dialogs, restricted to non-activity roots. */
class ProviderDialog private constructor() {
    companion object {
        private const val TAG = "ProviderDialog"
        private val RETRY_DELAYS_MS = intArrayOf(80, 160, 320, 640, 1000)
        private const val QR_SCAN_INTERVAL_MS = 400L
        private const val QR_SCAN_MAX_ATTEMPTS = 750 // Five minutes.
        private const val AUTO_LOGIN_TAG = "tvbox_provider_auto_login"
        private val sApiExempted = AtomicBoolean(false)
        private val sAttempt = AtomicInteger(0)
        private val sQrGeneration = AtomicInteger(0)
        private val sQrAttempt = AtomicInteger(0)
        private val sQrDecoding = AtomicBoolean(false)
        private val RELABEL =
            Runnable {
                tryRelabel()
            }
        private val SCAN_QR =
            Runnable {
                tryQrHandoff()
            }

        @Volatile private var sLastQrPayload = ""

        @JvmStatic fun relabelLoginDismissButton() {
            sAttempt.set(0)
            App.post(RELABEL, RETRY_DELAYS_MS[0].toLong())
        }

        /** The provider retains session ownership and polling; no cookies/tokens are copied. */
        @JvmStatic fun startLoginQrHandoff() {
            val generation = sQrGeneration.incrementAndGet()
            sQrAttempt.set(0)
            sQrDecoding.set(false)
            sLastQrPayload = ""
            // Ignore a stale QR left in a reopened provider dialog before the new action.
            val existing = findProviderQrBitmap()
            if (existing == null) {
                App.post(SCAN_QR, QR_SCAN_INTERVAL_MS)
                return
            }
            sQrDecoding.set(true)
            Task.executor().execute {
                val stalePayload = ProviderQrLauncher.decode(existing)
                App.post {
                    if (generation == sQrGeneration.get()) {
                        sLastQrPayload = stalePayload
                        sQrDecoding.set(false)
                        App.post(SCAN_QR, QR_SCAN_INTERVAL_MS)
                    }
                }
            }
        }

        @JvmStatic fun stopLoginQrHandoff() {
            sQrGeneration.incrementAndGet()
            sQrAttempt.set(0)
            sQrDecoding.set(false)
            sLastQrPayload = ""
            App.removeCallbacks(SCAN_QR)
        }

        private fun tryRelabel() {
            val relabeled = setDismissLabel()
            val autoButtons = addAutoLoginButtons()
            if (relabeled && autoButtons) return
            val attempt = sAttempt.incrementAndGet()
            if (attempt < RETRY_DELAYS_MS.size) App.post(RELABEL, RETRY_DELAYS_MS[attempt].toLong())
        }

        private fun addAutoLoginButtons(): Boolean {
            val activity = App.activity() ?: return false
            if (activity.isFinishing) return false
            try {
                val loggedOut = ArrayList<Button>()
                val activityRoot = activity.window.decorView
                for (root in windowRoots()) {
                    if (root === activityRoot) continue
                    TvDialogFocus.install(root)
                    findLoggedOutButtons(root, loggedOut)
                }
                for (login in loggedOut) addAutoLoginButton(activity, login)
                return loggedOut.isNotEmpty()
            } catch (error: Throwable) {
                if (sAttempt.get() >= RETRY_DELAYS_MS.size - 1) AppLog.w(TAG, "Unable to add provider auto-login buttons", error)
                return false
            }
        }

        private fun findLoggedOutButtons(
            view: View,
            result: MutableList<Button>,
        ) {
            if (!view.isShown) return
            if (view is Button && isLoggedOut(view.text)) result.add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) findLoggedOutButtons(view.getChildAt(index), result)
        }

        private fun isLoggedOut(text: CharSequence?): Boolean {
            if (text == null) return false
            val value = text.toString().replace(" ", "").lowercase(Locale.ROOT)
            return value.contains("未登录") || value.contains("未登入") || value.contains("notloggedin") || value.contains("signedout")
        }

        private fun addAutoLoginButton(
            activity: Activity,
            login: Button,
        ) {
            val row = login.parent as? LinearLayout ?: return
            for (index in 0 until row.childCount) if (AUTO_LOGIN_TAG == row.getChildAt(index).tag) return
            val source = login.layoutParams as? LinearLayout.LayoutParams ?: return
            val auto = MaterialButton(activity)
            auto.tag = AUTO_LOGIN_TAG
            auto.setText(R.string.cloud_qr_auto)
            auto.contentDescription = activity.getString(R.string.cloud_qr_auto)
            auto.setTextColor(ContextCompat.getColor(activity, R.color.white))
            TextScale.setRole(auto, com.wuming.tv.core.design.TextRole.LABEL)
            auto.isAllCaps = false
            auto.setSingleLine(true)
            auto.minWidth = 0
            auto.minimumWidth = 0
            auto.insetTop = 0
            auto.insetBottom = 0
            auto.cornerRadius = ResUtil.getDimensionPixelSize(R.dimen.vs_6)
            auto.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.blue_500))
            val padding = ResUtil.getDimensionPixelSize(R.dimen.vs_6)
            auto.setPadding(padding, 0, padding, 0)
            val height = source.height
            source.width = 0
            source.weight = 2.5f
            login.layoutParams = source
            val params = LinearLayout.LayoutParams(0, height, 1f)
            params.marginStart = ResUtil.getDimensionPixelSize(R.dimen.vs_8)
            row.addView(auto, row.indexOfChild(login) + 1, params)
            auto.setOnClickListener { view ->
                view.isEnabled = false
                startLoginQrHandoff()
                login.performClick()
                App.post({
                    view.isEnabled = true
                }, 1200L)
            }
        }

        private fun tryQrHandoff() {
            val generation = sQrGeneration.get()
            if (sQrAttempt.getAndIncrement() >= QR_SCAN_MAX_ATTEMPTS) return
            val bitmap = findProviderQrBitmap()
            if (bitmap != null && sQrDecoding.compareAndSet(false, true)) {
                Task.executor().execute {
                    val payload = ProviderQrLauncher.decode(bitmap)
                    val target = ProviderQrLauncher.targetForPayload(payload)
                    val fresh = generation == sQrGeneration.get() && payload.isNotEmpty() && payload != sLastQrPayload
                    val qrSaved = fresh && target != null && ProviderQrLauncher.save(App.get(), bitmap, target) != null
                    App.post {
                        sQrDecoding.set(false)
                        if (generation != sQrGeneration.get() || payload.isEmpty() || payload == sLastQrPayload) return@post
                        sLastQrPayload = payload
                        if (!qrSaved) {
                            Notify.show(R.string.cloud_qr_save_failed)
                            return@post
                        }
                        if (ProviderQrLauncher.open(App.activeActivity(), payload, qrSaved)) sQrGeneration.incrementAndGet()
                    }
                }
            }
            if (generation == sQrGeneration.get()) App.post(SCAN_QR, QR_SCAN_INTERVAL_MS)
        }

        @SuppressLint("PrivateApi")
        private fun findProviderQrBitmap(): Bitmap? {
            val activity = App.activity() ?: return null
            if (activity.isFinishing) return null
            try {
                for (root in windowRoots()) {
                    if (root === activity.window.decorView) continue
                    val bitmap = findQrBitmap(root)
                    if (bitmap != null) return bitmap
                }
            } catch (error: Throwable) {
                AppLog.w(TAG, "Unable to inspect provider QR dialog", error)
            }
            return null
        }

        private fun findQrBitmap(view: View): Bitmap? {
            if (!view.isShown) return null
            if (view is ImageView) {
                val drawable = view.drawable
                val bitmap = bitmapFrom(drawable)
                if (isQrCandidate(bitmap)) return bitmap
                if (drawable !is BitmapDrawable && bitmap != null) bitmap.recycle()
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    val bitmap = findQrBitmap(view.getChildAt(index))
                    if (bitmap != null) return bitmap
                }
            }
            return null
        }

        private fun isQrCandidate(bitmap: Bitmap?): Boolean {
            if (bitmap == null) return false
            return bitmap.width >= 160 && bitmap.height >= 160 &&
                Math.abs(bitmap.width - bitmap.height) <= maxOf(bitmap.width, bitmap.height) / 5
        }

        private fun bitmapFrom(drawable: Drawable?): Bitmap? {
            if (drawable == null) return null
            if (drawable is BitmapDrawable) return drawable.bitmap
            val width = drawable.intrinsicWidth
            val height = drawable.intrinsicHeight
            if (width <= 0 || height <= 0 || width > 1600 || height > 1600) return null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            return bitmap
        }

        @SuppressLint("PrivateApi")
        private fun setDismissLabel(): Boolean {
            val activity = App.activity() ?: return false
            if (activity.isFinishing) return false
            try {
                ensureHiddenApiExempted()
                val activityRoot = activity.window.decorView
                for (root in windowRoots()) {
                    if (root === activityRoot) continue
                    val button = root.findViewById<View>(android.R.id.button2) as? TextView ?: continue
                    if (!button.isShown) continue
                    button.setText(R.string.dialog_close)
                    button.contentDescription = activity.getString(R.string.dialog_close)
                    return true
                }
            } catch (error: Throwable) {
                if (sAttempt.get() >= RETRY_DELAYS_MS.size - 1) AppLog.w(TAG, "Unable to relabel provider login dialog", error)
            }
            return false
        }

        @SuppressLint("PrivateApi")
        private fun ensureHiddenApiExempted() {
            // VM exemption is process-global; don't repeat the reflective call on every pass.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                sApiExempted.compareAndSet(false, true)
            ) {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/view/WindowManagerGlobal;")
            }
        }

        @Throws(Exception::class)
        private fun windowRoots(): List<View> {
            ensureHiddenApiExempted()
            val type = Class.forName("android.view.WindowManagerGlobal")
            val getInstance =
                type.getDeclaredMethod("getInstance").also {
                    it.isAccessible = true
                }
            val manager = getInstance.invoke(null)
            val viewsField =
                type.getDeclaredField("mViews").also {
                    it.isAccessible = true
                }
            val roots = viewsField.get(manager) as? List<*> ?: return emptyList()
            return roots
                .stream()
                .filter {
                    it is View
                }.map {
                    it as View
                }.toList()
        }
    }
}
