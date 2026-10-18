package com.wuming.android.tv.utils

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import android.webkit.WebView
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import org.json.JSONTokener
import java.lang.ref.WeakReference

/**
 * First-party login handoff. WebView captures are decoded only in memory; only a clean,
 * allowlisted QR is published to MediaStore, never account names or other page content.
 */
class OpenDriveQrHandoff
    @JvmOverloads
    constructor(
        activity: Activity?,
        webView: WebView?,
        providerId: String?,
        private val onProviderOpened: Runnable? = null,
    ) {
        private val activityRef = WeakReference(activity)
        private val webViewRef = WeakReference(webView)
        private val expectedTarget = ProviderQrLauncher.targetForProvider(providerId)
        private val handler = Handler(Looper.getMainLooper())
        private val scanRunnable =
            Runnable {
                scan()
            }

        @Volatile private var generation = 0
        @Volatile private var attempts = 0
        @Volatile private var decoding = false

        fun start() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post {
                    start()
                }
                return
            }
            cancelScan()
            ProviderQrLauncher.discardSaved(activityRef.get())
            val activity = activityRef.get()
            val webView = webViewRef.get()
            if (!alive(activity, webView) || expectedTarget == null) {
                AppLog.w(TAG, "Auto handoff is unavailable because the login view is detached")
                Notify.show(R.string.cloud_qr_not_ready)
                return
            }
            AppLog.i(TAG, "Starting Auto handoff for " + expectedTarget.name)
            attempts = 0
            val currentWebView = webView ?: return
            currentWebView.evaluateJavascript(SELECT_QR_LOGIN_SCRIPT, null)
            val currentGeneration = generation
            currentWebView.evaluateJavascript(EXTRACT_QR_IMAGE_SCRIPT) {
                onDomQrImage(currentGeneration, it)
            }
            Notify.show(R.string.cloud_qr_searching)
            handler.postDelayed(scanRunnable, FIRST_SCAN_DELAY_MS)
        }

        /** Direct API challenge avoids screen capture but retains allowlisting and cleanup. */
        fun start(payload: String?) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                handler.post {
                    start(payload)
                }
                return
            }
            cancelScan()
            ProviderQrLauncher.discardSaved(activityRef.get())
            val activity = activityRef.get()
            val webView = webViewRef.get()
            val actualTarget = ProviderQrLauncher.targetForPayload(payload)
            if (!alive(activity, webView) || expectedTarget == null || actualTarget != expectedTarget) {
                AppLog.w(TAG, "Rejected an invalid direct provider QR handoff")
                Notify.show(R.string.cloud_qr_not_ready)
                return
            }
            val currentGeneration = generation
            Notify.show(R.string.cloud_qr_searching)
            try {
                Task.executor().execute {
                    val currentPayload = payload
                    if (currentPayload != null) {
                        saveKnownPayload(currentPayload, actualTarget, currentGeneration)
                    }
                }
            } catch (
                error: RuntimeException,
            ) {
                AppLog.w(TAG, "Unable to queue direct provider QR handoff", error)
                Notify.show(R.string.cloud_qr_save_failed)
            }
        }

        private fun saveKnownPayload(
            payload: String,
            target: ProviderQrLauncher.Target,
            currentGeneration: Int,
        ) {
            val cleanQr = QRCode.getBitmapPx(payload, GENERATED_QR_SIZE_PX, 4)
            var saved: Uri? = null
            try {
                if (cleanQr != null && currentGeneration == generation) saved = ProviderQrLauncher.save(activityRef.get(), cleanQr, target)
            } finally {
                if (cleanQr != null && !cleanQr.isRecycled) cleanQr.recycle()
            }
            val result = saved
            handler.post {
                openKnownPayload(payload, target, currentGeneration, result)
            }
        }

        private fun openKnownPayload(
            payload: String,
            target: ProviderQrLauncher.Target,
            currentGeneration: Int,
            saved: Uri?,
        ) {
            val activity = activityRef.get()
            val webView = webViewRef.get()
            if (currentGeneration != generation || !alive(activity, webView)) {
                ProviderQrLauncher.discardSaved(activity, saved)
                return
            }
            if (saved == null) {
                Notify.show(R.string.cloud_qr_save_failed)
                return
            }
            if (ProviderQrLauncher.open(activity, payload, true)) {
                notifyProviderOpened()
                generation++
            } else {
                ProviderQrLauncher.discardSaved(activity, saved)
            }
        }

        fun complete() {
            cancelScan()
            ProviderQrLauncher.discardSaved(activityRef.get())
        }

        fun destroy() {
            cancelScan()
            handler.removeCallbacksAndMessages(null)
            activityRef.clear()
            webViewRef.clear()
        }

        private fun scan() {
            val activity = activityRef.get()
            val webView = webViewRef.get()
            if (!alive(activity, webView) || expectedTarget == null) return
            if (decoding) {
                handler.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
                return
            }
            if (attempts++ >= MAX_SCAN_ATTEMPTS) {
                Notify.show(R.string.cloud_qr_not_found)
                return
            }
            val currentWebView = webView ?: return
            val capture = allocateCapture(currentWebView)
            if (capture == null) {
                handler.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
                return
            }
            decoding = true
            val currentActivity = activityRef.get()
            if (currentActivity != null) {
                requestPixelCopy(currentActivity, currentWebView, capture, generation)
            } else {
                decoding = false
            }
        }

        private fun onDomQrImage(
            currentGeneration: Int,
            javascriptValue: String?,
        ) {
            if (currentGeneration != generation || decoding) return
            val bitmap = decodeDomBitmap(javascriptValue) ?: return
            decoding = true
            submitDecode(bitmap, currentGeneration)
        }

        private fun requestPixelCopy(
            activity: Activity,
            webView: WebView,
            capture: Bitmap,
            currentGeneration: Int,
        ) {
            val location = IntArray(2)
            webView.getLocationInWindow(location)
            val source = Rect(location[0], location[1], location[0] + webView.width, location[1] + webView.height)
            val bounds = windowBounds(activity)
            if (!source.intersect(0, 0, bounds.width, bounds.height)) {
                capture.recycle()
                decoding = false
                handler.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
                return
            }
            try {
                PixelCopy.request(activity.window, source, capture, { result ->
                    if (result != PixelCopy.SUCCESS || currentGeneration != generation) {
                        if (!capture.isRecycled) capture.recycle()
                        decoding = false
                        if (currentGeneration == generation) {
                            AppLog.w(TAG, "Login-page capture failed with PixelCopy result $result")
                            handler.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
                        }
                    } else {
                        submitDecode(capture, currentGeneration)
                    }
                }, handler)
            } catch (error: RuntimeException) {
                capture.recycle()
                decoding = false
                AppLog.w(TAG, "Unable to capture provider login page", error)
                handler.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
            }
        }

        private fun submitDecode(
            capture: Bitmap,
            currentGeneration: Int,
        ) {
            try {
                Task.executor().execute {
                    decodeAndSave(capture, currentGeneration)
                }
            } catch (error: RuntimeException) {
                capture.recycle()
                decoding = false
                AppLog.w(TAG, "Unable to queue provider QR decoding", error)
                handler.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
            }
        }

        private fun decodeAndSave(
            capture: Bitmap,
            currentGeneration: Int,
        ) {
            val payload =
                decodePayload({
                    ProviderQrLauncher.decode(capture)
                }, {
                    // Failure must reset decoding and re-arm scanning; otherwise one bad frame stalls it.
                    AppLog.w(TAG, "Unable to decode provider QR capture", RuntimeException("qr decode failed"))
                    handler.post {
                        decoding = false
                        if (currentGeneration == generation) handler.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
                    }
                })
            if (payload == null) {
                if (!capture.isRecycled) capture.recycle()
                return
            }
            val actualTarget = ProviderQrLauncher.targetForPayload(payload)
            AppLog.i(
                TAG,
                if (actualTarget ==
                    null
                ) {
                    "No allowlisted QR found in login-page capture"
                } else {
                    "Validated QR for " + actualTarget.name
                },
            )
            var saved: Uri? = null
            if (currentGeneration == generation && actualTarget == expectedTarget) {
                val cleanQr = QRCode.getBitmapPx(payload, GENERATED_QR_SIZE_PX, 4)
                try {
                    if (cleanQr != null) saved = ProviderQrLauncher.save(activityRef.get(), cleanQr, actualTarget)
                } finally {
                    if (cleanQr != null && !cleanQr.isRecycled) cleanQr.recycle()
                }
            }
            val result = saved
            handler.post {
                onDecoded(currentGeneration, payload, actualTarget, result)
            }
        }

        private fun onDecoded(
            currentGeneration: Int,
            payload: String,
            actualTarget: ProviderQrLauncher.Target?,
            saved: Uri?,
        ) {
            decoding = false
            val activity = activityRef.get()
            val webView = webViewRef.get()
            if (currentGeneration != generation || !alive(activity, webView)) {
                ProviderQrLauncher.discardSaved(activity, saved)
                return
            }
            if (actualTarget != expectedTarget) {
                handler.postDelayed(scanRunnable, SCAN_INTERVAL_MS)
                return
            }
            if (saved == null) {
                Notify.show(R.string.cloud_qr_save_failed)
                return
            }
            if (ProviderQrLauncher.open(activity, payload, true)) {
                notifyProviderOpened()
                generation++
                handler.removeCallbacks(scanRunnable)
            } else {
                ProviderQrLauncher.discardSaved(activity, saved)
            }
        }

        fun interface Decoder {
            fun decode(): String?
        }

        private fun cancelScan() {
            generation++
            attempts = 0
            handler.removeCallbacks(scanRunnable)
        }

        private fun notifyProviderOpened() {
            onProviderOpened?.run()
        }

        private class ViewBounds(
            val width: Int,
            val height: Int,
        )

        companion object {
            private const val FIRST_SCAN_DELAY_MS = 450L
            private const val SCAN_INTERVAL_MS = 650L
            private const val MAX_SCAN_ATTEMPTS = 16
            private const val GENERATED_QR_SIZE_PX = 720
            private const val MAX_CAPTURE_PIXELS = 1_200_000L
            private const val MAX_DOM_QR_BASE64_LENGTH = 6 * 1024 * 1024
            private const val TAG = "OpenDriveQrHandoff"
            private const val SELECT_QR_LOGIN_SCRIPT =
                "(function(){" +
                    "var labels=['扫码登录','掃碼登入','二维码登录','二維碼登入','qr code','scan qr'];" +
                    "var nodes=document.querySelectorAll('button,a,[role=button],div,span');" +
                    "for(var i=0;i<nodes.length;i++){" +
                    "var n=nodes[i],t=(n.innerText||n.textContent||'').trim().toLowerCase();" +
                    "if(t.length>16||labels.indexOf(t)<0)continue;" +
                    "var r=n.getBoundingClientRect(),s=window.getComputedStyle(n);" +
                    "if(r.width<20||r.height<12||s.display==='none'||s.visibility==='hidden')continue;" +
                    "n.click();return true;" +
                    "}return false;" +
                    "})();"
            private const val EXTRACT_QR_IMAGE_SCRIPT =
                "(function(){try{" +
                    "var all=document.querySelectorAll('canvas,img'),best=null,bestScore=-1;" +
                    "for(var i=0;i<all.length;i++){var n=all[i],r=n.getBoundingClientRect();" +
                    "var w=r.width,h=r.height;if(w<70||h<70||w>700||h>700||w/h<0.72||w/h>1.28)continue;" +
                    "var hint=((n.id||'')+' '+(n.className||'')+' '+(n.alt||'')+' '+(n.src||'')).toLowerCase();" +
                    "var score=w*h+(n.tagName==='CANVAS'?10000000:0)" +
                    "+(/qr|qrcode|code/.test(hint)?20000000:0)+(r.top<innerHeight*0.65?1000000:0);" +
                    "if(score>bestScore){best=n;bestScore=score;}" +
                    "}if(!best)return '';" +
                    "if(best.tagName==='CANVAS')return best.toDataURL('image/png');" +
                    "var c=document.createElement('canvas'),w=best.naturalWidth||best.width,h=best.naturalHeight||best.height;" +
                    "if(w<1||h<1||w*h>4000000)return '';c.width=w;c.height=h;" +
                    "c.getContext('2d').drawImage(best,0,0,w,h);return c.toDataURL('image/png');" +
                    "}catch(e){return '';}})();"

            @JvmStatic fun supports(providerId: String?): Boolean = ProviderQrLauncher.targetForProvider(providerId) != null

            @JvmStatic fun cleanupExpired(context: Context?) = ProviderQrLauncher.cleanupExpired(context)

            @JvmStatic fun discardSaved(context: Context?) = ProviderQrLauncher.discardSaved(context)

            @JvmStatic fun saveCapturedAndOpen(
                activity: Activity?,
                providerId: String?,
                expectedPayload: String?,
                screenshot: Bitmap?,
                onProviderOpened: Runnable?,
            ) {
                val expected = ProviderQrLauncher.targetForProvider(providerId)
                if (activity == null || screenshot == null || expected == null) {
                    if (screenshot != null && !screenshot.isRecycled) screenshot.recycle()
                    Notify.show(R.string.cloud_qr_not_ready)
                    return
                }
                val activityRef = WeakReference(activity)
                try {
                    Task.executor().execute {
                        var saved: Uri? = null
                        try {
                            val decoded = ProviderQrLauncher.decode(screenshot)
                            val actual = ProviderQrLauncher.targetForPayload(decoded)
                            if (actual == expected && expectedPayload != null &&
                                expectedPayload == decoded
                            ) {
                                saved = ProviderQrLauncher.save(activityRef.get(), screenshot, actual)
                            }
                        } finally {
                            if (!screenshot.isRecycled) screenshot.recycle()
                        }
                        val result = saved
                        App.post {
                            val current = activityRef.get()
                            if (current == null || current.isFinishing ||
                                current.isDestroyed
                            ) {
                                ProviderQrLauncher.discardSaved(current, result)
                                return@post
                            }
                            if (result == null) {
                                Notify.show(R.string.cloud_qr_save_failed)
                                return@post
                            }
                            if (ProviderQrLauncher.open(current, expectedPayload, true)) {
                                onProviderOpened?.run()
                            } else {
                                ProviderQrLauncher.discardSaved(current, result)
                            }
                        }
                    }
                } catch (error: RuntimeException) {
                    screenshot.recycle()
                    Notify.show(R.string.cloud_qr_save_failed)
                }
            }

            /** Caller resets decoding and re-arms scanning on any decoder failure. */
            @JvmStatic fun decodePayload(
                decoder: Decoder,
                onFailure: Runnable,
            ): String? =
                try {
                    decoder.decode()
                } catch (
                    error: RuntimeException,
                ) {
                    onFailure.run()
                    null
                } catch (error: OutOfMemoryError) {
                    onFailure.run()
                    null
                }

            private fun decodeDomBitmap(javascriptValue: String?): Bitmap? {
                try {
                    val value = JSONTokener(javascriptValue).nextValue() as? String ?: return null
                    val prefix = "data:image/png;base64,"
                    if (!value.startsWith(prefix)) return null
                    val encoded = value.substring(prefix.length)
                    if (encoded.isEmpty() || encoded.length > MAX_DOM_QR_BASE64_LENGTH) return null
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (ignored: Exception) {
                    return null
                }
            }

            private fun allocateCapture(webView: WebView): Bitmap? {
                val sourceWidth = webView.width
                val sourceHeight = webView.height
                if (sourceWidth <= 0 || sourceHeight <= 0) return null
                val scale = minOf(1.0, Math.sqrt(MAX_CAPTURE_PIXELS.toDouble() / (sourceWidth.toLong() * sourceHeight).toDouble()))
                val width = maxOf(1, Math.round(sourceWidth * scale).toInt())
                val height = maxOf(1, Math.round(sourceHeight * scale).toInt())
                try {
                    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                } catch (
                    error: OutOfMemoryError,
                ) {
                    AppLog.w(TAG, "Unable to allocate QR capture", error)
                    return null
                } catch (
                    error: RuntimeException,
                ) {
                    AppLog.w(TAG, "Unable to allocate QR capture", error)
                    return null
                }
            }

            private fun windowBounds(activity: Activity): ViewBounds =
                activity.window.decorView.let {
                    ViewBounds(it.width, it.height)
                }

            private fun alive(
                activity: Activity?,
                webView: WebView?,
            ): Boolean = Util.isActive(activity) && webView != null && webView.isAttachedToWindow
        }
    }
