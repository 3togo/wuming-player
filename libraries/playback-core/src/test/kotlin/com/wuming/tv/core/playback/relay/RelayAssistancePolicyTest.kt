package com.wuming.tv.core.playback.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayAssistancePolicyTest {
    private val policy = RelayAssistancePolicy()
    private fun sample(time: Long, buffering: Boolean = true, playing: Boolean = true, relay: Boolean = false) =
        policy.sample(time, buffering, playing, relay)

    @Test fun healthyDirectPlaybackAndShortRebuffersNeverAskForHelp() {
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(0, buffering = false))
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(100))
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(7900))
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(8000, buffering = false))
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(9000))
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(16000))
    }

    @Test fun sustainedBufferingRequestsOnceAndNeverOscillatesAfterFallback() {
        sample(0)
        assertEquals(RelayAssistancePolicy.Action.REQUEST_DESKTOP, sample(8000))
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(9000))
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(10000, relay = true))
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(21000, relay = true))
        assertEquals(RelayAssistancePolicy.Action.FALLBACK_DIRECT, sample(22000, relay = true))
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(23000))
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(60000))
    }

    @Test fun pauseDoesNotCountAsSlowNetworkAndResumptionStartsANewStallWindow() {
        sample(0)
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(10000, playing = false))
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(20000))
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(27000))
        assertEquals(RelayAssistancePolicy.Action.REQUEST_DESKTOP, sample(28000))
    }

    @Test fun briefRelayLiveWindowRecoveryKeepsTheRelay() {
        sample(0, relay = true)
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(5000, buffering = false, relay = true))
        sample(20000, relay = true)
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(31000, relay = true))
    }

    @Test fun aNewSelectionResetsAttemptAndClockRollbackDoesNotTrigger() {
        sample(10000)
        assertEquals(RelayAssistancePolicy.Action.NONE, sample(100))
        assertEquals(RelayAssistancePolicy.Action.REQUEST_DESKTOP, sample(8100))
        policy.reset()
        sample(20000)
        assertEquals(RelayAssistancePolicy.Action.REQUEST_DESKTOP, sample(28000))
    }
}
