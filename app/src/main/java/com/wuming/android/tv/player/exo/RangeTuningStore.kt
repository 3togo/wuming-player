package com.wuming.android.tv.player.exo

import android.content.Context
import android.content.SharedPreferences
import android.net.NetworkCapabilities
import android.net.Uri
import com.wuming.android.tv.App
import com.wuming.android.tv.utils.Hex
import com.wuming.android.tv.utils.PlatformCapabilities
import com.wuming.tv.core.playback.RangeProbeOptimizer
import com.wuming.tv.core.playback.RangeTuningProfile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Device-local, bounded history of range settings that proved useful in practice. */
object RangeTuningStore {
    @JvmField val PROFILE_TTL_MILLIS = TimeUnit.DAYS.toMillis(7)
    const val MAX_PROFILES = 64
    private const val PREFS = "range_tuning_v2"
    private const val PREFIX = "profile_"
    private const val FALLBACK_PREFIX = "fallback_"
    private const val MAX_CONFIDENCE = 8
    private val STORE_LOCK = Any()

    @JvmStatic
    fun session(profile: String?, mode: String?, uri: Uri?, contentBytes: Long): Session {
        val host = try { uri?.host?.lowercase(Locale.ROOT) ?: "unknown" }
        catch (_: RuntimeException) { "unknown" }
        return sessionForHost(profile, mode, host, contentBytes)
    }

    @JvmStatic
    fun session(profile: String?, mode: String?, url: String?, contentBytes: Long): Session {
        val host = try { java.net.URI.create(url).host ?: "unknown" }
        catch (_: RuntimeException) { "unknown" }
        return sessionForHost(profile, mode, host.lowercase(Locale.ROOT), contentBytes)
    }

    private fun sessionForHost(profile: String?, mode: String?, host: String, contentBytes: Long): Session {
        try {
            val capabilities = PlatformCapabilities.activeNetworkCapabilities(App.get())
            val transport = transport(capabilities)
            val metered = capabilities != null && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val roaming = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                PlatformCapabilities.isCellularRoaming(App.get(), capabilities)
            val vpn = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val bandwidthBucket = bandwidthBucketKbps(capabilities?.linkDownstreamBandwidthKbps ?: 0)
            val key = environmentKey(profile, mode, host, transport, metered, roaming, vpn, bandwidthBucket, contentSizeBucket(contentBytes))
            val fallbackKey = fallbackEnvironmentKey(profile, mode, host, transport, metered, roaming, vpn)
            val preferences = App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return Session(preferences, PREFIX + sha256(key), FALLBACK_PREFIX + sha256(fallbackKey))
        } catch (_: RuntimeException) {
            // Local JVM tests and very early process startup do not provide Android services.
            return Session(null, "")
        }
    }

    @JvmStatic
    fun session(profile: String?, uri: Uri?): Session = session(profile, "playback", uri, 0L)

    @JvmStatic
    fun environmentKey(profile: String?, mode: String?, host: String?, transport: String?, metered: Boolean,
                       roaming: Boolean, vpn: Boolean, bandwidthBucketKbps: Int, contentSizeBucketBytes: Long): String =
        "v2|" + clean(profile) + '|' + clean(mode) + '|' + clean(host) + '|' + clean(transport) +
            '|' + metered + '|' + roaming + '|' + vpn + '|' + maxOf(0, bandwidthBucketKbps) + '|' + maxOf(0L, contentSizeBucketBytes)

    @JvmStatic
    fun environmentKey(profile: String?, host: String?, transport: String?, metered: Boolean, roaming: Boolean,
                       vpn: Boolean, bandwidthBucketKbps: Int): String =
        environmentKey(profile, "playback", host, transport, metered, roaming, vpn, bandwidthBucketKbps, 0L)

    @JvmStatic
    fun fallbackEnvironmentKey(profile: String?, mode: String?, host: String?, transport: String?,
                               metered: Boolean, roaming: Boolean, vpn: Boolean): String =
        "fallback-v1|" + clean(profile) + '|' + clean(mode) + '|' + clean(host) + '|' + clean(transport) +
            '|' + metered + '|' + roaming + '|' + vpn

    @JvmStatic
    fun bandwidthBucketKbps(downstreamKbps: Int): Int {
        if (downstreamKbps <= 0) return 0
        val highest = Integer.highestOneBit(downstreamKbps)
        val next = if (highest > Int.MAX_VALUE / 2) highest else highest shl 1
        return if (downstreamKbps - highest < next - downstreamKbps) highest else next
    }

    @JvmStatic
    fun contentSizeBucket(contentBytes: Long): Long {
        if (contentBytes <= 0L) return 0L
        val highest = java.lang.Long.highestOneBit(contentBytes)
        if (highest <= 0L || highest > Long.MAX_VALUE / 2L) return highest
        val next = highest shl 1
        return if (contentBytes - highest < next - contentBytes) highest else next
    }

    @JvmStatic
    fun merge(previous: RangeTuningProfile?, ranges: Int, rangeBytes: Long, observedBps: Long, now: Long): RangeTuningProfile {
        val confidence = if (previous == null) 1 else minOf(MAX_CONFIDENCE, previous.confidence + 1)
        val smoothedBps = if (previous == null || previous.observedBps <= 0L) maxOf(0L, observedBps)
            else (previous.observedBps * 3L + maxOf(0L, observedBps)) / 4L
        return RangeTuningProfile(maxOf(1, ranges), maxOf(1L, rangeBytes), smoothedBps, confidence, maxOf(0L, now),
            RangeProbeOptimizer.AXIS_COUNT, 1, 1, 0, 0L, 0L)
    }

    @JvmStatic
    fun fresh(profile: RangeTuningProfile?, now: Long): Boolean = profile != null && profile.confidence > 0 &&
        profile.updatedAt > 0L && now >= profile.updatedAt && now - profile.updatedAt <= PROFILE_TTL_MILLIS

    private fun transport(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "unknown"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
    }

    private fun clean(value: String?): String = value?.trim { it <= ' ' }?.lowercase(Locale.ROOT)?.replace('|', '_') ?: ""
    private fun String.isJavaBlank(): Boolean = chars().allMatch { Character.isWhitespace(it) }

    private fun sha256(value: String): String = try {
        Hex.encodeLower(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))
    } catch (_: Exception) { Integer.toHexString(value.hashCode()) }

    class Session @JvmOverloads constructor(private val preferences: SharedPreferences?, private val key: String,
                                           private val fallbackKey: String = "") {
        private var loadedRevision = 0L
        private var loaded = false

        @Synchronized
        fun load(): RangeTuningProfile? {
            if (preferences == null || key.isJavaBlank()) return null
            synchronized(STORE_LOCK) {
                try {
                    val now = System.currentTimeMillis()
                    var profile = RangeTuningProfile.decode(preferences.getString(key, ""))
                    if (!fresh(profile, now)) {
                        preferences.edit().remove(key).apply()
                        profile = null
                    }
                    loadedRevision = revisionOf(profile)
                    loaded = true
                    if (profile != null || fallbackKey.isJavaBlank()) return profile
                    val fallback = RangeTuningProfile.decode(preferences.getString(fallbackKey, ""))
                    if (!fresh(fallback, now)) {
                        preferences.edit().remove(fallbackKey).apply()
                        return null
                    }
                    return asFallback(fallback!!)
                } catch (_: RuntimeException) { return null }
            }
        }

        @Synchronized
        fun success(ranges: Int, rangeBytes: Long, observedBps: Long) {
            if (preferences == null || key.isJavaBlank() || observedBps <= 0L) return
            synchronized(STORE_LOCK) {
                try {
                    val now = System.currentTimeMillis()
                    var current = RangeTuningProfile.decode(preferences.getString(key, ""))
                    if (!fresh(current, now)) current = null
                    val next = mergeSuccess(current, ranges, rangeBytes, observedBps, now)
                    preferences.edit().putString(key, next.encode()).apply()
                    saveFallback(preferences, fallbackKey, next, now)
                    loadedRevision = next.revision
                    loaded = true
                    prune(preferences, now)
                } catch (_: RuntimeException) { }
            }
        }

        @Synchronized
        fun save(profile: RangeTuningProfile?) {
            if (preferences == null || key.isJavaBlank() || profile == null) return
            synchronized(STORE_LOCK) {
                try {
                    val current = RangeTuningProfile.decode(preferences.getString(key, ""))
                    if (loaded && !revisionMatches(current, loadedRevision)) return
                    val revision = nextRevision(revisionOf(current))
                    val next = withRevision(profile, revision)
                    preferences.edit().putString(key, next.encode()).apply()
                    saveFallback(preferences, fallbackKey, next, System.currentTimeMillis())
                    loadedRevision = revision
                    loaded = true
                    prune(preferences, System.currentTimeMillis())
                } catch (_: RuntimeException) { }
            }
        }

        @Synchronized
        fun saveOrClear(profile: RangeTuningProfile?) {
            if (profile == null) clear() else save(profile)
        }

        @Synchronized
        fun clear() {
            if (preferences == null || key.isJavaBlank()) return
            synchronized(STORE_LOCK) {
                try {
                    val current = RangeTuningProfile.decode(preferences.getString(key, ""))
                    if (loaded && !revisionMatches(current, loadedRevision)) return
                    preferences.edit().remove(key).apply()
                    loadedRevision = 0L
                    loaded = true
                } catch (_: RuntimeException) { }
            }
        }

        @Synchronized
        fun failure() {
            if (preferences == null || key.isJavaBlank()) return
            try {
                val current = load()
                if (current == null || current.confidence <= 1) clear()
                else {
                    val next = RangeTuningProfile(current.ranges, current.rangeBytes, current.observedBps,
                        current.confidence - 1, System.currentTimeMillis(), current.nextAxis, current.countDirection,
                        current.sizeDirection, current.probeCooldown, current.probeNotBefore, current.revision)
                    save(next)
                }
            } catch (_: RuntimeException) { }
        }
    }

    @JvmStatic
    fun revisionMatches(current: RangeTuningProfile?, loadedRevision: Long): Boolean = revisionOf(current) == maxOf(0L, loadedRevision)
    private fun revisionOf(profile: RangeTuningProfile?): Long = if (profile == null) 0L else maxOf(0L, profile.revision)
    private fun nextRevision(revision: Long): Long = if (revision == Long.MAX_VALUE) Long.MAX_VALUE else revision + 1L

    @JvmStatic
    fun mergeSuccess(previous: RangeTuningProfile?, ranges: Int, rangeBytes: Long, observedBps: Long, now: Long): RangeTuningProfile =
        withRevision(merge(previous, ranges, rangeBytes, observedBps, now), nextRevision(revisionOf(previous)))

    private fun withRevision(profile: RangeTuningProfile, revision: Long): RangeTuningProfile =
        RangeTuningProfile(profile.ranges, profile.rangeBytes, profile.observedBps, profile.confidence,
            profile.updatedAt, profile.nextAxis, profile.countDirection, profile.sizeDirection,
            profile.probeCooldown, profile.probeNotBefore, revision)

    private fun prune(preferences: SharedPreferences, now: Long) {
        val values = preferences.all
        prunePrefix(preferences, values, PREFIX, now)
        prunePrefix(preferences, values, FALLBACK_PREFIX, now)
    }

    private fun prunePrefix(preferences: SharedPreferences, values: Map<String, *>, prefix: String, now: Long) {
        val profiles = ArrayList<Pair<String, RangeTuningProfile>>()
        for ((key, value) in values) {
            if (!key.startsWith(prefix) || value !is String) continue
            val profile = RangeTuningProfile.decode(value)
            if (!fresh(profile, now)) preferences.edit().remove(key).apply()
            else profiles.add(key to profile!!)
        }
        profiles.sortBy { it.second.updatedAt }
        val excess = maxOf(0, profiles.size - MAX_PROFILES)
        if (excess == 0) return
        val editor = preferences.edit()
        for (index in 0 until excess) editor.remove(profiles[index].first)
        editor.apply()
    }

    @JvmStatic
    fun asFallback(profile: RangeTuningProfile): RangeTuningProfile =
        RangeTuningProfile(profile.ranges, profile.rangeBytes, profile.observedBps, minOf(2, profile.confidence),
            profile.updatedAt, profile.nextAxis, profile.countDirection, profile.sizeDirection, 0, 0L, profile.revision)

    private fun saveFallback(preferences: SharedPreferences, key: String?, profile: RangeTuningProfile?, now: Long) {
        if (key == null || key.isJavaBlank() || profile == null) return
        val current = RangeTuningProfile.decode(preferences.getString(key, ""))
        if (fresh(current, now) && current!!.updatedAt > profile.updatedAt) return
        val revision = nextRevision(revisionOf(current))
        preferences.edit().putString(key, withRevision(profile, revision).encode()).apply()
    }
}
