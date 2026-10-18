package com.wuming.android.tv.player.exo

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/** Retains extension renderers while caching exact-format video capability queries. */
internal class CapabilityRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        mode: Int,
        selector: MediaCodecSelector,
        fallback: Boolean,
        handler: Handler,
        listener: VideoRendererEventListener,
        joiningTime: Long,
        renderers: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(context, mode, selector, fallback, handler, listener, joiningTime, renderers)
        for (i in renderers.indices) {
            if (renderers[i].javaClass != MediaCodecVideoRenderer::class.java) continue
            renderers[i] =
                CachedVideoRenderer(
                    MediaCodecVideoRenderer
                        .Builder(context)
                        .setCodecAdapterFactory(getCodecAdapterFactory())
                        .setMediaCodecSelector(selector)
                        .setAllowedJoiningTimeMs(joiningTime)
                        .setEnableDecoderFallback(fallback)
                        .setEventHandler(handler)
                        .setEventListener(listener)
                        .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
                )
        }
    }

    private class CachedVideoRenderer(
        builder: MediaCodecVideoRenderer.Builder,
    ) : MediaCodecVideoRenderer(builder) {
        @Throws(MediaCodecUtil.DecoderQueryException::class)
        override fun supportsFormat(
            selector: MediaCodecSelector,
            format: Format,
        ): Int = VideoCapabilityCache.get().resolve(format) { super.supportsFormat(selector, format) }
    }
}
