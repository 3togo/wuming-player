package com.wuming.android.tv

import android.app.Activity
import android.content.DialogInterface
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
        setDialog(
            TvAlertDialogBuilder(
                activity,
            ).setTitle(
                title,
            ).setView(
                getBinding().root,
            ).setPositiveButton(
                R.string.update_confirm,
                null,
            ).setNegativeButton(R.string.dialog_negative, null)
                .setCancelable(true)
                .create(),
        )
    }

    override fun onDialogShown(
        binding: DialogUpdateBinding,
        desc: String,
    ) {
        getDialog().getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(this::confirm)
        getDialog().getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(this::cancel)
        binding.desc.text = desc
    }

    override fun progress(progress: Int) {
        if (getDialog() != null &&
            getDialog().isShowing
        ) {
            getDialog().getButton(DialogInterface.BUTTON_POSITIVE).text =
                String.format(Locale.getDefault(), "%1\$d%%", progress)
        }
    }
}
