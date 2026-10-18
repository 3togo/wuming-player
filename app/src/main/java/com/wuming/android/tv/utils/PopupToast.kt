package com.wuming.android.tv.utils

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wuming.android.tv.R
import com.wuming.android.tv.App
import java.lang.ref.WeakReference
import java.util.ArrayDeque

class PopupToast private constructor() {

    private val mHandler: Handler = Handler(Looper.getMainLooper())
    @Volatile
    private var mViewRef: WeakReference<View>? = null
    @Volatile
    private var mParentRef: WeakReference<ViewGroup>? = null
    private val mDismissRunnable: Runnable = DismissRunnable(this)
    private val pending = ArrayDeque<String>()
    private var currentText: String? = null

    fun show(activity: Activity?, text: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val activityRef = WeakReference(activity)
            mHandler.post { show(activityRef.get(), text) }
            return
        }
        if (text == currentText || pending.contains(text)) return
        if (mViewRef?.get() != null || activity == null || activity.isFinishing || activity.isDestroyed) {
            if (pending.size >= 12) pending.removeFirst()
            pending.addLast(text)
            return
        }
        showNow(activity, text)
    }

    fun onActivityResumed(activity: Activity?) {
        if (activity == null) return
        val text = currentText
        if (text != null && mParentRef?.get() !== activity.window.decorView) {
            dismissOnMainThread()
            showNow(activity, text)
        } else if (mViewRef?.get() == null) {
            advance()
        }
    }

    private fun showNow(activity: Activity?, text: String) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        val decorView = activity.window.decorView as ViewGroup? ?: return
        currentText = text

        val view = LayoutInflater.from(activity).inflate(R.layout.view_notify, decorView, false)
        val tv = view.findViewById<TextView>(R.id.notify_popup)
        tv.text = text
        TextScale.apply(view)

        // Anchored to the bottom-end corner: clears the header, the D-pad focus
        // path, the player control row, and the subtitle band above the controls.
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        )
        val margin = ResUtil.getDimensionPixelSize(R.dimen.notify_edge_margin)
        params.setMargins(margin, margin, margin, margin)

        val isRtl = decorView.layoutDirection == View.LAYOUT_DIRECTION_RTL

        // A bare sibling on the decor must never take D-pad focus on TV.
        view.isFocusable = false
        view.isFocusableInTouchMode = false

        decorView.addView(view, params)
        mViewRef = WeakReference(view)
        mParentRef = WeakReference(decorView)

        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val layoutParams = target.layoutParams as FrameLayout.LayoutParams
            layoutParams.leftMargin = margin + if (isRtl) bars.left else 0
            layoutParams.rightMargin = margin + if (isRtl) 0 else bars.right
            layoutParams.bottomMargin = margin + maxOf(bars.bottom, ime.bottom)
            target.layoutParams = layoutParams
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)

        view.alpha = 0f
        view.translationY = margin / 2f
        view.animate().alpha(1f).translationY(0f).setDuration(160).start()

        mHandler.removeCallbacks(mDismissRunnable)
        mHandler.postDelayed(mDismissRunnable, getDuration(activity, text).toLong())
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mHandler.post { dismiss() }
            return
        }
        pending.clear()
        currentText = null
        dismissOnMainThread()
    }

    fun dismiss(owner: Activity?) {
        if (owner == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val ownerRef = WeakReference(owner)
            mHandler.post { dismiss(ownerRef.get()) }
            return
        }
        val parent = mParentRef?.get()
        if (owner.window != null && parent === owner.window.decorView) {
            currentText?.let { pending.addFirst(it) }
            currentText = null
            dismissOnMainThread()
        }
    }

    private fun advance() {
        dismissOnMainThread()
        currentText = null
        val activity = App.activeActivity() ?: return
        val text = pending.pollFirst() ?: return
        showNow(activity, text)
    }

    private fun dismissOnMainThread() {
        mHandler.removeCallbacks(mDismissRunnable)
        val view = mViewRef?.get()
        val parent = mParentRef?.get()
        if (view != null && parent != null) {
            try {
                view.animate().cancel()
                ViewCompat.setOnApplyWindowInsetsListener(view, null)
                parent.removeView(view)
            } catch (ignored: Exception) {
            }
        }
        mViewRef = null
        mParentRef = null
    }

    private class DismissRunnable(toast: PopupToast) : Runnable {
        private val ref: WeakReference<PopupToast> = WeakReference(toast)

        override fun run() {
            val toast = ref.get()
            toast?.advance()
        }
    }

    companion object {
        private const val SHORT_DISMISS_MS = 2500
        private const val LONG_DISMISS_MS = 4500

        @Volatile
        private var sInstance: PopupToast? = null

        @JvmStatic
        fun get(): PopupToast {
            if (sInstance == null) {
                synchronized(PopupToast::class.java) {
                    if (sInstance == null) {
                        sInstance = PopupToast()
                    }
                }
            }
            return sInstance!!
        }

        private fun getDuration(context: Context, text: String): Int {
            var duration = if (text.length > 80 || text.indexOf('\n') >= 0) LONG_DISMISS_MS else SHORT_DISMISS_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager?
                if (manager != null) {
                    duration = manager.getRecommendedTimeoutMillis(
                        duration,
                        AccessibilityManager.FLAG_CONTENT_TEXT
                    )
                }
            }
            return duration
        }
    }
}
