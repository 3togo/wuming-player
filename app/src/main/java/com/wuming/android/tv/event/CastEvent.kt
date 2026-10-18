package com.wuming.android.tv.event

import com.wuming.android.tv.bean.Config
import com.wuming.android.tv.bean.Device
import com.wuming.android.tv.bean.History
import org.greenrobot.eventbus.EventBus

data class CastEvent(
    private val configValue: Config,
    private val deviceValue: Device,
    private val historyValue: History,
    private val audioTrackFormatValue: String? = null,
) {
    fun config(): Config = configValue
    fun device(): Device = deviceValue
    fun history(): History = historyValue
    fun audioTrackFormat(): String? = audioTrackFormatValue

    companion object {
        @JvmStatic
        fun post(config: Config, device: Device, history: History) =
            post(config, device, history, null)

        @JvmStatic
        fun post(config: Config, device: Device, history: History, audioTrackFormat: String?) {
            EventBus.getDefault().post(CastEvent(config, device, history, audioTrackFormat))
        }
    }
}
