package com.wuming.android.tv.ui.dialog

import com.wuming.android.tv.utils.ControlSpacing
import android.content.DialogInterface
import android.content.Intent
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.wuming.android.tv.R
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.cloud.opendrive.OpenDriveShareTarget
import com.wuming.android.tv.databinding.DialogLinkBinding
import com.wuming.android.tv.setting.AppSettings
import com.wuming.android.tv.ui.activity.VideoActivity
import com.wuming.android.tv.utils.DownloadedCatVod
import com.wuming.android.tv.utils.FileChooser
import com.wuming.android.tv.utils.Notify
import com.wuming.android.tv.utils.ProviderAppLauncher
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.Sniffer
import com.wuming.android.tv.utils.Util
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import java.util.Optional

open class LinkDialog(
    private val fragment: Fragment,
) {
    private var launcher: ActivityResultLauncher<Intent>? = null
    private val binding =
        DialogLinkBinding.inflate(
            com.wuming.android.tv.utils.DialogUtil
                .inflater(fragment.requireContext()),
        )
    private lateinit var dialog: AlertDialog

    companion object {
        @JvmStatic fun create(fragment: Fragment): LinkDialog = LinkDialog(fragment)
    }

    open fun launcher(launcher: ActivityResultLauncher<Intent>?): LinkDialog {
        this.launcher = launcher
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
            ).setTitle(
                R.string.play,
            ).setView(
                binding.root,
            ).setPositiveButton(
                R.string.dialog_positive,
                this::onPositive,
            ).setNegativeButton(
                R.string.dialog_negative,
                this::onNegative,
            ).setNeutralButton(R.string.cloud_storage_open_external, null)
                .create()
        dialog.window!!.setDimAmount(0.35f)
        dialog.show()
        if (ResUtil.isPad()) {
            val context = fragment.requireContext()
            val width = (minOf(ResUtil.getScreenWidth(context), ResUtil.getScreenHeight(context)) * 0.8f).toInt()
            dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(this::onOpenOfficialApp)
    }

    private fun initView() {
        val horizontal = com.wuming.android.tv.utils.ControlSpacing.dimensionPixels(dialog.context, R.dimen.ds_space_large)
        val vertical = com.wuming.android.tv.utils.ControlSpacing.dimensionPixels(dialog.context, R.dimen.ds_space_small)
        binding.root.setPadding(horizontal, vertical, horizontal, vertical)
        val text = Util.getClipText()
        binding.text.filters = arrayOf(InputFilter.LengthFilter(Int.MAX_VALUE))
        if (!TextUtils.isEmpty(text)) binding.text.setText(Sniffer.getUrl(text.toString()))
    }

    private fun initEvent() {
        binding.input.setEndIconOnClickListener(this::onChoose)
        binding.text.addTextChangedListener(SimpleTextWatcher { updateLinkActions() })
        binding.text.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            true
        }
        updateLinkActions()
    }

    private fun updateLinkActions() {
        val text =
            binding.text.text!!
                .toString()
                .trim { it <= ' ' }
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).visibility = if (ProviderAppLauncher.supports(text)) View.VISIBLE else View.GONE
    }

    private fun onOpenOfficialApp(view: View) {
        val text =
            binding.text.text!!
                .toString()
                .trim { it <= ' ' }
        if (ProviderAppLauncher.open(fragment.requireActivity(), text)) dialog.dismiss()
    }

    private fun onChoose(view: View) {
        FileChooser.from(launcher).show()
        dialog.dismiss()
    }

    private fun onPositive(
        dialog: DialogInterface,
        which: Int,
    ) {
        val text =
            binding.text.text!!
                .toString()
                .trim { it <= ' ' }
        val share = OpenDriveShareTarget.fromText(text)
        if (share.isPresent) {
            if (AppSettings.getLinkRoute() == AppSettings.LINK_ROUTE_BUILTIN) {
                VideoActivity.start(
                    fragment.requireActivity(),
                    VodConfig.OPEN_DRIVE_KEY,
                    share.get().itemId,
                    fragment.getString(R.string.open_drive_share_title),
                    null,
                )
            } else {
                val site = DownloadedCatVod.findCloudSite()
                if (site == null) {
                    Notify.show(R.string.link_route_downloaded_missing)
                    return
                }
                VideoActivity.start(fragment.requireActivity(), site.key, share.get().shareUrl, site.name, null)
            }
        } else if (text.isNotEmpty()) {
            VideoActivity.start(fragment.requireActivity(), text)
        }
        dialog.dismiss()
    }

    private fun onNegative(
        dialog: DialogInterface,
        which: Int,
    ) {
        dialog.dismiss()
    }

    private fun interface SimpleTextWatcher : TextWatcher {
        fun changed(value: CharSequence?)

        override fun beforeTextChanged(
            value: CharSequence?,
            start: Int,
            count: Int,
            after: Int,
        ) {}

        override fun onTextChanged(
            value: CharSequence?,
            start: Int,
            before: Int,
            count: Int,
        ) {
            changed(value)
        }

        override fun afterTextChanged(value: android.text.Editable?) {}
    }
}
