package com.wuming.android.tv.server.process

import com.wuming.android.tv.server.CastMediaAccess
import com.wuming.android.tv.utils.AppLog
import com.wuming.tv.core.playback.DashRelayManifest
import com.wuming.tv.core.playback.DashRelayPolicy
import okhttp3.ResponseBody
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/** DASH MPD manifest detection and URL rewriting for cast relay. */
internal class MpdPlaylistRewriter private constructor() {

    internal companion object {
        private const val TAG = "MpdPlaylistRewriter"
        private val LOOPBACK_URL = Pattern.compile("https?://127\\.0\\.0\\.1[^\\s<>\"']*")

        @JvmStatic
        fun canRewriteManifest(upstreamStatus: Int): Boolean = upstreamStatus in 200..299

        @JvmStatic
        fun isDash(url: String, mime: String, body: ResponseBody): Boolean {
            val lowerUrl = url.lowercase(Locale.US)
            val lowerMime = mime.lowercase(Locale.US)
            if (lowerUrl.contains(".mpd") || lowerMime.contains("dash+xml")) return true
            try {
                val preview = body.source().peek()
                if (preview.request(5)) {
                    val prefix = preview.readUtf8(5)
                    if (prefix.startsWith("<?xml")) {
                        preview.request(256)
                        val head = preview.readUtf8(preview.buffer.size.coerceAtMost(256L))
                        return head.contains("<MPD")
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Unable to inspect cast media response for DASH: ${e.message}")
            }
            return false
        }

        @JvmStatic
        fun rewriteManifest(manifest: String?, relayPath: String): String =
            rewriteManifest(manifest, relayPath, "manifest")

        @JvmStatic
        fun rewriteManifest(manifest: String?, relayPath: String, trace: String): String {
            if (manifest.isNullOrEmpty()) return ""
            val target = CastMediaAccess.getRelayTarget(relayPath)
            val media = DashRelayManifest.rewrite(
                manifest,
                target ?: "http://127.0.0.1/",
                java.util.function.Function<String, String> { child -> CastMediaAccess.childUrl(relayPath, child) },
                target != null && DashRelayPolicy.isBilibiliManifest(target),
            )
            var rewrittenCount = media.rewrittenUrls
            val matcher = LOOPBACK_URL.matcher(media.manifest)
            val output = StringBuffer(manifest.length + 256)
            while (matcher.find()) {
                val url = matcher.group()
                val childUrl = CastMediaAccess.childUrl(relayPath, url)
                if (url != childUrl) rewrittenCount++
                matcher.appendReplacement(output, Matcher.quoteReplacement(childUrl))
            }
            matcher.appendTail(output)
            var remoteReferences = 0
            val references = Pattern.compile("https?://[^\\s<>\"']+").matcher(manifest)
            while (references.find()) {
                val g = references.group()
                if (!g.startsWith("http://127.0.0.1") && !g.startsWith("https://127.0.0.1")) remoteReferences++
            }
            CastDebug.event(
                trace,
                "event=dash_manifest rewrittenUrls=$rewrittenCount" +
                    " removedVideoRepresentations=${media.removedVideoRepresentations}" +
                    " normalizedMetadataFields=${media.normalizedMetadataFields}" +
                    " untouchedHttpReferences=$remoteReferences" +
                    " changed=${!manifest.contentEquals(output)}" +
                    " note=references_include_metadata_not_proof_of_media_route",
            )
            return output.toString()
        }
    }
}
