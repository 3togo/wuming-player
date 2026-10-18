package com.wuming.tv.core.upnp

import java.util.Collections
import java.util.IdentityHashMap

/** Exception types only: messages and stack traces can contain signed URLs or credentials. */
object UpnpFailureDiagnostic {
    @JvmStatic fun types(failure: Throwable?): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val types = mutableListOf<String>()
        var current = failure
        while (current != null && types.size < 8 && seen.add(current)) {
            types += current.javaClass.simpleName.ifEmpty { "Throwable" }
            current = current.cause
        }
        return types.joinToString(">").ifEmpty { "unavailable" }
    }
}
