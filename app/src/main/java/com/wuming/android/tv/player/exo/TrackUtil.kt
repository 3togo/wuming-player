package com.wuming.android.tv.player.exo

import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.wuming.android.tv.bean.Track
import com.wuming.android.tv.player.PlayerHelper

open class TrackUtil {
    companion object {
        @JvmStatic fun count(
            tracks: Tracks,
            type: Int,
        ): Int = tracks.groups.filter { it.type == type }.sumOf { it.length }

        @JvmStatic fun reset(player: Player) {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .clearOverrides()
                    .build()
        }

        private fun find(
            player: Player,
            track: Track,
        ): TrackInfo? {
            if (track.format == null) return null
            for (trackGroup in player.currentTracks.groups) {
                if (trackGroup.type != track.type) continue
                for (i in 0 until trackGroup.length) {
                    if (track.format ==
                        PlayerHelper.describeFormat(trackGroup.getTrackFormat(i))
                    ) {
                        return TrackInfo(trackGroup, i)
                    }
                }
            }
            return null
        }

        @JvmStatic fun setTrackSelection(
            player: Player,
            tracks: List<Track>,
        ) {
            val mediaGroupMapByType = HashMap<Int, TrackGroup>()
            val selectedIndexMapByType = HashMap<Int, Int>()
            for (track in tracks) {
                val info = find(player, track) ?: continue
                val type = info.trackGroup.type
                mediaGroupMapByType[type] = info.trackGroup.mediaTrackGroup
                if (track.isSelected) selectedIndexMapByType[type] = info.trackIndex
            }
            val builder = player.trackSelectionParameters.buildUpon()
            mediaGroupMapByType.forEach { (type, mediaGroup) ->
                val selectedIndex = selectedIndexMapByType[type]
                val indices = if (selectedIndex != null) listOf(selectedIndex) else emptyList()
                builder.setOverrideForType(TrackSelectionOverride(mediaGroup, indices))
            }
            player.trackSelectionParameters = builder.build()
        }
    }

    private data class TrackInfo(
        val trackGroup: Tracks.Group,
        val trackIndex: Int,
    )
}
