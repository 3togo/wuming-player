package com.wuming.tv.desktop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * A search suggestion request can be triggered by a keystroke that races with the panel being
 * closed. The pool is shut down at that point, so the request must be dropped instead of raising
 * [java.util.concurrent.RejectedExecutionException] on the caller (the EDT).
 */
class DesktopSearchWordClientTest {

    @Test
    fun localSuggestionsSurviveRemoteFailure() {
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        val done = CountDownLatch(1)
        val client = DesktopSearchWordClient(loadSuggestions = { throw java.io.IOException("offline") })
        try {
            SwingUtilities.invokeAndWait {
                client.requestSuggestions("旅程第二季") {
                    calls.add(it)
                    if (calls.size == 2) done.countDown()
                }
                assertEquals(listOf("旅程第二季", "旅程", "旅程第三季"), calls.single())
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(calls.first(), calls.last())
        } finally { client.close() }
    }

    @Test
    fun staleNetworkRepliesCannotReplaceANewerQuery() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val answers = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        val client = DesktopSearchWordClient(loadSuggestions = { url ->
            if (url.endsWith("first")) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                """{"data":[{"title":"stale"}]}"""
            } else """{"data":[{"title":"fresh"}]}"""
        })
        try {
            SwingUtilities.invokeAndWait { client.requestSuggestions("first") { answers.add(it) } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait {
                client.requestSuggestions("second") {
                    answers.add(it)
                    if (it.contains("fresh")) done.countDown()
                }
            }
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(listOf("first"), listOf("second"), listOf("second", "fresh")), answers)
        } finally {
            release.countDown()
            client.close()
        }
    }

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
