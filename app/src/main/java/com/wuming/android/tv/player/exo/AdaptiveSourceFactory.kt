package com.wuming.android.tv.player.exo

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.DefaultSsChunkSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import com.wuming.tv.core.playback.PlaybackTransportPlan

/** Media3 port: mutable manifests bypass the runtime segment cache, including old cache entries.  */
object AdaptiveSourceFactory {
    @JvmStatic
    fun hlsDataSources(
        media: DataSource.Factory?,
        manifest: DataSource.Factory?
    ): HlsDataSourceFactory = HlsDataSourceFactory { type: Int ->
        (
            if (type ==
                C.DATA_TYPE_MANIFEST
            ) {
                manifest?.let { CapabilityManifestDataSource.Factory(it, true) }
            } else {
                media
            }
            )!!.createDataSource()
    }

    @JvmStatic
    fun create(
        kind: PlaybackTransportPlan.Kind,
        media: DataSource.Factory,
        manifest: DataSource.Factory?
    ): MediaSource.Factory? = when (kind) {
        PlaybackTransportPlan.Kind.HLS -> HlsMediaSource.Factory(
            hlsDataSources(
                media,
                manifest
            )
        )

        PlaybackTransportPlan.Kind.DASH -> DashMediaSource.Factory(
            DefaultDashChunkSource.Factory(
                media
            ),
            manifest
        )

        PlaybackTransportPlan.Kind.SMOOTH_STREAMING -> SsMediaSource.Factory(
            DefaultSsChunkSource.Factory(media),
            manifest
        )

        else -> null
    }
}
