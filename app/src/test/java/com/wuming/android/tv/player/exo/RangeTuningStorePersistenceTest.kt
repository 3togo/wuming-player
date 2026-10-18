package com.wuming.android.tv.player.exo

import android.content.SharedPreferences
import com.wuming.tv.core.playback.RangeTuningProfile
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class RangeTuningStorePersistenceTest {
    @Test fun staleSessionCannotOverwriteOrClearAnotherSessionsSave() {
        val preferences = Preferences()
        val first = RangeTuningStore.Session(preferences.port, "profile_fixture")
        val stale = RangeTuningStore.Session(preferences.port, "profile_fixture")
        assertNull(first.load())
        assertNull(stale.load())
        first.save(profile())
        val saved = preferences.values["profile_fixture"]
        stale.save(profile(ranges = 8))
        stale.clear()
        assertEquals(saved, preferences.values["profile_fixture"])
        assertEquals(1L, RangeTuningProfile.decode(saved)!!.revision)
    }

    @Test fun fallbackIsConservativeAndOnlyClearRemovesTheExactProfile() {
        val preferences = Preferences()
        preferences.values["fallback_fixture"] = profile(confidence = 8, cooldown = 4).encode()
        val session = RangeTuningStore.Session(preferences.port, "profile_fixture", "fallback_fixture")
        val fallback = session.load()!!
        assertEquals(2, fallback.confidence)
        assertEquals(0, fallback.probeCooldown)
        session.save(fallback)
        assertTrue(preferences.values.containsKey("profile_fixture"))
        session.clear()
        assertFalse(preferences.values.containsKey("profile_fixture"))
        assertTrue(preferences.values.containsKey("fallback_fixture"))
    }

    @Test fun expiredAndMalformedRecordsAreRemovedWithoutTouchingOtherPreferences() {
        val preferences = Preferences()
        preferences.values["profile_fixture"] = profile(updatedAt = System.currentTimeMillis() - RangeTuningStore.PROFILE_TTL_MILLIS - 1000).encode()
        preferences.values["fallback_fixture"] = "broken"
        preferences.values["user_setting"] = "keep"
        assertNull(RangeTuningStore.Session(preferences.port, "profile_fixture", "fallback_fixture").load())
        assertEquals(mapOf("user_setting" to "keep"), preferences.values)
    }

    @Test fun pruningBoundsEachPrefixAndPreservesOtherPreferences() {
        val preferences = Preferences()
        val now = System.currentTimeMillis()
        for (index in 0..64) {
            preferences.values["profile_$index"] = profile(updatedAt = now - 1000 + index).encode()
            preferences.values["fallback_$index"] = profile(updatedAt = now - 1000 + index).encode()
        }
        preferences.values["user_setting"] = "keep"
        RangeTuningStore.Session(preferences.port, "profile_new", "fallback_new").save(profile(updatedAt = now))
        assertEquals(64, preferences.values.keys.count { it.startsWith("profile_") })
        assertEquals(64, preferences.values.keys.count { it.startsWith("fallback_") })
        assertFalse(preferences.values.containsKey("profile_0"))
        assertFalse(preferences.values.containsKey("fallback_0"))
        assertEquals("keep", preferences.values["user_setting"])
    }

    private fun profile(ranges: Int = 4, confidence: Int = 3, cooldown: Int = 0,
                        updatedAt: Long = System.currentTimeMillis()) =
        RangeTuningProfile(ranges, 1024L, 1_000_000L, confidence, updatedAt, 0, 1, 1, cooldown, 0L, 0L)

    /** In-memory port exercises real store ordering without an Android process. */
    private class Preferences {
        val values = LinkedHashMap<String, String>()
        val port = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0] as String] ?: args[1]
                "getAll" -> LinkedHashMap(values)
                "edit" -> editor()
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = LinkedHashMap<String, String?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString" -> { changes[args[0] as String] = args[1] as String?; proxy }
                    "remove" -> { changes[args[0] as String] = null; proxy }
                    "apply" -> {
                        for ((key, value) in changes) if (value == null) values.remove(key) else values[key] = value
                        null
                    }
                    else -> error("Unexpected editor operation: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
