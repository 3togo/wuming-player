package com.wuming.tv.desktop.ui

/**
 * Suppresses accidental repeats of the same user request without delaying unrelated choices.
 * Swing invokes this from the event-dispatch thread, but synchronization also makes it safe for
 * callers outside that thread.
 */
internal class RepeatRequestGate(
    private val windowMillis: Long = 1_000,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val acceptedAt = LinkedHashMap<Any, Long>()

    @Synchronized
    fun accept(key: Any): Boolean {
        val now = clockMillis()
        val previous = acceptedAt[key]
        if (previous != null && (now < previous || now - previous < windowMillis)) return false

        acceptedAt[key] = now
        if (acceptedAt.size > MAX_TRACKED_REQUESTS) {
            val expiry = now - windowMillis
            acceptedAt.entries.removeIf { it.value <= expiry }
            while (acceptedAt.size > MAX_TRACKED_REQUESTS) {
                acceptedAt.remove(acceptedAt.keys.first())
            }
        }
        return true
    }

    private companion object {
        const val MAX_TRACKED_REQUESTS = 128
    }
}
