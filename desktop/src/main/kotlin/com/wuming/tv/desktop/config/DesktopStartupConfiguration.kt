package com.wuming.tv.desktop.config

/** One source of truth for the initial URL and the Settings selection. */
internal object DesktopStartupConfiguration {
    private fun preferred(depot: DesktopDepot) =
        com.wuming.tv.core.source.config.BundledStartupPolicy.select(depot.vod.map {
            com.wuming.tv.core.source.config.BundledStartupPolicy.Source(it.name, it.location)
        }, "饭太硬")
    fun defaultLocation(depot: DesktopDepot): String =
        preferred(depot)?.location.orEmpty()

    /** The bundled default is authorized for automatic first-run provider setup. */
    fun automaticallyApprove(depot: DesktopDepot, location: String): Boolean =
        preferred(depot)?.let { location.trim() == it.location } == true

    fun location(depot: DesktopDepot, lastWorking: String, rememberedDepot: String?): String =
        lastWorking.trim().takeIf(String::isNotEmpty)
            ?: rememberedDepot?.trim()?.takeIf { remembered -> depot.vod.any { it.location == remembered } }
            ?: defaultLocation(depot)
}
