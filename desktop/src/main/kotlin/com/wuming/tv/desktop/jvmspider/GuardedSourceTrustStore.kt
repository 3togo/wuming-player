package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.prefs.Preferences

data class GuardedSourceTrust(val sha256: String, val abi: GuardedSpiderAbi)

/** Remembers an explicit approval only for the exact provider identity and artifact digest. */
class GuardedSourceTrustStore(private val preferences: Preferences) {

    fun find(site: JvmSpiderSiteSpec): GuardedSourceTrust? {
        val parts = preferences.get(key(site), "").split(':', limit = 2)
        if (parts.size != 2 || !parts[0].matches(SHA256)) return null
        val abi = runCatching { GuardedSpiderAbi.parse(parts[1]) }.getOrNull() ?: return null
        return GuardedSourceTrust(parts[0], abi)
    }

    fun remember(site: JvmSpiderSiteSpec, sha256: String, abi: GuardedSpiderAbi) {
        require(sha256.matches(SHA256)) { "Trusted guarded artifact SHA-256 is invalid" }
        preferences.put(key(site), "$sha256:${abi.configName}")
        preferences.flush()
    }

    private fun key(site: JvmSpiderSiteSpec): String {
        val identity = "${site.key}\u0000${site.className}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
        return "guardedTrust.$digest"
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
