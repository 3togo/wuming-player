package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.Bilibili
import com.wuming.android.tv.utils.Notify
import com.wuming.android.tv.utils.ProviderQrLauncher
import com.wuming.android.tv.utils.QRCode
import com.wuming.android.tv.utils.QrSizing
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.Task
import com.github.catvod.net.OkHttp
import com.github.catvod.net.PersistentCookieJar
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import java.lang.ref.WeakReference
import java.util.concurrent.Future

/** Bilibili scan login; BaseQrLoginDialog owns measured QR sizing and view lifetime. */
class BilibiliLoginDialog(
    activity: Activity?,
    private var onSuccess: Runnable?,
    private var onCancel: Runnable?,
    private val generateUrl: String,
    private val pollUrl: String,
) : BaseQrLoginDialog(activity) {
    private constructor(activity: Activity?, onSuccess: Runnable?, onCancel: Runnable?) :
        this(activity, onSuccess, onCancel, GENERATE_URL, POLL_URL)

    private val pollRunnable =
        Runnable {
            poll()
        }
    private var task: Future<*>? = null
    private var call: Call? = null
    private var qrUrl = ""
    private var qrKey = ""
    private var autoAfterGenerate = false
    private val alreadyLoggedIn = PersistentCookieJar.get().hasLoginCookies(LOGIN_CHECK_URL)

    override fun title(): CharSequence? = ResUtil.getString(R.string.bilibili_login_title)

    override fun initialStatus(): CharSequence? =
        ResUtil.getString(if (alreadyLoggedIn) R.string.bilibili_already_logged_in else R.string.bilibili_login_starting)

    override fun primaryLabel(): Int = R.string.bilibili_login_refresh

    override fun secondaryLabel(): Int = R.string.bilibili_login_auto

    override fun onPrimary() = generate()

    override fun onSecondary() = onAuto()

    override fun onStart() {
        if (alreadyLoggedIn) hideQrArea() else generate()
    }

    override fun onStop() {
        getActivity()?.let {
            ProviderQrLauncher.discardSaved(it)
        }
        resetRequest()
    }

    override fun onLifecycleEnd() {
        onSuccess = null
        onCancel = null
    }

    override fun onFinish(success: Boolean) {
        val completion = if (success) onSuccess else onCancel
        onSuccess = null
        onCancel = null
        completion?.run()
    }

    private fun generate() {
        if (!isActive(getActivity())) return
        val id = resetRequest()
        setStatus(R.string.bilibili_login_starting)
        setQr(null)
        val ownerRef = WeakReference(this)
        val request = OkHttp.newCall(generateUrl, headers())
        call = request
        task =
            Task.submitLarge {
                var bitmap: Bitmap? = null
                var key = ""
                var url = ""
                try {
                    request.execute().use { response ->
                        check(response.isSuccessful)
                        val data = data(response.body.string())
                        key = data.get("qrcode_key")?.asString ?: ""
                        url = data.get("url")?.asString ?: ""
                        bitmap = QRCode.getBitmapPx(url, QrSizing.encodePx(), 4)
                    }
                } catch (ignored: Exception) {
                }
                val resultBitmap = bitmap
                val resultKey = key
                val resultUrl = url
                App.post {
                    val owner = ownerRef.get()
                    if (owner == null) recycle(resultBitmap) else owner.onGenerated(id, resultKey, resultUrl, resultBitmap)
                }
            }
    }

    private fun onGenerated(
        id: Int,
        key: String,
        url: String,
        bitmap: Bitmap?,
    ) {
        if (!isCurrent(id)) {
            recycle(bitmap)
            return
        }
        task = null
        call = null
        if (key.isEmpty() || bitmap == null) {
            autoAfterGenerate = false
            recycle(bitmap)
            setStatus(R.string.bilibili_login_failed)
            setQr(null)
            return
        }
        qrKey = key
        qrUrl = url
        setQr(bitmap)
        setStatus(R.string.bilibili_login_waiting)
        App.post(pollRunnable, POLL_DELAY_MS)
        if (autoAfterGenerate) {
            autoAfterGenerate = false
            onAuto()
        }
    }

    private fun onAuto() {
        val activity = getActivity()
        if (!isActive(activity)) return
        if (qrKey.isEmpty()) {
            if (!autoAfterGenerate) {
                autoAfterGenerate = true
                generate()
            }
            return
        }
        val qr = captureQr()
        if (qr == null) {
            Notify.show(R.string.cloud_qr_not_ready)
            return
        }
        val saved = ProviderQrLauncher.save(activity, qr, ProviderQrLauncher.Target.BILIBILI)
        recycle(qr)
        if (saved == null) {
            Notify.show(R.string.cloud_qr_save_failed)
            return
        }
        if (!ProviderQrLauncher.open(activity, qrUrl, true)) Notify.show(R.string.cloud_qr_open_failed)
    }

    private fun poll() {
        if (qrKey.isEmpty() || dialog?.isShowing != true) return
        val id = requestId
        val ownerRef = WeakReference(this)
        val request = OkHttp.newCall(pollUrl + Uri.encode(qrKey), headers())
        call = request
        task =
            Task.submitLarge {
                var code = Int.MIN_VALUE
                try {
                    request.execute().use { response ->
                        check(response.isSuccessful)
                        code = data(response.body.string()).get("code")?.asInt ?: Int.MIN_VALUE
                    }
                } catch (ignored: Exception) {
                }
                val resultCode = code
                App.post {
                    ownerRef.get()?.onPolled(id, resultCode)
                }
            }
    }

    private fun onPolled(
        id: Int,
        code: Int,
    ) {
        if (!isCurrent(id)) return
        task = null
        call = null
        when (code) {
            0 -> {
                if (!PersistentCookieJar.get().hasLoginCookies(LOGIN_CHECK_URL)) {
                    setStatus(R.string.bilibili_login_failed)
                    return
                }
                setStatus(R.string.bilibili_login_success)
                dismiss(true)
            }

            86090 -> {
                setStatus(R.string.bilibili_login_confirm)
                App.post(pollRunnable, POLL_DELAY_MS)
            }

            86101 -> {
                setStatus(R.string.bilibili_login_waiting)
                App.post(pollRunnable, POLL_DELAY_MS)
            }

            86038 -> {
                qrKey = ""
                setStatus(R.string.bilibili_login_expired)
            }

            else -> {
                qrKey = ""
                setStatus(R.string.bilibili_login_failed)
            }
        }
    }

    private fun resetRequest(): Int {
        requestId++
        qrKey = ""
        qrUrl = ""
        App.removeCallbacks(pollRunnable)
        call?.cancel()
        call = null
        task?.cancel(true)
        task = null
        return requestId
    }

    private fun isCurrent(id: Int): Boolean = id == requestId && dialog?.isShowing == true && isActive(getActivity())

    companion object {
        private const val GENERATE_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        private const val POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key="
        private const val POLL_DELAY_MS = 2000L
        private const val LOGIN_CHECK_URL = "https://www.bilibili.com/"

        @JvmStatic fun show(
            activity: Activity?,
            onSuccess: Runnable?,
            onCancel: Runnable?,
        ): BilibiliLoginDialog = show(activity, onSuccess, onCancel, false)

        @JvmStatic fun show(
            activity: Activity?,
            onSuccess: Runnable?,
            onCancel: Runnable?,
            assisted: Boolean,
        ): BilibiliLoginDialog =
            BilibiliLoginDialog(activity, onSuccess, onCancel).also {
                it.autoAfterGenerate = assisted
                it.show()
            }

        private fun data(response: String): JsonObject {
            val root = runCatching { JsonParser.parseString(response).asJsonObject }.getOrNull()
                ?: throw IllegalArgumentException("Invalid JSON response")
            check(root.has("code") && root.get("code")?.asInt == 0)
            return root.getAsJsonObject("data") ?: throw IllegalArgumentException("Missing data field")
        }

        private fun headers(): Map<String, String> =
            hashMapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 Chrome/120 Safari/537.36",
                "Referer" to "https://www.bilibili.com/",
            )
    }
}
