package com.wuming.android.tv.server

import com.wuming.android.tv.ProductCore

object DlnaPairing {
    @JvmField
    val SESSION = QrPairingSession()

    @JvmStatic
    fun allow(method: String, socketPeer: String?): Boolean =
        com.wuming.tv.core.upnp.DlnaRequestPolicy.allows(method, SESSION.allows(socketPeer), ProductCore.getDeviceType() == 0)

    @JvmStatic
    @JvmName("allowsTvRequest")
    internal fun allowsTvRequest(method: String, paired: Boolean): Boolean =
        com.wuming.tv.core.upnp.DlnaRequestPolicy.allows(method, paired, true)
}
