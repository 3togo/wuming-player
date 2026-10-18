package com.wuming.android.tv.server

import java.util.function.LongSupplier

/** Android API adapter; challenge and peer-grant state live in upnp-core. */
class QrPairingSession internal constructor(clock: LongSupplier) {
    private val shared = com.wuming.tv.core.upnp.QrPairingSession(clock)
    constructor() : this(LongSupplier { System.nanoTime() / 1_000_000L })
    fun open(): String = shared.open()
    fun code(): String = shared.code()
    fun close() = shared.close()
    fun claim(code: String?, address: String?): Boolean = shared.claim(code, address)
    fun allows(address: String?): Boolean = shared.allows(address)
    fun pairedPeer(): String = shared.pairedPeer()
    fun revoke() = shared.revoke()
    companion object {
        const val QR_LIFETIME_MS = com.wuming.tv.core.upnp.QrPairingSession.QR_LIFETIME_MS
        const val GRANT_LIFETIME_MS = com.wuming.tv.core.upnp.QrPairingSession.GRANT_LIFETIME_MS
    }
}
