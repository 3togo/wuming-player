package com.wuming.android.tv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.Notify

class ShortcutReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Notify.show(R.string.shortcut)
    }

    companion object {
        @JvmField
        val ACTION: String = ShortcutReceiver::class.java.simpleName
    }
}
