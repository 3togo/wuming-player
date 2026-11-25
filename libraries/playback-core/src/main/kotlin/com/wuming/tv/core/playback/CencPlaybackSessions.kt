package com.wuming.tv.core.playback

import java.util.UUID

/** Process-local capabilities. Neither keys nor signed URLs belong in saved catalogue locators. */
object CencPlaybackSessions {
    private const val PREFIX = "wuming-cenc://"
    private val sessions = LinkedHashMap<String, Material>()

    class Material internal constructor(
        val url: String, val headers: Map<String, String>, private val key: ByteArray,
        internal val created: Long,
    ) {
        val media by lazy { CencHttpMedia(url, headers, key) }
        fun keyHex(): String = com.wuming.tv.core.base.Hex.encodeLower(key)
        override fun toString() = "CencPlaybackMaterial"
    }

    @JvmStatic @Synchronized
    fun register(url: String, headers: Map<String, String>, key: ByteArray): String {
        require(key.size == 16) { "Invalid CENC material" }
        val uri = java.net.URI(url)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null)
        prune()
        while (sessions.size >= 8) sessions.remove(sessions.keys.first())
        val id = PREFIX + UUID.randomUUID()
        sessions[id] = Material(url, headers.toMap(), key.clone(), System.nanoTime())
        return id
    }

    @JvmStatic fun matches(value: String?) = value?.startsWith(PREFIX) == true

    @JvmStatic @Synchronized
    fun require(value: String): Material {
        prune()
        return sessions[value] ?: throw IllegalStateException("Playback session expired; select the episode again")
    }

    private fun prune() {
        val now = System.nanoTime()
        sessions.entries.removeAll { now - it.value.created > 10L * 60 * 1_000_000_000 }
    }
}
