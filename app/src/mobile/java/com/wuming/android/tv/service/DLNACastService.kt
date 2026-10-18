package com.wuming.android.tv.service

import com.wuming.android.tv.dlna.DLNAServiceConfiguration
import com.wuming.android.tv.utils.AppLog
import org.fourthline.cling.UpnpServiceConfiguration
import org.fourthline.cling.android.AndroidUpnpServiceImpl
import org.fourthline.cling.model.types.ServiceType
import org.fourthline.cling.model.types.UDAServiceType

class DLNACastService : AndroidUpnpServiceImpl() {

    override fun onCreate() {
        super.onCreate()
        try {
            AppLog.i(TAG, "onCreate: UPnP service started successfully")
        } catch (e: Exception) {
            AppLog.e(TAG, "onCreate: UPnP service startup failed", e)
        }
    }

    override fun createConfiguration(): UpnpServiceConfiguration =
        object : DLNAServiceConfiguration() {
            override fun getExclusiveServiceTypes(): Array<ServiceType> =
                arrayOf(
                    UDAServiceType("AVTransport", 1),
                    UDAServiceType("ConnectionManager", 1),
                    UDAServiceType("RenderingControl", 1)
                )
        }

    companion object {
        private const val TAG = "DLNACastService"
    }
}
