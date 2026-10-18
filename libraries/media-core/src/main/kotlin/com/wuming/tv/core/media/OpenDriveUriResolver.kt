package com.wuming.tv.core.media

import com.wuming.tv.core.cache.BoundedLruMap
import java.util.function.Supplier

fun interface OpenDriveUriResolver {
    fun resolve(uri: String?): PlaybackTarget?
    fun refresh(uri: String?): PlaybackTarget? = resolve(uri)
    fun failover(uri: String?): PlaybackTarget? = throw IllegalStateException("OpenDrive playback failover is unavailable")
}

/** Session reuse, refresh/failover and pipeline result policy shared by all hosts. */
class LazyOpenDriveUriResolver(private val factory: Supplier<OpenDrivePipeline>,
                              private val diagnostics: Diagnostics = Diagnostics { _, _, _ -> }) : OpenDriveUriResolver {
    fun interface Diagnostics { fun invalid(uri: String?, status: String, message: String) }
    @Volatile private var value: OpenDrivePipeline? = null
    private val sessions: MutableMap<String?, OpenDrivePlaybackSession?> = BoundedLruMap.create(32)

    override fun resolve(uri: String?): PlaybackTarget? {
        val session = synchronized(sessions) {
            sessions[uri] ?: pipeline().openSession(uri).orElse(null)?.also { sessions[uri] = it }
        }
        if (session != null) return session.target()
        val result = pipeline().prepare(uri)
        if (result.status == OpenDrivePipeline.Result.Status.READY) return result.target
        diagnostics.invalid(uri, result.status.name, result.message)
        require(result.status != OpenDrivePipeline.Result.Status.INVALID) { result.message.ifBlank { "OpenDrive URI is invalid" } }
        check(result.status != OpenDrivePipeline.Result.Status.NOT_FOUND) { "OpenDrive resource was not found" }
        throw IllegalStateException("OpenDrive resource cannot be played")
    }

    override fun refresh(uri: String?): PlaybackTarget = synchronized(sessions) {
        var session = sessions[uri]
        if (session == null) {
            diagnostics.invalid(uri, "session_null", "refresh: session not found")
            session = pipeline().openSession(uri).orElse(null)?.also { sessions[uri] = it }
        }
        checkNotNull(session) { "OpenDrive session was not found" }.refresh()
    }

    override fun failover(uri: String?): PlaybackTarget = synchronized(sessions) {
        val session = sessions[uri]
        if (session == null) diagnostics.invalid(uri, "session_null", "failover: session not found")
        checkNotNull(session) { "OpenDrive session was not found" }.failover()
    }

    private fun pipeline(): OpenDrivePipeline = value ?: synchronized(this) {
        value ?: factory.get().also { value = it }
    }
}
