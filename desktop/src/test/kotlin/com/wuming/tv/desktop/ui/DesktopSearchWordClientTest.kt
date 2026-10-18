package com.wuming.tv.desktop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A search suggestion request can be triggered by a keystroke that races with the panel being
 * closed. The pool is shut down at that point, so the request must be dropped instead of raising
 * [java.util.concurrent.RejectedExecutionException] on the caller (the EDT).
 */
class DesktopSearchWordClientTest {

    @Test
    fun emptyQueryAnswersImmediately() {
        val client = DesktopSearchWordClient()
        var answered = false
        try {
            client.requestSuggestions("   ") { answered = true }
        } finally {
            client.close()
        }
        assertTrue(answered)
    }

    @Test
    fun requestAfterCloseIsIgnored() {
        val client = DesktopSearchWordClient()
        client.close()
        var answered = false
        client.requestSuggestions("keyword") { answered = true }
        assertFalse(answered)
    }

    @Test
    fun closeIsIdempotent() {
        val client = DesktopSearchWordClient()
        client.close()
        client.close()
    }
}
