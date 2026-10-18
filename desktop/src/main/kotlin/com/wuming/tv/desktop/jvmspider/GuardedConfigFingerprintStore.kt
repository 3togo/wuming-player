package com.wuming.tv.desktop.jvmspider

import java.util.prefs.Preferences

/** Tracks the last activated configuration fingerprint to avoid redundant guarded source activation. */
class GuardedConfigFingerprintStore(private val preferences: Preferences) {

    fun lastActivatedFingerprint(): String? = preferences.get("lastActivatedConfigSha256", null)

    fun rememberActivatedFingerprint(sha256: String) {
        preferences.put("lastActivatedConfigSha256", sha256)
        preferences.flush()
    }

    fun isSameAsLastActivated(sha256: String): Boolean = lastActivatedFingerprint() == sha256
}