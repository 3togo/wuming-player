package com.wuming.android.tv.cloud.opendrive

import com.wuming.tv.core.cloud.DeferredCloudEpisodeCache

object OpenDriveExternalEpisodeCache {
    private val shared = DeferredCloudEpisodeCache<OpenDriveProvider>()
    @JvmStatic fun put(provider: OpenDriveProvider?, prepared: OpenDrivePreparedExternal?, retainedBytes: Long): String =
        shared.put(provider, prepared?.shared, retainedBytes)
    @JvmStatic fun materialize(providerId: String?, handle: String?): Materialized =
        shared.materialize(providerId, handle).let { Materialized(it.provider, it.itemId) }
    @JvmStatic fun retainedBytes(flag: String?, payload: String?): Long = DeferredCloudEpisodeCache.retainedBytes(flag, payload)
    @JvmRecord data class Materialized(val provider: OpenDriveProvider?, val itemId: String?)
}
