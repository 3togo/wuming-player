package com.wuming.tv.core.design.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shared bounded feed lookup; hosts supply HTTP configuration, preferences and diagnostics. */
class UpdateFeedClient(
    private val preferredHost: () -> String?,
    private val diagnostics: Diagnostics = object : Diagnostics {}
) {
    interface Diagnostics {
        fun debug(message: String) {}
        fun warning(message: String) {}
    }
    @Throws(Exception::class)
    fun check(feeds: List<String>, repository: String, channel: String, mode: String, abi: String,
              packageName: String, installedCode: Long, http: OkHttpClient): UpdateArtifact? {
        // A source snapshot has no publishing channel until its maintainer configures one.
        if (repository.isBlank()) return null
        require(feeds.isNotEmpty() && feeds.size <= 2)
        val executor = Executors.newFixedThreadPool(feeds.size)
        val results = ExecutorCompletionService<Pair<String, UpdateArtifact?>>(executor)
        val calls = feeds.map { http.newCall(Request.Builder().url(it).build()) }
        val futures = calls.mapIndexed { index, call -> results.submit(java.util.concurrent.Callable {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Feed HTTP ${response.code}")
                val body = response.body
                // Bound metadata memory even if a host returns the wrong resource.
                val source = body.source()
                source.request(1024 * 1024 + 1L)
                if (source.buffer.size > 1024 * 1024) throw IOException("Update feed too large")
                val artifact = UpdateManifest.select(body.string(), repository, channel, mode, abi, packageName, installedCode)
                Pair(feeds[index], artifact)
            }
        }) }
        var valid = 0
        val artifacts = mutableListOf<UpdateArtifact>()
        val reachable = mutableSetOf<String>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9)
        try {
            repeat(feeds.size) {
                val remaining = deadline - System.nanoTime()
                val done = if (remaining > 0) results.poll(remaining, TimeUnit.NANOSECONDS) else null
                if (done != null) try {
                    val (feed, artifact) = done.get()
                    valid++
                    reachable += if (URI(feed).host == "raw.githubusercontent.com") "github.com" else URI(feed).host
                    if (artifact != null) artifacts += artifact
                    diagnostics.debug("Feed OK: ${URI(feed).host}, candidate=${artifact?.code ?: "current"}")
                } catch (error: java.util.concurrent.ExecutionException) {
                    diagnostics.warning("Feed unavailable: ${error.cause?.message}")
                }
            }
            if (valid == 0) throw IOException("All update feeds unavailable")
            val preferred = preferredHost()
            val selected = UpdateManifest.newest(artifacts, if (preferred in reachable) preferred!! else "")
            diagnostics.debug("Selected: ${selected?.code ?: "current"}, mirrors=${selected?.urls?.size ?: 0}")
            return selected
        } finally {
            calls.forEach { it.cancel() }
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
    }
}
