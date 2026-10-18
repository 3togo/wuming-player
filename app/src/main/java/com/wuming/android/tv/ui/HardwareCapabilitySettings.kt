package com.wuming.android.tv.ui

import android.view.View
import android.widget.TextView
import com.wuming.android.tv.R
import com.wuming.android.tv.player.exo.VideoCapabilityCache
import java.text.DateFormat
import java.util.Date

object HardwareCapabilitySettings {
    @JvmStatic fun bind(trigger: View) {
        render(trigger)
        trigger.setOnClickListener(SettingsOperations::refreshVideoCapabilities)
    }

    @JvmStatic fun render(trigger: View) {
        val label = trigger.findViewById<TextView>(R.id.videoCapabilitiesText) ?: return
        val saved = VideoCapabilityCache.get().savedReport()
        val status = if (saved == null) trigger.context.getString(R.string.video_capabilities_not_detected) else
            trigger.context.getString(R.string.video_capabilities_saved,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(saved.detectedAt)),
                saved.decoders.size)
        label.text = trigger.context.getString(R.string.setting_video_capabilities) + "\n" + status
    }
}
