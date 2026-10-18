package com.wuming.tv.core.playback

import java.util.IdentityHashMap

/** Bounded diagnostic categories; never serializes exception messages, URLs or headers. */
object FailureDiagnostics {
    @JvmStatic fun causeTypes(error: Throwable?): List<String> {
        val seen = IdentityHashMap<Throwable, Boolean>()
        val result = mutableListOf<String>()
        var current = error
        while (current != null && result.size < 16 && seen.put(current, true) == null) {
            result.add(current.javaClass.name)
            current = current.cause
        }
        return result
    }
}
