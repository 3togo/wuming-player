package com.wuming.android.tv.dlna

import android.content.Context
import com.wuming.android.tv.bean.Device
import org.fourthline.cling.controlpoint.ControlPoint
import org.fourthline.cling.model.meta.RemoteDevice
import org.fourthline.cling.model.meta.RemoteService

class DLNACastManager private constructor() {

    interface DeviceListener {
        fun onDeviceAdded(device: Device)
        fun onDeviceRemoved(device: Device)
    }

    fun init(context: Context) {}
    fun isReady(): Boolean = false
    fun setDeviceListener(listener: DeviceListener?) {}
    fun search() {}
    fun getRegistered(): List<Device> = emptyList()
    fun findDevice(bean: Device): RemoteDevice? = null
    fun findAVTransport(bean: Device): RemoteService? = null
    val controlPoint: ControlPoint? get() = null
    fun release(context: Context) {}

    companion object {
        @JvmStatic
        fun get(): DLNACastManager = Loader.INSTANCE
    }

    private object Loader {
        val INSTANCE = DLNACastManager()
    }
}
