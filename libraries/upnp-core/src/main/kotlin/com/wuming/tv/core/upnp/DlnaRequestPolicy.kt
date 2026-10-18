package com.wuming.tv.core.upnp

object DlnaRequestPolicy {
    @JvmStatic
    fun allows(method: String, paired: Boolean, pairingRequired: Boolean): Boolean =
        !pairingRequired || method == "GET" || method == "HEAD" || paired
}
