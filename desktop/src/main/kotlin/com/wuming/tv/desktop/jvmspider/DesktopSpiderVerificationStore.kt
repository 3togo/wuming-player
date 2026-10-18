package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.catalog.CatalogCapability
import com.wuming.tv.core.catalog.CatalogVerification
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import java.security.MessageDigest
import java.util.prefs.Preferences

/** Only digests and capability timestamps are stored, never extension tokens or playback URLs. */
class DesktopSpiderVerificationStore(
    private val preferences: Preferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun read(identity: String): CatalogVerification = CatalogVerification(
        preferences.get(identity, "").split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) return@mapNotNull null
            val capability = runCatching { CatalogCapability.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val time = parts[1].toLongOrNull() ?: return@mapNotNull null
            capability to time
        }.toMap(),
    )

    @Synchronized
    fun record(identity: String, capabilities: Set<CatalogCapability>) {
        val time = now()
        val existing = read(identity)
        // Browsing can make many requests. Refresh each successful capability at most hourly.
        if (capabilities.all { capability ->
            existing.observedAt[capability]?.let { it <= time && time - it < 60 * 60 * 1000 } == true
        }) return
        val values = existing.observedAt.filterKeys { it in existing.fresh(time) }.toMutableMap()
        capabilities.forEach { values[it] = time }
        if (preferences.get(identity, null) == null) {
            val keys = preferences.keys().filter { it.startsWith("spiderEvidence.") }
            if (keys.size >= 256) {
                keys.minByOrNull { read(it).observedAt.values.maxOrNull() ?: 0L }?.let(preferences::remove)
            }
        }
        preferences.put(identity, values.entries.joinToString(",") { "${it.key}:${it.value}" })
        preferences.flush()
    }

    fun support(identity: String, indexProvider: Boolean): GuardedDesktopSupport? {
        val evidence = read(identity)
        return when {
            indexProvider && evidence.discoveryVerified(now()) -> GuardedDesktopSupport.VERIFIED_INDEX
            evidence.catalogVerified(now()) -> if (CatalogCapability.PLAYBACK_URL in evidence.fresh(now())) {
                GuardedDesktopSupport.VERIFIED_PLAYBACK_URL
            } else GuardedDesktopSupport.VERIFIED_CATALOG
            else -> null
        }
    }

    companion object {
        // Bump when host compatibility or verification rules change; old evidence then cannot qualify.
        const val RUNTIME_REVISION = "desktop-spider-3"
        fun identity(site: JvmSpiderSiteSpec, trust: GuardedSourceTrust, revision: String = RUNTIME_REVISION): String {
            val fields = listOf(revision, site.key, site.api, site.artifactLocation, site.extension,
                trust.sha256, trust.abi.configName) + site.categories
            val encoded = fields.joinToString("") { "${it.length}:$it" }
            val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "spiderEvidence.$digest"
        }
    }
}
