package com.wuming.tv.core.source

import java.util.Locale

object CloudSiteSelectionPolicy {
    @JvmStatic
    fun score(type: Int, api: String, jar: String, key: String, name: String, builtinKey: String, quark: Boolean): Int {
        if (type != 3 || !api.startsWith("csp_") || jar.isEmpty() || builtinKey == key) return 0
        val identity = (key + "\n" + name + "\n" + api).lowercase(Locale.ROOT)
        return when {
            identity.contains("mydriveguard") -> 300
            identity.contains("mydrive") || identity.contains("mdrive") -> 200
            quark -> 100
            else -> 0
        }
    }
    @JvmStatic fun <T> select(sites: List<T>, score: (T) -> Int): T? {
        var best: T? = null
        var bestScore = 0
        for (site in sites) { val value = score(site); if (value > bestScore) { best = site; bestScore = value } }
        return best
    }
}
