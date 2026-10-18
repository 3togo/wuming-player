package com.wuming.tv.core.upnp

import java.util.LinkedHashMap

/** Bounded receiver-side deduplication and stale-stop protection for app casting. */
object RemoteCastCommandTracker {
    const val SESSION_TTL_MILLIS = 10L * 60L * 1000L
    private const val MAX_SESSIONS = 64
    private val sessions = LinkedHashMap<String, Long>(16, 0.75f, true)
    private var activeSession = ""

    @JvmStatic
    @Synchronized
    fun accept(sessionId: String?, now: Long): Boolean {
        if (sessionId == null || sessionId.isJavaBlank()) return true
        prune(now)
        if (sessions.containsKey(sessionId)) return false
        sessions[sessionId] = now
        activeSession = sessionId
        while (sessions.size > MAX_SESSIONS) {
            val iterator = sessions.keys.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        return true
    }

    @JvmStatic
    @Synchronized
    fun canStop(sessionId: String?): Boolean =
        sessionId == null || sessionId.isJavaBlank() || sessionId == activeSession

    @JvmStatic
    @Synchronized
    fun stopped(sessionId: String?) {
        if (sessionId == null || sessionId.isJavaBlank() || sessionId == activeSession) activeSession = ""
    }

    @JvmStatic
    @Synchronized
    fun resetForTest() {
        sessions.clear()
        activeSession = ""
    }

    private fun prune(now: Long) {
        sessions.entries.removeIf { entry -> now >= entry.value && now - entry.value >= SESSION_TTL_MILLIS }
    }

    /** Preserve Java String.isBlank rather than Kotlin's broader Unicode-space definition. */
    private fun String.isJavaBlank(): Boolean = chars().allMatch { Character.isWhitespace(it) }
}
