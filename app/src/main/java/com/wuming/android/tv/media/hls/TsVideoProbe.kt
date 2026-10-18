package com.wuming.android.tv.media.hls

import com.wuming.tv.core.playback.HlsResolutionAdFilter.VideoInfo
import com.wuming.tv.core.playback.TsStreamProbe

/** Reads AVC dimensions and the first raw video PTS from a bounded MPEG-TS prefix.
 *  Delegates MPEG-TS parsing to [TsStreamProbe] in playback-core; SPS parsing is shared by every client. */
class TsVideoProbe private constructor() {
    companion object {
        const val MAX_BYTES: Int = TsStreamProbe.MAX_BYTES

        @JvmStatic
        fun read(data: ByteArray?): VideoInfo? {
            return TsStreamProbe.create().read(data)
        }
    }

}
