package com.wuming.android.tv.server.process

import com.wuming.android.tv.BuildConfig
import com.wuming.android.tv.utils.AppLog
import com.wuming.tv.core.playback.TransferDiagnosticStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/** Android logging port. Never emit URL paths, credentials, query values, or exception messages. */
object CastDebug {
    private val requests = AtomicLong()
    @JvmStatic fun route(url: String?): String = try {
        val path = URI(url ?: "").path ?: ""
        val token = Regex("^/cast-relay/([^/]+)").find(path)?.groupValues?.get(1)
        if (token == null) "non_relay" else java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "unknown" }

    @JvmStatic fun nextRequest(): Long = requests.incrementAndGet()
    @JvmStatic fun endpoint(url: String?): String = try {
        val uri = URI(url ?: "")
        "${uri.scheme ?: "unknown"}://${uri.host ?: "unknown"}"
    } catch (_: Exception) { "invalid-url" }

    @JvmStatic fun event(context: String, details: String) {
        if (BuildConfig.DEBUG) AppLog.d("CastDebug", "$context $details")
    }

    @JvmStatic fun stream(source: InputStream, length: Long, context: String): InputStream =
        if (BuildConfig.DEBUG) TransferDiagnosticStream(source, length, { event(context, it) }) else source
}
