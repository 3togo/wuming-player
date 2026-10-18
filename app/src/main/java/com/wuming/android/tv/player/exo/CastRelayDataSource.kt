package com.wuming.android.tv.player.exo

import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import com.wuming.tv.core.playback.CastRelayTransfer
import com.wuming.tv.core.playback.OpenDrivePlaybackHints
import com.wuming.tv.core.playback.PlaybackTransportPlan
import java.io.InputStream

/** Adapts the same network/range sources used by local playback to the LAN HTTP response. */
object CastRelayDataSource {
    @JvmStatic
    fun open(url: String, plan: PlaybackTransportPlan, slice: CastRelayTransfer.Slice): InputStream {
        val network = MediaSourceFactory.buildDataSourceFactory(url, plan)
        val factory = if (plan.parallelProfile == OpenDrivePlaybackHints.PROFILE_ALIPAN) network
            else ParallelRangeDataSource.Factory(network, slice.total, plan.parallelProfile)
        val source = factory.createDataSource()
        val stream = DataSourceInputStream(source, DataSpec.Builder().setUri(url)
            .setPosition(slice.start).setLength(slice.length).build())
        try {
            stream.open()
            return stream
        } catch (failure: Exception) {
            try { source.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
