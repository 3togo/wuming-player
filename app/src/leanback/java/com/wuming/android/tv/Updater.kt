package com.wuming.android.tv

import android.app.Activity
import com.wuming.android.tv.databinding.DialogUpdateBinding
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import java.util.Locale

open class Updater : BaseUpdater() {
    companion object {
        @JvmStatic fun create(): Updater = Updater()
    }

    override fun createDialog(
        activity: Activity,
        title: String,
    ) {
        getBinding().version.text = title
        setDialog(TvAlertDialogBuilder(activity).setView(getBinding().root).setCancelable(true).create())
    }

    override fun onDialogShown(
        binding: DialogUpdateBinding,
        desc: String,
    ) {
        binding.confirm.setOnClickListener(this::confirm)
        binding.cancel.setOnClickListener(this::cancel)
        binding.desc.text = desc
        binding.confirm.requestFocus()
    }

    override fun progress(progress: Int) {
        if (getBinding() != null) getBinding().confirm.text = String.format(Locale.getDefault(), "%1\$d%%", progress)
    }
}
