package com.wuming.android.tv.player.relay

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.Task
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.wuming.tv.core.playback.relay.DiscoveredRelay

object DesktopRelayDialog {
    @JvmStatic fun show(activity: Activity) {
        val choices = arrayOf(activity.getString(R.string.relay_find), activity.getString(
            if (DesktopRelayClient.enabled) R.string.relay_disable else R.string.relay_enable), activity.getString(R.string.relay_forget))
        TvAlertDialogBuilder(activity).setTitle(R.string.relay_title)
            .setItems(choices) { _, index -> when (index) {
                0 -> find(activity)
                1 -> if (DesktopRelayClient.paired) DesktopRelayClient.setEnabled(!DesktopRelayClient.enabled) else find(activity)
                2 -> DesktopRelayClient.forget()
            } }.setNegativeButton(R.string.dialog_negative, null).show()
    }
    private fun find(activity: Activity) {
        Toast.makeText(activity, R.string.relay_searching, Toast.LENGTH_SHORT).show()
        Task.execute {
            val found = runCatching { DesktopRelayClient.discover() }.getOrDefault(emptyList())
            App.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (found.isEmpty()) Toast.makeText(activity, R.string.relay_not_found, Toast.LENGTH_LONG).show()
                else TvAlertDialogBuilder(activity).setTitle(R.string.relay_find)
                    .setItems(found.map { "${it.service.name} (${java.net.URI(it.address).host})" }.toTypedArray()) { _, index -> pair(activity, found[index]) }
                    .setNegativeButton(R.string.dialog_negative, null).show()
            }
        }
    }
    private fun pair(activity: Activity, relay: DiscoveredRelay) {
        val code = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            isSingleLine = true
            hint = activity.getString(R.string.relay_code)
        }
        val dialog = TvAlertDialogBuilder(activity).setTitle(R.string.relay_code).setMessage(R.string.relay_code_help)
            .setView(code).setPositiveButton(R.string.dialog_positive, null).setNegativeButton(R.string.dialog_negative, null).create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val value = code.text.toString()
                if (!value.matches(Regex("[0-9]{6}"))) { code.error = activity.getString(R.string.relay_code); return@setOnClickListener }
                positive.isEnabled = false
                Task.execute {
                    val success = runCatching { DesktopRelayClient.pair(relay, value) }.isSuccess
                    App.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        if (success) { dialog.dismiss(); Toast.makeText(activity, R.string.relay_paired, Toast.LENGTH_LONG).show() }
                        else { positive.isEnabled = true; code.error = activity.getString(R.string.relay_pair_failed) }
                    }
                }
            }
            code.nextFocusDownId = positive.id
            code.nextFocusForwardId = positive.id
        }
        dialog.show()
    }
}
