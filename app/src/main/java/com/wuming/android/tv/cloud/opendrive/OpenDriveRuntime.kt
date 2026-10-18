package com.wuming.android.tv.cloud.opendrive

object OpenDriveRuntime {

    @JvmStatic
    fun engine(): OpenDriveEngine = Holder.engine

    private object Holder {
        val engine = OpenDriveEngine()
    }
}
