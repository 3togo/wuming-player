package com.wuming.tv.core.cloud

import com.wuming.tv.core.cache.ExpiringLruCache
import com.wuming.tv.core.cache.MemoryBudget
import com.wuming.tv.core.cache.Weights
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Host-owned instance of the shared bounded deferred-episode workflow. */
class DeferredCloudEpisodeCache<P : CloudStorageProvider> {
    private val memory = MemoryBudget.current()
    private val entries = ExpiringLruCache<String?, Entry<P>>(
        memory.scale(2048, 4096, 8192), TimeUnit.HOURS.toMillis(2), { it.weight }, memory.weightedBytes(0.02))

    private data class Entry<P>(val provider: P?, val prepared: OpenDrivePreparedExternal?, val weight: Long)
    data class Materialized<P>(val provider: P?, val itemId: String?)

    @Synchronized
    fun put(provider: P?, prepared: OpenDrivePreparedExternal?, retainedBytes: Long): String =
        UUID.randomUUID().toString().also { entries.put(it, Entry(provider, prepared, retainedBytes + 256L)) }

    @Synchronized
    fun materialize(providerId: String?, handle: String?): Materialized<P> {
        val entry = entries.get(handle)
        require(entry != null && entry.provider!!.id() == providerId) { "Deferred cloud episode expired; reopen the detail page" }
        return Materialized(entry.provider, entry.prepared!!.materialize())
    }

    companion object {
        @JvmStatic fun retainedBytes(flag: String?, payload: String?): Long = Weights.utf8(flag) + Weights.utf8(payload)
    }
}
