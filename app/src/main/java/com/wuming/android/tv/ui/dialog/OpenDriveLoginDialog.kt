package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.graphics.Bitmap
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.cloud.CloudStorageProvider
import com.wuming.android.tv.cloud.opendrive.OpenDriveTokenManager
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.Notify
import com.wuming.android.tv.utils.OpenDriveQrHandoff
import com.wuming.android.tv.utils.QRCode
import com.wuming.android.tv.utils.QrSizing
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.Task
import com.wuming.android.tv.utils.UiTaskScope
import com.wuming.tv.core.cloud.OpenDriveLoginChallenge
import com.wuming.tv.core.cloud.OpenDriveLoginState
import java.lang.ref.WeakReference
import java.util.concurrent.Future

/** First-party QR flow. BaseQrLoginDialog measures the square; never use a fixed QR size here. */
class OpenDriveLoginDialog private constructor(
    activity: Activity?,
    private val provider: CloudStorageProvider,
    private val autoRequested: Boolean,
    private var onChanged: Runnable?,
) : BaseQrLoginDialog(activity) {
    private val tokens = OpenDriveTokenManager.get()
    private val providerName = provider.displayName
    private var pollTasks = UiTaskScope()
    private var task: Future<*>? = null
    private var sessionId: String? = null
    private var payload: String? = null
    private var pollFailures = 0
    private var polling = false
    private var confirmed = false

    override fun title(): CharSequence? = ResUtil.getString(R.string.cloud_provider_qr_title, providerName)

    override fun initialStatus(): CharSequence? = ResUtil.getString(R.string.cloud_provider_qr_connecting, providerName)

    override fun primaryLabel(): Int = R.string.cloud_qr_auto

    override fun secondaryLabel(): Int = R.string.cloud_provider_qr_new

    override fun primaryEnabledInitially(): Boolean = false

    override fun onPrimary() = auto()

    override fun onSecondary() = begin()

    override fun onStart() = begin()

    override fun onStop() {
        pollTasks.close()
        cancelTask()
        sessionId = null
        payload = null
        polling = false
        val activity = getActivity()
        if (activity != null && !confirmed) OpenDriveQrHandoff.discardSaved(activity)
    }

    override fun onDismissed() {
        onChanged = null
    }

    override fun onFinish(success: Boolean) {
    } // Confirmed branch owns notification.

    private fun begin() {
        if (!isActive(getActivity())) return
        val current = reset()
        setStatus(ResUtil.getString(R.string.cloud_provider_qr_connecting, providerName))
        setQr(null)
        setPrimaryEnabled(false)
        val ownerRef = WeakReference(this)
        val providerId = provider.id
        val manager = tokens
        task =
            Task.submitLarge {
                try {
                    val challenge = manager.beginLogin(providerId)
                    val qr = QRCode.getBitmapPx(challenge.content, QrSizing.encodePx(), 4)
                    App.post {
                        val owner = ownerRef.get()
                        if (owner == null) recycle(qr) else owner.challengeReady(current, challenge, qr)
                    }
                } catch (error: Exception) {
                    AppLog.w(TAG, "QR challenge failed for $providerId", error)
                    App.post {
                        ownerRef.get()?.failed(current, R.string.cloud_provider_qr_failed)
                    }
                }
            }
    }

    private fun challengeReady(
        current: Int,
        challenge: OpenDriveLoginChallenge,
        qr: Bitmap?,
    ) {
        if (current != requestId) {
            recycle(qr)
            return
        }
        task = null
        if (qr == null) {
            failed(current, R.string.cloud_provider_qr_failed)
            return
        }
        sessionId = challenge.id
        payload = challenge.content
        setQr(qr)
        setStatus(ResUtil.getString(R.string.cloud_provider_qr_instruction, providerName))
        setPrimaryEnabled(OpenDriveQrHandoff.supports(provider.id))
        pollTasks.post({
            poll(current)
        }, 900L)
        if (autoRequested) {
            pollTasks.post({
                auto()
            }, 120L)
        }
    }

    private fun auto() {
        val activity = getActivity()
        if (!isActive(activity) || payload == null) {
            Notify.show(R.string.cloud_qr_not_ready)
            return
        }
        val screenshot = captureQr()
        if (screenshot == null) {
            Notify.show(R.string.cloud_qr_save_failed)
            return
        }
        OpenDriveQrHandoff.saveCapturedAndOpen(activity, provider.id, payload, screenshot, null)
    }

    private fun poll(current: Int) {
        if (current != requestId || polling || sessionId == null) return
        polling = true
        val activeSession = sessionId
        val ownerRef = WeakReference(this)
        val providerId = provider.id
        val manager = tokens
        task =
            Task.submitLarge {
                try {
                    val state = manager.pollLogin(providerId, activeSession)
                    App.post {
                        ownerRef.get()?.state(current, state)
                    }
                } catch (error: Exception) {
                    AppLog.w(TAG, "QR polling failed for $providerId", error)
                    App.post {
                        ownerRef.get()?.pollFailed(current)
                    }
                }
            }
    }

    private fun state(
        current: Int,
        state: OpenDriveLoginState?,
    ) {
        if (current != requestId) return
        task = null
        polling = false
        pollFailures = 0
        if (state == OpenDriveLoginState.WAITING || state == OpenDriveLoginState.SCANNED) {
            pollTasks.post({
                poll(current)
            }, POLL_INTERVAL_MILLIS)
            return
        }
        if (state == OpenDriveLoginState.CONFIRMED) {
            confirmed = true
            getActivity()?.let {
                OpenDriveQrHandoff.discardSaved(it)
            }
            Notify.show(ResUtil.getString(R.string.cloud_storage_login_confirmed, providerName))
            val callback = onChanged
            onChanged = null
            callback?.run()
            dismiss(false)
            return
        }
        failed(current, if (state == OpenDriveLoginState.EXPIRED) R.string.cloud_provider_qr_expired else R.string.cloud_provider_qr_failed)
    }

    private fun pollFailed(current: Int) {
        if (current != requestId) return
        task = null
        polling = false
        if (++pollFailures < 3) {
            pollTasks.post({
                poll(current)
            }, POLL_INTERVAL_MILLIS)
        } else {
            failed(current, R.string.cloud_provider_qr_failed)
        }
    }

    private fun failed(
        current: Int,
        message: Int,
    ) {
        if (current != requestId) return
        task = null
        polling = false
        sessionId = null
        setQr(null)
        hideQrArea()
        setStatus(ResUtil.getString(message, providerName))
        setPrimaryEnabled(false)
    }

    private fun reset(): Int {
        requestId++
        cancelTask()
        pollTasks.close()
        pollTasks = UiTaskScope()
        payload = null
        pollFailures = 0
        polling = false
        return requestId
    }

    private fun cancelTask() {
        task?.cancel(true)
        task = null
    }

    companion object {
        private const val TAG = "OpenDriveLoginDialog"
        private const val POLL_INTERVAL_MILLIS = 1500L

        @JvmStatic fun show(
            activity: Activity?,
            provider: CloudStorageProvider,
            auto: Boolean,
            onChanged: Runnable?,
        ): OpenDriveLoginDialog =
            OpenDriveLoginDialog(activity, provider, auto, onChanged).also {
                it.show()
            }
    }
}
