package com.wuming.android.tv.ui.dialog

import android.content.DialogInterface
import android.content.Intent
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.wuming.android.tv.R
import com.wuming.android.tv.api.config.LiveConfig
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.api.config.WallConfig
import com.wuming.android.tv.bean.Config
import com.wuming.android.tv.databinding.DialogConfigBinding
import com.wuming.android.tv.impl.ConfigCallback
import com.wuming.android.tv.ui.custom.CustomTextListener
import com.wuming.android.tv.utils.FileChooser
import com.wuming.android.tv.utils.TvAlertDialogBuilder

open class MobileConfigDialog(
    fragment: Fragment,
) {
    private var launcher: ActivityResultLauncher<Intent>? = null
    private val callback = fragment as ConfigCallback
    private val binding =
        DialogConfigBinding.inflate(
            com.wuming.android.tv.utils.DialogUtil
                .inflater(fragment.requireContext()),
        )
    private lateinit var dialog: AlertDialog
    private var append = true
    private var edit = false
    private var ori: String? = null
    private var type = 0
    private var prefillUrl: String? = null
    private var adminUnlocked = false
    private var bundledUrlLocked = false

    companion object {
        @JvmStatic fun create(fragment: Fragment): MobileConfigDialog = MobileConfigDialog(fragment)
    }

    open fun type(type: Int): MobileConfigDialog {
        this.type = type
        return this
    }

    open fun url(url: String?): MobileConfigDialog {
        prefillUrl = url
        return this
    }

    open fun unlockedUrl(): MobileConfigDialog {
        adminUnlocked = true
        return this
    }

    open fun edit(): MobileConfigDialog {
        edit = true
        return this
    }

    open fun launcher(launcher: ActivityResultLauncher<Intent>?): MobileConfigDialog {
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
            TvAlertDialogBuilder(binding.root.context)
                .setTitle(
                    if (type == 0) {
                        R.string.setting_vod
                    } else if (type == 1) {
                        R.string.setting_live
                    } else {
                        R.string.setting_wall
                    },
                ).setView(
                    binding.root,
                ).setPositiveButton(
                    if (edit) R.string.dialog_edit else R.string.dialog_positive,
                    this::onPositive,
                ).setNegativeButton(R.string.dialog_negative, this::onNegative)
                .create()
        dialog.window!!.setDimAmount(0f)
        dialog.show()
    }

    private fun initView() {
        binding.name.setText(getConfig()!!.name)
        ori = prefillUrl ?: getConfig()!!.url
        bundledUrlLocked = !adminUnlocked && BundledSourceAccess.isBundled(binding.root.context, ori)
        binding.url.setText(if (bundledUrlLocked) "" else ori)
        binding.choose.visibility = if (bundledUrlLocked) View.GONE else View.VISIBLE
        binding.input.visibility = if (edit || bundledUrlLocked) View.VISIBLE else View.GONE
        binding.name.isEnabled = edit
        if (!bundledUrlLocked) binding.url.setSelection(if (TextUtils.isEmpty(ori)) 0 else ori!!.length)
    }

    private fun initEvent() {
        binding.choose.setEndIconOnClickListener(this::onChoose)
        binding.url.addTextChangedListener(
            object : CustomTextListener() {
                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    detect(s!!.toString())
                }
            },
        )
        binding.url.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            true
        }
    }

    private fun getConfig(): Config? =
        when (type) {
            0 -> VodConfig.get().getConfig()
            1 -> LiveConfig.get().getConfig()
            2 -> WallConfig.get().getConfig()
            else -> null
        }

    private fun onChoose(view: View) {
        FileChooser.from(launcher).show()
        dialog.dismiss()
    }

    private fun detect(s: String) {
        if (append && "h".equals(s, ignoreCase = true)) {
            append = false
            binding.url.append("ttp://")
        } else if (append && "f".equals(s, ignoreCase = true)) {
            append = false
            binding.url.append("ile://")
        } else if (append && "a".equals(s, ignoreCase = true)) {
            append = false
            binding.url.append("ssets://")
        } else if (s.length > 1) {
            append = false
        } else if (s.isEmpty()) {
            append = true
        }
    }

    private fun onPositive(
        dialog: DialogInterface,
        which: Int,
    ) {
        val url = if (bundledUrlLocked) ori.orEmpty() else
            binding.url.text!!.toString().trim { it <= ' ' }
        val name =
            binding.name.text!!
                .toString()
                .trim { it <= ' ' }
        if (edit) {
            Config
                .find(ori, type)
                .url(url)
                .name(name)
                .update()
        }
        if (url.isEmpty()) Config.delete(ori, type)
        callback.setConfig(Config.find(url, type))
        dialog.dismiss()
    }

    private fun onNegative(
        dialog: DialogInterface,
        which: Int,
    ) {
        dialog.dismiss()
    }
}
