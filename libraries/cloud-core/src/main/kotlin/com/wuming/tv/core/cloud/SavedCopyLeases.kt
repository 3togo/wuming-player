package com.wuming.tv.core.cloud

import java.util.UUID
import java.util.function.LongSupplier

/** Process-local handoff and active-user protection. Never stores credentials or media URLs. */
class SavedCopyLeases(private val clock: LongSupplier, private val handoffMillis: Long) {

    class Key(val provider: String, val account: String, val file: String) {
        init {
            if (provider.isBlank() || account.isBlank() || file.isBlank())
                throw IllegalArgumentException("Copy identity is required")
        }

        @JvmName("provider")
        fun providerAccessor(): String = provider
        @JvmName("account")
        fun accountAccessor(): String = account
        @JvmName("file")
        fun fileAccessor(): String = file

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key) return false
            return provider == other.provider && account == other.account && file == other.file
        }

        override fun hashCode(): Int = 31 * (31 * provider.hashCode() + account.hashCode()) + file.hashCode()
    }

    private class Entry(val key: Key, val deadline: Long) {
        var users: Int = 0
    }

    fun interface Delete {
        @Throws(Exception::class)
        fun run(): Boolean
    }

    private val entries = HashMap<String, Entry>()
    private val deleting = HashSet<Key>()

    @Synchronized
    fun stage(key: Key): String {
        prune()
        if (deleting.contains(key)) throw IllegalStateException("Copy cleanup is in progress")
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(key, clock.asLong + handoffMillis)
        return token
    }

    @Synchronized
    fun pin(token: String): AutoCloseable {
        prune()
        val entry = entries[token] ?: return AutoCloseable { }
        if (deleting.contains(entry.key)) return AutoCloseable { }
        entry.users++
        return object : AutoCloseable {
            var closed = false
            override fun close() {
                synchronized(this@SavedCopyLeases) {
                    if (closed) return
                    closed = true
                    if (--entry.users == 0) entries.remove(token, entry)
                }
            }
        }
    }

    @Synchronized
    fun busy(provider: String, file: String): Boolean {
        prune()
        return entries.values.any { it.key.provider == provider && it.key.file == file }
    }

    /** Previous-source handoff may retire unused results, but never an active player. */
    @Synchronized
    fun retire(provider: String, file: String) {
        entries.values.removeIf { it.users == 0 && it.key.provider == provider && it.key.file == file }
    }

    @Throws(Exception::class)
    fun delete(key: Key, action: Delete): Boolean {
        synchronized(this) {
            prune()
            if (deleting.contains(key) || busy(key.provider, key.file)) return false
            deleting.add(key)
        }
        try { return action.run() }
        finally { synchronized(this) { deleting.remove(key) } }
    }

    private fun prune() {
        val now = clock.asLong
        entries.values.removeIf { it.users == 0 && it.deadline <= now }
    }
}
