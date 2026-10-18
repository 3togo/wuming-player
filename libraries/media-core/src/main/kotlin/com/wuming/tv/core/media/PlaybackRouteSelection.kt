package com.wuming.tv.core.media

import java.util.function.Supplier

/** Direct routes retain their current source identity; a host's canonical history
 * is consulted only for a route that explicitly supports it. */
object PlaybackRouteSelection {
    @JvmStatic
    fun select(direct: Boolean, current: String, historyIdentity: Supplier<String?>): String =
        if (direct) current else historyIdentity.get() ?: current
}
