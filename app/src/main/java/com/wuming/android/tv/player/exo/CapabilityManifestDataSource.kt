package com.wuming.android.tv.player.exo

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UriUtil
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.wuming.android.tv.App
import com.wuming.tv.core.playback.VideoRenditionPolicy
import com.wuming.tv.core.playback.VideoRenditionPolicy.Support
import com.wuming.tv.core.playback.hls.HlsVariantFilter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Filters advertised HLS variants before Media3 requests rendition playlists or segments. */
internal class CapabilityManifestDataSource(
    private val upstream: DataSource,
    private val manifestOnly: Boolean,
) : DataSource {
    class Factory(private val upstream: DataSource.Factory, private val manifestOnly: Boolean) : DataSource.Factory {
        override fun createDataSource(): DataSource = CapabilityManifestDataSource(upstream.createDataSource(), manifestOnly)
    }

    private var prefix: ByteArrayInputStream? = null
    private var passthrough = true

    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun open(dataSpec: DataSpec): Long {
        prefix = null
        passthrough = true
        val length = upstream.open(dataSpec)
        val type = upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.firstOrNull().orEmpty().lowercase()
        val manifest = manifestOnly || upstream.uri?.path.orEmpty().endsWith(".m3u8", true) ||
            type.contains("mpegurl")
        if (!manifest || dataSpec.position != 0L || length > MAX_MANIFEST_BYTES) return length
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= MAX_MANIFEST_BYTES) {
            val count = upstream.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) {
                passthrough = false
                break
            }
            if (count > 0) output.write(buffer, 0, count)
        }
        val original = output.toByteArray()
        // Oversized manifests retain streaming behavior without unbounded buffering.
        val filtered = if (passthrough) original else filter(App.get(), upstream.uri ?: dataSpec.uri, original)
        prefix = ByteArrayInputStream(filtered)
        return if (passthrough) length else filtered.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val count = prefix?.read(buffer, offset, length) ?: -1
        if (count >= 0) return count
        return if (passthrough) upstream.read(buffer, offset, length) else C.RESULT_END_OF_INPUT
    }

    override fun close() {
        prefix = null
        passthrough = true
        upstream.close()
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 1024 * 1024

        fun filter(context: Context, uri: Uri, bytes: ByteArray): ByteArray {
            return filter(uri, bytes) { assess(context, it) }
        }

        fun filter(uri: Uri, bytes: ByteArray, evidence: (Format) -> Support): ByteArray {
            val text = bytes.toString(Charsets.UTF_8)
            if (!text.contains("#EXT-X-STREAM-INF:")) return bytes
            val playlist = HlsPlaylistParser().parse(uri, ByteArrayInputStream(bytes)) as? HlsMultivariantPlaylist
                ?: return bytes
            if (playlist.variants.isEmpty()) return bytes
            val support = playlist.variants.map { variant ->
                val format = if (playlist.sessionKeyDrmInitData.isEmpty()) variant.format else
                    variant.format.buildUpon().setDrmInitData(playlist.sessionKeyDrmInitData.first()).build()
                evidence(format)
            }
            val eligible = VideoRenditionPolicy.eligibleIndices(support, true)
            if (eligible.isEmpty()) {
                throw UnsupportedVideoException(playlist.variants.first().format, C.FORMAT_UNSUPPORTED_SUBTYPE,
                    UnsupportedVideoException.Reason.NO_COMPATIBLE_RENDITION)
            }
            if (eligible.size == playlist.variants.size) return bytes
            val allowed = eligible.map { playlist.variants[it].url }.toSet()
            val result = HlsVariantFilter.filter(text) { raw ->
                var expanded = raw
                for ((name, value) in playlist.variableDefinitions) expanded = expanded.replace("{\$${name}}", value)
                UriUtil.resolveToUri(playlist.baseUri, expanded) in allowed
            }
            // Counts only: signed media paths, query values and credentials stay private.
            com.wuming.android.tv.utils.AppLog.i("VideoCapabilities",
                "HLS compatible variants=${eligible.size}/${playlist.variants.size}")
            return result.toByteArray(Charsets.UTF_8)
        }

        private fun assess(context: Context, original: Format): Support {
            if (HdrDisplayChecker.shouldRejectHdrFormat(context, original)) return Support.UNSUPPORTED
            // Secure decoder requirements are resolved by Media3 once DRM is known.
            if (original.drmInitData != null) return Support.UNKNOWN
            val codecs = Util.getCodecsOfType(original.codecs, C.TRACK_TYPE_VIDEO)
            val mime = MimeTypes.getVideoMediaMimeType(codecs) ?: return Support.UNKNOWN
            val format = original.buildUpon().setSampleMimeType(mime).setCodecs(codecs).build()
            VideoCapabilityCache.get().savedSupport(format)?.let { return it }
            return try {
                val decoders = MediaCodecUtil.getDecoderInfos(mime, false, false)
                when {
                    decoders.any { it.isFormatSupported(context, format) } -> Support.SUPPORTED
                    decoders.any { it.isFormatFunctionallySupported(context, format) } -> Support.EXCEEDS
                    else -> Support.UNSUPPORTED
                }
            } catch (_: MediaCodecUtil.DecoderQueryException) {
                Support.UNKNOWN
            }
        }
    }
}
