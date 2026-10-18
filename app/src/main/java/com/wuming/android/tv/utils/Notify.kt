package com.wuming.android.tv.utils

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.LayoutInflater

import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.databinding.ViewProgressBinding
import com.wuming.android.tv.utils.TvAlertDialogBuilder

import java.lang.ref.WeakReference
import java.util.ArrayDeque

object Notify {

    @JvmField
    val DEFAULT = "default"

    @JvmField
    val ID = 9527

    private const val PENDING_LIMIT = 12

    private var mDialogRef: WeakReference<AlertDialog>? = null
    private val mPending = ArrayDeque<String>()

    @JvmStatic
    fun createChannel() {
        val notifyMgr = NotificationManagerCompat.from(App.get())
        notifyMgr.createNotificationChannel(NotificationChannelCompat.Builder(DEFAULT, NotificationManagerCompat.IMPORTANCE_LOW).setName("TV").build())
    }

    @JvmStatic
    fun getError(resId: Int, e: Throwable): String {
        if (BeanUtils.isEmpty(e.message)) return ResUtil.getString(resId)
        return ResUtil.getString(resId) + "\n" + e.message
    }

    @JvmStatic
    fun show(notification: Notification) {
        if (ContextCompat.checkSelfPermission(App.get(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(App.get()).notify(ID, notification)
    }

    @JvmStatic
    fun show(resId: Int) {
        if (resId != 0) show(ResUtil.getString(resId))
    }

    @JvmStatic
    fun show(text: String?) {
        if (BeanUtils.isNotEmpty(text)) makeText(text!!)
    }

    @JvmStatic
    fun progress(context: Context) {
        dismiss()
        create(context)
    }

    @JvmStatic
    fun dismiss() {
        dismissDialog()
    }

    @JvmStatic
    fun onActivityResumed() {
        PopupToast.get().onActivityResumed(App.activity())
        flushPending()
    }

    @JvmStatic
    fun onActivityDestroyed(activity: Activity) {
        clearDialogsForActivity(activity)
    }

    private fun flushPending() {
        val pending = ArrayList<String>()
        synchronized(mPending) {
            while (true) {
                val head = mPending.pollFirst() ?: break
                // Startup/permission prompts can outlast a toast timeout. Retain
                // notices until an activity can actually display them.
                pending.add(head)
            }
        }
        for (text in pending) makeText(text)
    }

    private fun dismissDialog() {
        val dialog = mDialogRef?.get()
        if (dialog != null) {
            try {
                dialog.dismiss()
            } catch (e: Exception) {
                android.util.Log.w("Notify", "Failed to dismiss dialog", e)
            }
        }
        mDialogRef = null
    }

    private fun create(context: Context) {
        dismissDialog()
        val binding = ViewProgressBinding.inflate(LayoutInflater.from(context))
        val dialog = TvAlertDialogBuilder(context).setView(binding.root).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        mDialogRef = WeakReference(dialog)
    }

    private fun makeText(text: String) {
        val activity = App.activity()
        if (activity == null || !Util.isActive(activity)) {
            enqueuePending(text)
            return
        }
        PopupToast.get().show(activity, text)
    }

    private fun enqueuePending(text: String) {
        synchronized(mPending) {
            if (mPending.size >= PENDING_LIMIT) mPending.pollFirst()
            if (!mPending.contains(text)) mPending.addLast(text)
        }
    }

    private fun clearDialogsForActivity(activity: Activity?) {
        if (activity == null) return
        val progressDialog = mDialogRef?.get()
        if (progressDialog != null && belongsToActivity(progressDialog, activity)) {
            try { progressDialog.dismiss() } catch (e: Exception) {
                android.util.Log.w("Notify", "Failed to dismiss dialog for destroyed activity", e)
            }
            mDialogRef = null
        }
        PopupToast.get().dismiss(activity)
    }

    private fun belongsToActivity(dialog: Dialog, activity: Activity): Boolean {
        try {
            var ctx: Context? = dialog.context
            while (ctx is ContextWrapper) {
                if (ctx === activity) return true
                ctx = ctx.baseContext
            }
        } catch (e: Exception) {
            android.util.Log.d("Notify", "Failed to check dialog context", e)
        }
        return false
    }
}
