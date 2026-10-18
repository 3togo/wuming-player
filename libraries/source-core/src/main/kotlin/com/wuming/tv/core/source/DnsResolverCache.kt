package com.wuming.tv.core.source

import java.net.InetAddress
import java.net.UnknownHostException

/** Resolver changes invalidate answers, including lookups that finish after the change. */
class DnsResolverCache(
    private val system: (String) -> List<InetAddress>,
    private val fallback: (String) -> List<InetAddress>,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMillis: Long = 10 * 60 * 1000L,
    private val maxEntries: Int = 100,
) {
    private class State(val resolver: ((String) -> List<InetAddress>)?) {
        val answers = LinkedHashMap<String, Answer>()
    }
    private data class Answer(val addresses: List<InetAddress>, val expiresAt: Long)
    private val lock = Any()
    private var state = State(null)

    init {
        require(ttlMillis > 0)
        require(maxEntries > 0)
    }

    /** Null means System DNS, including after a previously selected DoH resolver. */
    fun select(resolver: ((String) -> List<InetAddress>)?) = synchronized(lock) {
        state = State(resolver)
    }

    fun clear() = synchronized(lock) { state = State(state.resolver) }

    @Throws(UnknownHostException::class)
    fun lookup(hostname: String): List<InetAddress> {
        while (true) {
            val snapshot = synchronized(lock) {
                val answer = state.answers[hostname]
                if (answer != null && clock() < answer.expiresAt) return answer.addresses.toList()
                state.answers.remove(hostname)
                state
            }
            // Network calls never hold the lock used by Settings and other lookups.
            val result = try {
                checked(hostname, (snapshot.resolver ?: system)(hostname))
            } catch (primary: UnknownHostException) {
                try {
                    checked(hostname, fallback(hostname))
                } catch (secondary: UnknownHostException) {
                    if (secondary !== primary) secondary.addSuppressed(primary)
                    if (synchronized(lock) { state !== snapshot }) continue
                    throw secondary
                }
            }
            val answer = synchronized(lock) {
                if (state !== snapshot) null else {
                    state.answers[hostname] = Answer(result, clock() + ttlMillis)
                    while (state.answers.size > maxEntries) {
                        state.answers.remove(state.answers.keys.first())
                    }
                    result.toList()
                }
            }
            if (answer != null) return answer
        }
    }

    private fun checked(hostname: String, addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty()) throw UnknownHostException("No DNS answers for $hostname")
        return addresses.toList()
    }
}
