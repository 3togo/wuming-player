package com.wuming.android.tv.player.offline

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.wuming.android.tv.App
import com.wuming.android.tv.service.PlaybackDownloadService
import com.wuming.android.tv.utils.Hex
import com.wuming.android.tv.utils.Hex.encodeLower
import com.wuming.tv.core.playback.PlaybackTransportPlan
import com.wuming.tv.core.playback.PlaybackTransportPlan.CachePolicy
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Explicit, resumable download commands for public direct/adaptive links.  */
@UnstableApi
object PlaybackDownloads {
    private val MIN_FREE_RESERVE_BYTES = 512L * 1024L * 1024L

    /**
     * Returns whether a URL can be handed to Media3's persistent downloader without losing
     * provider authentication or signed-URL refresh semantics.
     */
    @JvmStatic
    fun supported(url: String?, mimeType: String?, headers: Map<String, String>?): Boolean =
        isSupported(url, PlaybackTransportPlan.Companion.parse(url, mimeType, headers))

    @JvmStatic
    private fun isSupported(url: String?, plan: PlaybackTransportPlan): Boolean =
        !plan.refreshable() && !plan.credentialsAccessor() &&
            (plan.kindAccessor() == PlaybackTransportPlan.Kind.PROGRESSIVE || plan.adaptive()) &&
            !isLoopback(url)

    @JvmStatic
    fun enqueue(
        url: String,
        mimeType: String?,
        headers: Map<String, String>?,
        expectedBytes: Long
    ): String {
        val plan: PlaybackTransportPlan =
            PlaybackTransportPlan.Companion.parse(url, mimeType, headers)
        require(isSupported(url, plan)) {
            "This link requires live credentials or refresh and cannot be downloaded safely"
        }
        requireStorage(expectedBytes)
        val id: String = id(url)
        val builder: DownloadRequest.Builder = DownloadRequest.Builder(id, Uri.parse(url))
            .setMimeType(mimeType)
        if (plan.kindAccessor() == PlaybackTransportPlan.Kind.PROGRESSIVE && plan.cacheKeyAccessor().isNotEmpty()) {
            builder.setCustomCacheKey(plan.cacheKeyAccessor())
        }
        if (plan.networkHeadersAccessor().isNotEmpty()) {
            builder.setData(AuthHeaderRegistry.serialize(plan.networkHeadersAccessor()))
            AuthHeaderRegistry.register(url, plan.networkHeadersAccessor())
        }
        val request: DownloadRequest = builder.build()
        DownloadService.sendAddDownload(
            App.get(),
            PlaybackDownloadService::class.java,
            request,
            true
        )
        return id
    }

    @JvmStatic
    fun remove(id: String?) {
        require(
            !(
                id == null || id.codePoints().allMatch {
                    Character.isWhitespace(it)
                }
                )
        ) { "Download id must not be empty" }
        DownloadService.sendRemoveDownload(
            App.get(),
            PlaybackDownloadService::class.java,
            id,
            false
        )
    }

    @JvmStatic
    fun pauseAll() {
        DownloadService.sendPauseDownloads(App.get(), PlaybackDownloadService::class.java, false)
    }

    @JvmStatic
    fun resumeAll() {
        DownloadService.sendResumeDownloads(App.get(), PlaybackDownloadService::class.java, true)
    }

    @JvmStatic
    fun hasStorage(availableBytes: Long, expectedBytes: Long): Boolean {
        if (availableBytes <= MIN_FREE_RESERVE_BYTES || expectedBytes < 0L) return false
        // Subtract before comparing so a malicious or corrupt size cannot overflow long.
        return expectedBytes < availableBytes - MIN_FREE_RESERVE_BYTES
    }

    @JvmStatic
    private fun requireStorage(expectedBytes: Long) {
        val directory: File = OfflinePlaybackStore.directory()
        val volume: File? = if (directory.exists()) directory else directory.getParentFile()
        val available: Long = if (volume == null) 0L else volume.getUsableSpace()
        check(hasStorage(available, expectedBytes)) { "Not enough storage for an offline download" }
    }

    @JvmStatic
    private fun id(url: String): String {
        try {
            val digest: ByteArray = MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray(StandardCharsets.UTF_8))
            return "offline-" + encodeLower(digest)
        } catch (impossible: Exception) {
            return "offline-" + Integer.toHexString(url.hashCode())
        }
    }

    private fun isLoopback(url: String?): Boolean {
        if (url == null) return false
        return try {
            val host = URI.create(url).host
            host != null && ("localhost".equals(host, ignoreCase = true) || "127.0.0.1" == host || "::1" == host)
        } catch (_: Exception) { false }
    }
}
