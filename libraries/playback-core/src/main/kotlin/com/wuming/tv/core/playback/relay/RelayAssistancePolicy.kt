package com.wuming.tv.core.playback.relay

/** One desktop trial per playback selection, driven by sustained buffering rather than pairing. */
class RelayAssistancePolicy {
    enum class Action { NONE, REQUEST_DESKTOP, FALLBACK_DIRECT }
    private var bufferingSince: Long? = null
    private var attempted = false
    private var fellBack = false
    private var previousRelay = false

    fun reset() {
        bufferingSince = null
        attempted = false
        fellBack = false
        previousRelay = false
    }

    fun sample(nowMillis: Long, buffering: Boolean, playWhenReady: Boolean, relay: Boolean): Action {
        if (!buffering || !playWhenReady || previousRelay != relay) bufferingSince = null
        previousRelay = relay
        if (!buffering || !playWhenReady) return Action.NONE
        val since = bufferingSince ?: nowMillis.also { bufferingSince = it }
        if (nowMillis < since) { bufferingSince = nowMillis; return Action.NONE }
        if (relay && !fellBack && nowMillis - since >= RELAY_STALL_MILLIS) {
            fellBack = true
            attempted = true
            return Action.FALLBACK_DIRECT
        }
        if (!relay && !attempted && nowMillis - since >= DIRECT_STALL_MILLIS) {
            attempted = true
            return Action.REQUEST_DESKTOP
        }
        return Action.NONE
    }

    companion object {
        const val DIRECT_STALL_MILLIS = 8_000L
        const val RELAY_STALL_MILLIS = 12_000L
        const val REQUEST_BUDGET_MILLIS = 11_000L
        const val PREPARE_BUDGET_MILLIS = 7_000L
    }
}
