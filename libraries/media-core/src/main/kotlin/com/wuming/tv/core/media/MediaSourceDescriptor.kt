package com.wuming.tv.core.media

import java.util.Collections
import java.util.LinkedHashMap

/** Provider-neutral source metadata produced by a bounded input decoder. */
class MediaSourceDescriptor(
    adapterId: String,
    val locatorVersion: Int,
    locator: Map<String, String>?,
    fingerprint: String,
    priority: Int,
    val persistable: Boolean,
    credentialRef: String?
) {
    val adapterId: String = adapterId
    val locator: Map<String, String> = immutableLocator(locator)
    val fingerprint: String = fingerprint
    val priority: Int = priority.coerceIn(-1000, 1000)
    val credentialRef: String = credentialRef ?: ""

    constructor(adapterId: String, locatorVersion: Int,
                locator: Map<String, String>?, fingerprint: String,
                priority: Int, persistable: Boolean) : this(adapterId, locatorVersion, locator, fingerprint, priority, persistable, "")

    init {
        if (!validAdapter(this.adapterId)) throw IllegalArgumentException("Media adapter ID is invalid")
        if (locatorVersion < 1) throw IllegalArgumentException("Media locator version is invalid")
        if (!validFingerprint(this.fingerprint)) throw IllegalArgumentException("Media source fingerprint is invalid")
        if (this.credentialRef.isNotEmpty()
            && !this.credentialRef.matches(Regex("vault:[a-z0-9][a-z0-9/._-]{1,255}"))) {
            throw IllegalArgumentException("Media credential reference is invalid")
        }
    }

    @JvmName("adapterId")
    fun adapterIdAccessor(): String = adapterId
    @JvmName("locatorVersion")
    fun locatorVersionAccessor(): Int = locatorVersion
    @JvmName("locator")
    fun locatorAccessor(): Map<String, String> = locator
    @JvmName("fingerprint")
    fun fingerprintAccessor(): String = fingerprint
    @JvmName("priority")
    fun priorityAccessor(): Int = priority
    @JvmName("persistable")
    fun persistableAccessor(): Boolean = persistable
    @JvmName("credentialRef")
    fun credentialRefAccessor(): String = credentialRef

    companion object {
        private fun validAdapter(value: String): Boolean =
            value.matches(Regex("[a-z0-9][a-z0-9._-]{1,127}"))

        private fun validFingerprint(value: String): Boolean =
            value.matches(Regex("sha256:[a-f0-9]{64}"))

        private fun immutableLocator(source: Map<String, String>?): Map<String, String> {
            if (source == null) throw NullPointerException("Media source locator is required")
            if (source.isEmpty() || source.size > 32) {
                throw IllegalArgumentException("Media source locator is invalid")
            }
            val copy = LinkedHashMap<String, String>()
            for ((key, value) in source) {
                if (!key.matches(Regex("[a-z][a-zA-Z0-9_]{0,63}"))
                    || value.length > 8192 || hasControl(value)) {
                    throw IllegalArgumentException("Media source locator is invalid")
                }
                copy[key] = value
            }
            return Collections.unmodifiableMap(copy)
        }

        private fun hasControl(value: String): Boolean {
            for (c in value) if (c.isISOControl()) return true
            return false
        }
    }
}
