package com.wuming.tv.core.cloud

import java.util.Objects
import java.util.function.Supplier

class OpenDrivePreparedExternal private constructor(private var materializer: Supplier<String>?) {

    private var itemId: String? = null

    @Synchronized
    fun materialize(): String {
        if (itemId != null) return itemId!!
        val value = materializer!!.get()
        if (value.isBlank()) {
            throw IllegalArgumentException("External cloud episode is no longer available")
        }
        itemId = value
        materializer = null
        return itemId!!
    }

    companion object {
        @JvmStatic
        fun of(materializer: Supplier<String>): OpenDrivePreparedExternal =
            OpenDrivePreparedExternal(Objects.requireNonNull(materializer))
    }
}
