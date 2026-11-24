package com.wuming.android.tv.player.exo

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.wuming.tv.core.playback.CencHttpMedia
import com.wuming.tv.core.playback.CencPlaybackSessions
import java.io.IOException

/** Android streaming port. The shared transformer preserves offsets, so normal MP4 seeking works. */
class CencDataSource : BaseDataSource(true) {
    private var media: CencHttpMedia? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var buffer = ByteArray(0)
    private var cursor = 0

    override fun open(spec: DataSpec): Long {
        com.wuming.android.tv.utils.VideoNetworkPolicy.requireAllowed()
        android.util.Log.d("NativeCenc", "Opening media at offset=${spec.position}")
        transferInitializing(spec)
        val opened = CencPlaybackSessions.require(spec.uri.toString()).media
        android.util.Log.d("NativeCenc", "Metadata ready length=${opened.length}")
        if (spec.position > opened.length) throw IOException("Media position exceeds length")
        media = opened
        uri = spec.uri
        position = spec.position
        remaining = if (spec.length == C.LENGTH_UNSET.toLong()) opened.length - position
            else minOf(spec.length, opened.length - position)
        transferStarted(spec)
        return remaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val opened = media ?: throw IOException("Media source is closed")
        if (cursor == buffer.size) {
            com.wuming.android.tv.utils.VideoNetworkPolicy.requireAllowed()
            buffer = opened.read(position, minOf(remaining, 256L * 1024).toInt())
            cursor = 0
        }
        val count = minOf(length, buffer.size - cursor, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        buffer.copyInto(target, offset, cursor, cursor + count)
        cursor += count
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri
    override fun close() {
        if (media != null) transferEnded()
        media = null
        uri = null
        remaining = 0
        buffer = ByteArray(0)
        cursor = 0
    }
}
