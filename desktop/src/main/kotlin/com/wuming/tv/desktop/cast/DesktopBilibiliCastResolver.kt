package com.wuming.tv.desktop.cast

import com.wuming.tv.core.playback.BilibiliCastPlayback
import com.wuming.tv.desktop.cloud.SecretServiceCredentialStore
import com.wuming.tv.desktop.player.DesktopPlaybackHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Desktop account port; source recognition and progressive selection are shared with Android. */
internal object DesktopBilibiliCastResolver {
    private val credentials by lazy { SecretServiceCredentialStore() }
    fun resolve(location: String): BilibiliCastPlayback.Stream? = try {
        BilibiliCastPlayback.resolve(location, null) { url, headers ->
            val request = Request.Builder().url(url).apply {
                headers.forEach { (key, value) -> header(key, value) }
                credentials.read("bilibili").takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
            }.build()
            val call = DesktopPlaybackHttpClient.client.newCall(request)
            call.timeout().timeout(8, TimeUnit.SECONDS)
            call.execute().use { response ->
                check(response.isSuccessful)
                val bytes = response.body.byteStream().readNBytes(1_000_001)
                check(bytes.size <= 1_000_000)
                bytes.toString(Charsets.UTF_8)
            }
        }
    } catch (error: Exception) {
        java.util.logging.Logger.getLogger("BilibiliCast").warning("Progressive resolution unavailable: ${error.javaClass.simpleName}")
        null
    }
}
