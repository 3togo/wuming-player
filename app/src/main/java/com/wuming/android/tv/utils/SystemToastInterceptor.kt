package com.wuming.android.tv.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.wuming.android.tv.App
import com.wuming.android.tv.BuildConfig
import com.wuming.android.tv.R
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

object SystemToastInterceptor {

    private const val TAG = "SystemToastInterceptor"
    private val sExempted = AtomicBoolean(false)
    private val receiverInstalled = AtomicBoolean(false)
    private const val EXTRA_TEXT = "text"

    // Providers can install a package-name compatibility hook on App. IPC must
    // always address the installed application, never that provider identity.
    private fun noticeAction() = BuildConfig.APPLICATION_ID + ".PROVIDER_NOTICE"

    @JvmStatic
    fun providerNoticeIntent(text: String): Intent = Intent(noticeAction())
        .setPackage(BuildConfig.APPLICATION_ID).putExtra(EXTRA_TEXT, text)

    private fun deliver(text: String) {
        if (App.isProviderProcess()) {
            // The isolated provider has no activity. Deliver to our UI process,
            // rather than letting Android place its toast over the page header.
            App.get().baseContext.sendBroadcast(providerNoticeIntent(text))
        } else {
            App.post { Notify.show(text) }
        }
    }

    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    @JvmStatic
    fun install() {
        if (!App.isProviderProcess() && receiverInstalled.compareAndSet(false, true)) {
            ContextCompat.registerReceiver(App.get(), object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != noticeAction()) return
                    val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                    Notify.show(text)
                }
            }, IntentFilter(noticeAction()), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                sExempted.compareAndSet(false, true)
            ) {
                HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/widget/Toast;",
                    "Landroid/widget/Toast\$TN;",
                    "Landroid/app/INotificationManager;"
                )
            }
            val getService = Toast::class.java.getDeclaredMethod("getService")
            getService.isAccessible = true
            val service = getService.invoke(null)
            if (service == null) throw IllegalStateException("Toast service is null")

            val serviceField = Toast::class.java.getDeclaredField("sService")
            serviceField.isAccessible = true
            val serviceInterface = Class.forName("android.app.INotificationManager")
            val proxy = Proxy.newProxyInstance(
                serviceInterface.classLoader,
                arrayOf(serviceInterface)
            ) { _, method, args -> invoke(service, method, args) }
            serviceField.set(null, proxy)
            AppLog.i(TAG, "Installed")
        } catch (e: Throwable) {
            AppLog.e(TAG, "Install failed", unwrap(e))
        }
    }

    @Throws(Throwable::class)
    private fun invoke(service: Any, method: Method, args: Array<Any>?): Any? {
        if (method.name == "enqueueTextToast" || method.name == "enqueueToast") {
            val text = findToastText(args)
            if (text.isNotEmpty()) {
                if (isUcLoginReminder(text)) {
                    AppLog.i(TAG, "Replaced MDrive UC login reminder with fallback context")
                    deliver(App.get().getString(R.string.opendrive_mdrive_uc_fallback))
                    return if (method.returnType == Boolean::class.javaPrimitiveType) true else null
                }
                AppLog.i(TAG, "Redirected a provider toast")
                deliver(text)
                return if (method.returnType == Boolean::class.javaPrimitiveType) true else null
            }
        }
        return try {
            method.invoke(service, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw unwrap(e)
        }
    }

    private fun findToastText(args: Array<Any>?): String {
        if (args == null) return ""
        for (arg in args) {
            if (arg is CharSequence) {
                val text = arg.toString().trim()
                if (text.isNotEmpty() && text != BuildConfig.APPLICATION_ID &&
                    text != App.get().packageName) return text
            }
        }
        for (arg in args) {
            val text = findCustomToastText(arg)
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    @JvmStatic
    fun isUcLoginReminder(text: String?): Boolean {
        if (text == null) return false
        val normalized = text.replace(Regex("\\s+"), "")
        val cloudDrive = normalized.contains("网盘") || normalized.contains("網盤")
        val ucBrowser = normalized.contains("UC浏览器") || normalized.contains("UC瀏覽器")
        val scanLogin = normalized.contains("扫码登录") || normalized.contains("掃碼登入")
            || normalized.contains("扫码登入") || normalized.contains("掃碼登录")
        return cloudDrive && ucBrowser && scanLogin
    }

    private fun findCustomToastText(value: Any?): String {
        if (value == null) return ""
        if (value is java.lang.ref.Reference<*>) {
            return findCustomToastText(value.get())
        }
        if (value is TextView) return value.text.toString().trim()
        if (value is ViewGroup) {
            for (i in 0 until value.childCount) {
                val text = findCustomToastText(value.getChildAt(i))
                if (text.isNotEmpty()) return text
            }
        }
        if (value is View) return ""
        val type = value.javaClass
        if (!type.name.startsWith("android.widget.Toast\$")) return ""
        var currentType: Class<*>? = type
        while (currentType != null) {
            try {
                val field = currentType.getDeclaredField("mNextView")
                field.isAccessible = true
                return findCustomToastText(field.get(value))
            } catch (e: NoSuchFieldException) {
                currentType = currentType.superclass
            } catch (e: Throwable) {
                AppLog.w(TAG, "Unable to inspect custom toast", unwrap(e))
                return ""
            }
        }
        return ""
    }

    private fun unwrap(throwable: Throwable): Throwable {
        var current = throwable
        while (current is InvocationTargetException && current.cause != null) {
            current = current.cause!!
        }
        return current
    }
}
