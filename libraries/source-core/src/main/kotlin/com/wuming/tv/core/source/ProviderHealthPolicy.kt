package com.wuming.tv.core.source

import java.util.HashMap

class ProviderHealthPolicy {

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun retryAfter(key: String, now: Long): Long {
        val entry = entries[key] ?: return 0
        if (expired(entry, now)) {
            entries.remove(key)
            return 0
        }
        if (entry.blockedUntil <= now) return 0
        return entry.blockedUntil - now
    }

    @Synchronized
    fun success(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun failure(key: String, now: Long) {
        entries.values.removeIf { entry -> expired(entry, now) }
        val entry = entries.computeIfAbsent(key) { Entry() }
        // Calls already in flight may fail after the circuit opens. They must
        // not consume attempts from the next recovery cycle or extend its wait.
        if (entry.blockedUntil > now) return
        entry.failures++
        entry.lastFailure = now
        if (entry.failures >= FAILURE_LIMIT) {
            entry.failures = 0
            entry.blockedUntil = now + COOLDOWN_MS
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun expired(entry: Entry, now: Long): Boolean {
        if (now < entry.lastFailure) return true // Wall clock was moved backwards.
        return if (entry.blockedUntil != 0L) entry.blockedUntil <= now
        else now - entry.lastFailure >= FAILURE_WINDOW_MS
    }

    private class Entry {
        var failures = 0
        var blockedUntil = 0L
        var lastFailure = 0L
    }

    companion object {
        const val FAILURE_LIMIT = 3
        const val COOLDOWN_MS = 60_000L
        const val FAILURE_WINDOW_MS = 45 * 60_000L
    }
}
