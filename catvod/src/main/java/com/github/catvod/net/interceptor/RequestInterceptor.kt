package com.github.catvod.net.interceptor

import android.util.Log
import com.github.catvod.utils.Prefers
import com.wuming.tv.core.source.GitHubMirror
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Interceptor.Chain
import okhttp3.Request
import okhttp3.Response

open class RequestInterceptor constructor() : Interceptor {
    private val authMap: ConcurrentHashMap<String?, String?>

    init {
        authMap = ConcurrentHashMap<String?, String?>()
    }

    open fun clear() {
        authMap.clear()
    }

    @Throws(IOException::class)
    override fun intercept(chain: Chain): Response {
        val request: Request = chain.request()
        val builder: Request.Builder = request.newBuilder()
        val url: HttpUrl = rewriteGithub(request.url)
        if (url !== request.url) builder.url(url)
        checkAuth(url, builder)
        return chain.proceed(builder.build())
    }

    private fun checkAuth(url: HttpUrl, builder: Request.Builder) {
        val host: String = url.host
        val auth: String? = url.queryParameter("auth")
        if (auth != null) {
            authMap.put(host, auth)
        } else if (authMap.containsKey(host)) {
            builder.url(
                url.newBuilder().addQueryParameter("auth", authMap.get(host)).build()
            )
        }
    }

    /**
     * Send GitHub content through the mirror this user's network measured, keeping the rules in
     * [GitHubMirror] shared with the desktop client. Playlists, segments and media always go direct:
     * the player needs ranges, seeking and its own timeouts, and a mirror in between breaks playback
     * in ways that look like a dead source.
     */
    private fun rewriteGithub(url: HttpUrl): HttpUrl {
        val request: String = url.toString()
        val rewritten = if (GitHubMirror.isNeverMirrored(request)) unwrap(request)
        else GitHubMirror.resolve(request, proxyPrefix)
        if (rewritten == request) return url
        return rewritten.toHttpUrlOrNull() ?: url
    }

    /** Take a media URL hidden behind someone else's mirror back to its publisher. */
    private fun unwrap(url: String): String {
        val target: String = GitHubMirror.canonical(url) ?: return url
        if (target != url) Log.d(TAG, "Unwrapping proxied media URL: $target")
        return target
    }

    // Keep generated static forwarders non-final so existing Java subclasses can hide them.
    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        private const val TAG = "RequestInterceptor"

        @get:JvmStatic
        open val mirrorHost: String
            /**
             * The mirror this device picked, remembered as a host so a settings list that changes
             * order cannot silently retarget it. A configuration's own mirror never overrides it.
             */
            get() = GitHubMirror.hostOf(
                Prefers.getString(GitHubMirror.HOST_KEY, null),
                Prefers.getInt(GitHubMirror.LEGACY_INDEX_KEY, GitHubMirror.DEFAULT_INDEX),
            )

        @get:JvmStatic
        open val proxyPrefix: String
            /**
             * The request prefix of [mirrorHost], like "https://gh-proxy.com", or empty for direct access.
             */
            get() = GitHubMirror.prefixOf(mirrorHost)
    }
}
