package com.wuming.android.tv.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.util.EnumMap
import java.util.Locale

class ProviderQrLauncher private constructor() {
    enum class Target(
        @JvmField val displayName: String,
        @JvmField val packageName: String,
        @JvmField val openAppWhenUrlUnsupported: Boolean,
    ) {
        ALIPAN("Aliyun Drive", "com.alicloud.databox", true),
        QUARK("Quark Drive", "com.quark.browser", false),
        UC("UC Drive", "com.UCMobile", false),
        BAIDU("Baidu Netdisk", "com.baidu.netdisk", true),
        PAN123("123 Netdisk", "com.mfcloudcalculate.networkdisk", true),
        TIANYI("Tianyi Cloud", "com.cn21.ecloud", true),
        BILIBILI("Bilibili", "tv.danmaku.bili", true),
    }

    companion object {
        private const val TAG = "ProviderQrLauncher"
        private const val PREFS = "provider_qr_handoff"
        private const val KEY_URI = "saved_uri"
        private const val KEY_SAVED_AT = "saved_at"
        private const val QR_EXPIRY_MS = 10 * 60 * 1000L

        @JvmStatic fun decode(bitmap: Bitmap?): String {
            if (bitmap == null || bitmap.isRecycled) return ""
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return decode(width, height, pixels)
        }

        @JvmStatic fun decode(
            width: Int,
            height: Int,
            pixels: IntArray,
        ): String =
            try {
                val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
                hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
                hints[DecodeHintType.TRY_HARDER] = true
                val source = RGBLuminanceSource(width, height, pixels)
                MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (ignored: Exception) {
                ""
            }

        @JvmStatic fun open(
            activity: Activity?,
            payload: String?,
        ): Boolean = open(activity, payload, false)

        @JvmStatic fun open(
            activity: Activity?,
            payload: String?,
            qrSaved: Boolean,
        ): Boolean {
            val target = targetForPayload(payload)
            if (activity == null || activity.isFinishing || target == null) return false
            // Keep confirmation in the caller task so Back resumes the pending login.
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(payload)).addCategory(Intent.CATEGORY_BROWSABLE)
            val targeted = selectOfficialHandler(activity, Intent(intent).setPackage(target.packageName), target)
            try {
                if (qrSaved && (target == Target.UC || target == Target.BILIBILI)) {
                    val launch =
                        activity.packageManager.getLaunchIntentForPackage(target.packageName)
                            ?: throw ActivityNotFoundException(target.packageName)
                    AppLog.i(TAG, "Opening installed provider app " + target.packageName)
                    activity.startActivity(launch)
                } else if (targeted.resolveActivity(activity.packageManager) != null) {
                    AppLog.i(TAG, "Opening provider QR in " + target.packageName)
                    activity.startActivity(targeted)
                } else if (target.openAppWhenUrlUnsupported) {
                    val launch =
                        activity.packageManager.getLaunchIntentForPackage(target.packageName)
                            ?: throw ActivityNotFoundException(target.packageName)
                    AppLog.i(TAG, "Opening installed provider app " + target.packageName)
                    activity.startActivity(launch)
                } else {
                    AppLog.i(TAG, "Opening validated " + target.name + " QR in browser fallback")
                    activity.startActivity(intent)
                }
                Notify.show(
                    activity.getString(if (qrSaved) R.string.cloud_qr_saved_opening else R.string.cloud_qr_opening, target.displayName),
                )
                return true
            } catch (
                error: ActivityNotFoundException,
            ) {
                return openFailed(error)
            } catch (error: SecurityException) {
                return openFailed(error)
            }
        }

        private fun openFailed(error: RuntimeException): Boolean {
            AppLog.w(TAG, "No safe handler for provider QR", error)
            Notify.show(R.string.cloud_qr_open_failed)
            return false
        }

        private fun selectOfficialHandler(
            activity: Activity,
            intent: Intent,
            target: Target,
        ): Intent {
            // Pin only an activity returned for the allowlisted package; never widen trust.
            try {
                for (info in activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
                    val candidate = info.activityInfo ?: continue
                    if (target.packageName != candidate.packageName) continue
                    return Intent(intent).setComponent(ComponentName(candidate.packageName, candidate.name))
                }
            } catch (error: RuntimeException) {
                AppLog.w(TAG, "Unable to select the official provider URL handler", error)
            }
            return intent
        }

        @JvmStatic fun save(
            context: Context?,
            bitmap: Bitmap?,
            target: Target?,
        ): Uri? {
            if (context == null || bitmap == null || bitmap.isRecycled || target == null) return null
            cleanupExpired(context)
            discardSaved(context)
            val resolver = context.contentResolver
            val values = ContentValues()
            val name = "TVBox-${target.name}-QR-${System.currentTimeMillis()}.png"
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TVBox")
                values.put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "TVBox")
                if (!directory.exists() && !directory.mkdirs()) return null
                values.put(MediaStore.Images.Media.DATA, File(directory, name).absolutePath)
            }
            var uri: Uri? = null
            try {
                val inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                uri = inserted
                resolver.openOutputStream(inserted, "w").use { output ->
                    check(
                        output != null && bitmap.compress(Bitmap.CompressFormat.PNG, 100, output),
                    ) {
                        "Unable to encode provider QR image"
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val ready = ContentValues()
                    ready.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(inserted, ready, null, null)
                }
                AppLog.i(TAG, "Saved provider QR image for " + target.name)
                rememberSaved(context, inserted)
                scheduleCleanup(context.applicationContext, inserted, QR_EXPIRY_MS)
                return inserted
            } catch (error: Throwable) {
                if (uri != null) resolver.delete(uri, null, null)
                AppLog.w(TAG, "Unable to save provider QR image", error)
                return null
            }
        }

        @JvmStatic fun targetForPayload(payload: String?): Target? {
            val host = trustedHttpsHost(payload) ?: return null
            return when {
                trustedHost(host, "aliyundrive.com") || trustedHost(host, "alipan.com") -> Target.ALIPAN
                trustedHost(host, "quark.cn") || trustedHost(host, "myquark.cn") -> Target.QUARK
                trustedHost(host, "uc.cn") || trustedHost(host, "ucweb.com") -> Target.UC
                trustedHost(host, "baidu.com") || trustedHost(host, "baidubce.com") -> Target.BAIDU
                trustedHost(host, "123pan.com") || trustedHost(host, "123pan.cn") -> Target.PAN123
                trustedHost(host, "189.cn") -> Target.TIANYI
                trustedHost(host, "bilibili.com") || trustedHost(host, "biligame.com") -> Target.BILIBILI
                else -> null
            }
        }

        @JvmStatic fun targetForProvider(providerId: String?): Target? =
            when (providerId) {
                "alipan" -> Target.ALIPAN
                "quark" -> Target.QUARK
                "uc" -> Target.UC
                "baidu" -> Target.BAIDU
                "123pan" -> Target.PAN123
                "tianyi" -> Target.TIANYI
                else -> null
            }

        @JvmStatic fun cleanupExpired(context: Context?) {
            if (context == null) return
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedAt = preferences.getLong(KEY_SAVED_AT, 0L)
            val value = preferences.getString(KEY_URI, "")!!
            val age = System.currentTimeMillis() - savedAt
            if (savedAt <= 0 || value.isEmpty() || age < 0 || age >= QR_EXPIRY_MS) {
                discardSaved(context)
            } else {
                scheduleCleanup(context.applicationContext, Uri.parse(value), QR_EXPIRY_MS - age)
            }
        }

        @JvmStatic fun discardSaved(context: Context?) {
            if (context == null) return
            val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URI, "")!!
            if (value.isNotEmpty()) deleteOwned(context, Uri.parse(value))
            clearRemembered(context, value)
        }

        @JvmStatic fun discardSaved(
            context: Context?,
            uri: Uri?,
        ) {
            if (context == null || uri == null) return
            deleteOwned(context, uri)
            clearRemembered(context, uri.toString())
        }

        private fun rememberSaved(
            context: Context,
            uri: Uri,
        ) {
            context
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE,
                ).edit()
                .putString(KEY_URI, uri.toString())
                .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                .apply()
        }

        private fun scheduleCleanup(
            context: Context,
            uri: Uri,
            delayMillis: Long,
        ) {
            App.post({
                val current = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URI, "")
                if (uri.toString() == current) discardSaved(context, uri)
            }, maxOf(0L, delayMillis))
        }

        private fun clearRemembered(
            context: Context,
            expected: String,
        ) {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (expected == preferences.getString(KEY_URI, "")) {
                preferences
                    .edit()
                    .remove(KEY_URI)
                    .remove(KEY_SAVED_AT)
                    .apply()
            }
        }

        private fun deleteOwned(
            context: Context,
            uri: Uri,
        ) {
            if (uri.scheme != "content" || uri.authority != "media") return
            try {
                context.contentResolver.delete(uri, null, null)
                AppLog.i(TAG, "Deleted temporary provider QR image")
            } catch (
                error: SecurityException,
            ) {
                AppLog.w(TAG, "Unable to delete temporary provider QR image", error)
            }
        }

        private fun trustedHttpsHost(payload: String?): String? {
            if (payload == null || !payload.regionMatches(0, "https://", 0, 8, true)) return null
            if (payload.any {
                    it <= ' ' || it == '\u007f'
                }
            ) {
                return null
            }
            var end = payload.length
            for (separator in charArrayOf('/', '?', '#')) {
                val index = payload.indexOf(separator, 8)
                if (index >= 0 && index < end) end = index
            }
            val authority = payload.substring(8, end)
            if (authority.isEmpty() || authority.indexOf('@') >= 0 || authority.indexOf('\\') >= 0 ||
                authority.indexOf(':') >= 0
            ) {
                return null
            }
            return authority.lowercase(Locale.ROOT)
        }

        private fun trustedHost(
            host: String,
            domain: String,
        ): Boolean = host == domain || host.endsWith(".$domain")
    }
}
