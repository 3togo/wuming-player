package com.wuming.tv.core.upnp

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.function.LongSupplier

/** In-memory, one-use QR challenge and bounded LAN peer grant. Never trusts HTTP forwarding headers. */
class QrPairingSession constructor(private val clock: LongSupplier) {
    private var challenge = ""
    private var peer = ""
    private var challengeDeadline = 0L
    private var grantDeadline = 0L

    constructor() : this(LongSupplier { System.nanoTime() / 1_000_000L })

    @Synchronized
    fun open(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val code = StringBuilder(64)
        for (byte in bytes) {
            code.append(Character.forDigit((byte.toInt() and 255) ushr 4, 16))
                .append(Character.forDigit(byte.toInt() and 15, 16))
        }
        challenge = code.toString()
        challengeDeadline = clock.asLong + QR_LIFETIME_MS
        return challenge
    }

    @Synchronized
    fun code(): String = if (clock.asLong < challengeDeadline) challenge else ""

    @Synchronized
    fun close() {
        challenge = ""
        challengeDeadline = 0
    }

    @Synchronized
    fun claim(code: String?, address: String?): Boolean {
        if (address.isNullOrEmpty() || code == null || code.length != 64 || code().isEmpty()) return false
        if (!MessageDigest.isEqual(
                challenge.toByteArray(StandardCharsets.US_ASCII),
                code.toByteArray(StandardCharsets.US_ASCII)
            )
        ) return false
        close()
        peer = address
        grantDeadline = clock.asLong + GRANT_LIFETIME_MS
        return true
    }

    @Synchronized
    fun allows(address: String?): Boolean =
        peer.isNotEmpty() && peer == address && clock.asLong < grantDeadline

    @Synchronized
    fun pairedPeer(): String = if (allows(peer)) peer else ""

    @Synchronized
    fun revoke() {
        close()
        peer = ""
        grantDeadline = 0
    }

    companion object {
        const val QR_LIFETIME_MS = 5 * 60 * 1000L
        const val GRANT_LIFETIME_MS = 2 * 60 * 60 * 1000L
    }
}
