package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.cloud.opendrive.OpenDriveCopyUsage
import com.wuming.android.tv.cloud.opendrive.OpenDriveStreamCheck
import com.wuming.android.tv.databinding.DialogInfoBinding
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.Task
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.wuming.android.tv.utils.Util
import java.util.concurrent.Future

open class InfoDialog(
    activity: Activity,
) {
    private val binding = DialogInfoBinding.inflate(DialogUtil.inflater(activity))
    private val callback = activity as Listener
    private lateinit var dialog: AlertDialog
    private var title: CharSequence? = null
    private var header: String? = null
    private var url: String? = null
    private var streamUrl: String? = null
    private var streamHeaders: Map<String, String> = emptyMap()
    private var streamCheck: OpenDriveStreamCheck? = null
    private var checkTask: Future<*>? = null
    private var copyUsage: AutoCloseable? = null

    companion object {
        @JvmStatic fun create(activity: Activity): InfoDialog = InfoDialog(activity)
    }

    open fun title(title: CharSequence?): InfoDialog {
        this.title = title
        return this
    }

    open fun headers(headers: Map<String, String>?): InfoDialog {
        streamHeaders = if (headers == null) emptyMap() else LinkedHashMap(headers)
        val sb = StringBuilder()
        for ((key, value) in headers ?: emptyMap()) {
            if (!key.regionMatches(
                    0,
                    "X-OpenDrive-",
                    0,
                    12,
                    true,
                )
            ) {
                sb
                    .append(key)
                    .append(" : ")
                    .append(value)
                    .append("\n")
            }
        }
        header = sb.toString().dropLast(1)
        return this
    }

    open fun url(url: String?): InfoDialog {
        streamUrl = url
        this.url = fix(url)
        return this
    }

    open fun show() {
        initDialog()
        initView()
        initEvent()
    }

    private fun initDialog() {
        dialog =
            TvAlertDialogBuilder(
                binding.root.context,
            ).setView(
                binding.root,
            ).setPositiveButton(R.string.cloud_check_stream, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.window!!.setDimAmount(0f)
        dialog.show()
    }

    private fun initView() {
        binding.url.text = url
        binding.title.text = title
        binding.header.text = header
        binding.title.setSingleLine(title!!.toString().contains(url!!))
        binding.url.visibility = if (BeanUtils.isEmpty(url)) View.GONE else View.VISIBLE
        binding.header.visibility = if (BeanUtils.isEmpty(header)) View.GONE else View.VISIBLE
    }

    private fun initEvent() {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { checkStream() }
        dialog.setOnDismissListener { cancelCheck() }
        binding.url.setOnClickListener {
            callback.onShare(title)
            dialog.dismiss()
        }
        binding.url.setOnLongClickListener { onCopy(url) }
        binding.header.setOnLongClickListener { onCopy(header) }
    }

    private fun checkStream() {
        cancelCheck()
        val check = OpenDriveStreamCheck()
        streamCheck = check
        copyUsage = OpenDriveCopyUsage.pin(streamHeaders)
        binding.diagnostic.visibility = View.VISIBLE
        binding.diagnostic.setText(R.string.cloud_check_running)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        checkTask =
            Task.submitLarge {
                val result = check.check(streamUrl, streamHeaders)
                App.post {
                    if (!dialog.isShowing || streamCheck !== check) return@post
                    val message =
                        when (result.state!!) {
                            OpenDriveStreamCheck.State.MEDIA_BYTES -> R.string.cloud_check_bytes

                            OpenDriveStreamCheck.State.HLS_SEGMENT -> R.string.cloud_check_segment

                            OpenDriveStreamCheck.State.HTTP_ERROR -> R.string.cloud_check_http

                            OpenDriveStreamCheck.State.TEXT_RESPONSE,
                            OpenDriveStreamCheck.State.INVALID_PLAYLIST,
                            -> R.string.cloud_check_invalid

                            OpenDriveStreamCheck.State.LIMIT -> R.string.cloud_check_limit

                            OpenDriveStreamCheck.State.CANCELLED -> R.string.cloud_check_cancelled

                            OpenDriveStreamCheck.State.NETWORK_ERROR -> R.string.error_cloud_network

                            OpenDriveStreamCheck.State.UNSUPPORTED -> R.string.cloud_check_unsupported
                        }
                    binding.diagnostic.text =
                        if (result.state ==
                            OpenDriveStreamCheck.State.HTTP_ERROR
                        ) {
                            binding.root.context.getString(message, result.httpStatus)
                        } else {
                            binding.root.context.getString(message)
                        }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    cancelCheck()
                }
            }
    }

    private fun cancelCheck() {
        streamCheck?.close()
        streamCheck = null
        checkTask?.cancel(true)
        checkTask = null
        try {
            copyUsage?.close()
        } catch (_: Exception) {
        }
        copyUsage = null
    }

    private fun fix(url: String?): String =
        if (BeanUtils.isEmpty(url)) {
            ""
        } else if (url!!.startsWith("data")) {
            url.substring(0, Math.min(url.length, 128)) +
                "..."
        } else {
            url
        }

    private fun onCopy(text: String?): Boolean {
        if (!text.isNullOrEmpty()) Util.copy(text)
        return true
    }

    interface Listener {
        fun onShare(title: CharSequence?)
    }
}
