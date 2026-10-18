package com.wuming.android.tv.player.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException

class RelayRequestTest {
    private val client = OkHttpClient()
    private fun call() = client.newCall(Request.Builder().url("https://fixture.test/relay").build())

    @Test fun recoveryOrTeardownCancelsTheOwnedHttpCall() {
        val lifetime = DesktopRelayClient.RelayRequest()
        val owned = call()
        val unrelated = call()
        lifetime.attach(owned)
        lifetime.close()
        lifetime.close()
        assertTrue(owned.isCanceled())
        assertFalse(unrelated.isCanceled())
    }

    @Test fun aLateWorkerCannotAttachAfterItsPlaybackWasReplaced() {
        val lifetime = DesktopRelayClient.RelayRequest()
        lifetime.close()
        val late = call()
        assertThrows(InterruptedIOException::class.java) { lifetime.attach(late) }
        assertTrue(late.isCanceled())
    }

    @Test fun interruptedPreparationNeverStartsANetworkRequest() {
        val lifetime = DesktopRelayClient.RelayRequest()
        val late = call()
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) { lifetime.attach(late) }
            assertTrue(late.isCanceled())
        } finally { Thread.interrupted() }
    }
}
