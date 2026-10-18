package com.wuming.android.tv.cloud.opendrive

import java.util.function.Supplier

class OpenDrivePreparedExternal private constructor(internal val shared: com.wuming.tv.core.cloud.OpenDrivePreparedExternal) {
    fun materialize(): String = shared.materialize()
    companion object {
        @JvmStatic fun of(materializer: Supplier<String>): OpenDrivePreparedExternal =
            OpenDrivePreparedExternal(com.wuming.tv.core.cloud.OpenDrivePreparedExternal.of(materializer))
    }
}
