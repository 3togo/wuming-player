package com.wuming.android.tv.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.google.common.util.concurrent.ListenableFuture
import com.wuming.android.tv.BuildConfig
import com.wuming.android.tv.event.ConfigEvent
import com.wuming.android.tv.browse.BrowseTree
import com.wuming.android.tv.browse.BrowserSearchServiceFixture
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real MediaBrowser -> MediaLibraryService -> BrowseTree -> shared workflow -> Binder callback. */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceSearchInstrumentedTest {
    @get:Rule val services = ServiceTestRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val notifications = LinkedBlockingQueue<Notice>()
    private lateinit var fixture: BrowserSearchServiceFixture
    private lateinit var browser: MediaBrowser
    private lateinit var binder: PlaybackService.LocalBinder
    private lateinit var service: PlaybackService
    private var bound = false

    private data class Notice(val query: String, val count: Int, val marker: String?)

    @Before fun connect() {
        check(BuildConfig.APPLICATION_ID.endsWith(".verification")) { "Isolated verification app required" }
        fixture = BrowserSearchServiceFixture()
        val context = ApplicationProvider.getApplicationContext<Context>()
        binder = services.bindService(Intent(context, PlaybackService::class.java)
            .setAction(PlaybackService.LOCAL_BIND_ACTION)) as PlaybackService.LocalBinder
        bound = true
        service = checkNotNull(binder.service)
        lateinit var connection: ListenableFuture<MediaBrowser>
        main {
            connection = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .setListener(object : MediaBrowser.Listener {
                    override fun onSearchResultChanged(browser: MediaBrowser, query: String, itemCount: Int, params: LibraryParams?) {
                        notifications.add(Notice(query, itemCount, params?.extras?.getString("marker")))
                    }
                }).buildAsync()
        }
        browser = connection.get(15, TimeUnit.SECONDS)
    }

    @After fun disconnect() {
        try {
            if (::browser.isInitialized) main { browser.release() }
            if (::service.isInitialized) main { service.shutdown() }
            if (bound) { services.unbindService(); bound = false }
            if (::binder.isInitialized) awaitDestroyed()
        } finally {
            if (::fixture.isInitialized) fixture.close()
        }
    }

    @Test fun acceptancePrecedesProviderCompletionAndResultsReachBrowser() {
        val entered = CountDownLatch(1)
        val release = fixture.gate()
        fixture.provider = { _, query -> entered.countDown(); await(release); listOf("$query-1", "$query-2") }
        search("pending", "request-marker")
        await(entered)
        assertNull("Acceptance is not a completed search", notifications.poll(150, TimeUnit.MILLISECONDS))
        release.countDown()
        assertEquals(Notice("pending", 2, "request-marker"), notice())
        assertEquals(listOf("pending-1", "pending-2"), results("pending"))
    }

    @Test fun replacementSuppressesQueuedProviderAndCachedNotifications() {
        val queued = holdNotifications()
        for (cached in listOf(false, true)) {
            val old = if (cached) "cached" else "provider"
            if (cached) {
                search(old); deliver(queued.takeNext()); assertEquals(old, notice().query)
            }
            val before = fixture.calls.get()
            search(old)
            val stale = queued.takeNext()
            assertEquals(before + if (cached) 0 else 1, fixture.calls.get())
            search("new-$old")
            val fresh = queued.takeNext()
            deliver(stale)
            assertNoNotice()
            deliver(fresh)
            assertEquals("new-$old", notice("new-$old").query)
            assertEquals(listOf("one-new-$old"), results("new-$old"))
        }
    }

    @Test fun clearSuppressesQueuedNotificationAndRequiresProviderReload() {
        val queued = holdNotifications()
        search("clear")
        val stale = queued.takeNext()
        main { ConfigEvent.vod() }
        deliver(stale)
        assertNoNotice()
        assertTrue(results("clear").isEmpty())
        search("clear")
        deliver(queued.takeNext())
        assertEquals(Notice("clear", 1, null), notice())
        assertEquals(2, fixture.calls.get())
    }

    @Test fun destroyedServiceCannotDeliverItsQueuedNotification() {
        val queued = holdNotifications()
        search("destroy")
        val stale = queued.takeNext()
        main { browser.release(); service.shutdown() }
        services.unbindService()
        bound = false
        awaitDestroyed()
        deliver(stale)
        assertNoNotice()
    }

    @Test fun cacheReuseStrictIdentityAndFixedExpiryAreVisibleThroughTheService() {
        fun roundTrip() {
            search("cache")
            assertEquals(Notice("cache", 1, null), notice())
            assertEquals(listOf("one-cache"), results("cache"))
        }
        roundTrip()
        fixture.clock.set(299_999)
        roundTrip()
        assertEquals(1, fixture.calls.get())
        fixture.clock.set(300_000)
        roundTrip()
        assertEquals("A hit must not extend the original five-minute TTL", 2, fixture.calls.get())
        fixture.strict = true
        roundTrip()
        assertEquals(3, fixture.calls.get())
        fixture.strict = false
        roundTrip()
        assertEquals(3, fixture.calls.get())
    }

    @Test fun timeoutStartsAtEachCollectionTurnAndLateTimedOutResultsStayExcluded() {
        fixture.sources = listOf("timeout", "second", "third")
        val entered = CountDownLatch(3)
        val first = fixture.gate()
        val second = fixture.gate()
        fixture.provider = { source, _ ->
            entered.countDown()
            if (source == "timeout") await(first)
            if (source == "second") await(second)
            listOf(source)
        }
        search("timeout")
        await(entered)
        // The first wait expires at five seconds. Its late payload must stay excluded,
        // while the second source gets a fresh budget and may finish after that point.
        fixture.timers.schedule({ first.countDown() }, 5_500, TimeUnit.MILLISECONDS)
        fixture.timers.schedule({ second.countDown() }, 6_000, TimeUnit.MILLISECONDS)
        assertEquals(Notice("timeout", 2, null), notice())
        assertEquals(listOf("second", "third"), results("timeout"))
    }

    @Test fun libraryChildrenAlsoRespectSmallPagesAndOutOfRangeRequests() {
        fun children(page: Int, pageSize: Int): List<String> {
            lateinit var future: ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>
            val params = LibraryParams.Builder().setExtras(Bundle().apply { putString("marker", "children") }).build()
            main { future = browser.getChildren("ROOT", page, pageSize, params) }
            val result = future.get(10, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
            assertEquals("children", result.params?.extras?.getString("marker"))
            val items = checkNotNull(result.value)
            assertTrue("Media3 children exceeded requested size $pageSize: ${items.size}", items.size <= pageSize)
            return items.map { it.mediaId }
        }
        assertEquals(listOf("VOD"), children(0, 1))
        assertEquals(listOf("LIVE"), children(1, 1))
        assertTrue(children(2, 1).isEmpty())
        assertTrue(children(Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertEquals(listOf("VOD", "LIVE"), children(0, Int.MAX_VALUE))
    }

    @Test fun sourceOrderControlsTheRawCapAndPagedRetrieval() {
        fixture.sources = listOf("first", "second", "third")
        val first = fixture.gate()
        fixture.provider = { source, _ ->
            if (source == "first") await(first)
            (0 until 30).map { "$source-$it" }
        }
        search("order")
        repeat(2) { assertNotNull("Later source did not complete", fixture.completed.poll(10, TimeUnit.SECONDS)) }
        first.countDown()
        assertEquals(Notice("order", 50, null), notice())
        val expected = (0 until 30).map { "first-$it" } + (0 until 20).map { "second-$it" }
        assertEquals(expected, results("order"))
        assertEquals(listOf("first-0"), results("order", 0, 1, "page-marker"))
        assertEquals(listOf("first-1"), results("order", 1, 1))
        assertEquals(listOf("second-19"), results("order", 49, 1))
        assertEquals(expected, (0..7).flatMap { results("order", it, 7) })
        assertTrue(results("order", 50, 1).isEmpty())
        assertTrue(results("order", 1, 100).isEmpty())
        assertTrue(results("order", Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertEquals(expected, results("order", 0, Int.MAX_VALUE))
        search("order")
        assertEquals(Notice("order", 50, null), notice())
        assertEquals(listOf("first-1"), results("order", 1, 1))
        assertEquals("Retrieval and cache reuse must not repeat provider work", 3, fixture.calls.get())
    }

    private fun search(query: String, marker: String? = null) {
        val params = LibraryParams.Builder().setExtras(Bundle().apply { if (marker != null) putString("marker", marker) }).build()
        lateinit var future: ListenableFuture<LibraryResult<Void>>
        main { future = browser.search(query, params) }
        assertEquals("Search dispatch must be accepted", LibraryResult.RESULT_SUCCESS, future.get(10, TimeUnit.SECONDS).resultCode)
    }

    private fun results(query: String, page: Int = 0, pageSize: Int = 100, marker: String? = null): List<String> {
        val params = marker?.let { LibraryParams.Builder().setExtras(Bundle().apply { putString("marker", it) }).build() }
        lateinit var future: ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>
        main { future = browser.getSearchResult(query, page, pageSize, params) }
        val result = future.get(10, TimeUnit.SECONDS)
        assertEquals(LibraryResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(marker, result.params?.extras?.getString("marker"))
        val items = checkNotNull(result.value)
        assertTrue("Media3 page exceeded requested size $pageSize: ${items.size}", items.size <= pageSize)
        return items.map { it.mediaId }
    }

    private fun holdNotifications() = QueuedExecutor().also { queue -> main { service.searchNotificationExecutor = queue } }
    private class QueuedExecutor : Executor {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun takeNext(): Runnable = checkNotNull(tasks.poll(15, TimeUnit.SECONDS)) { "Service did not enqueue a notification" }
    }
    private fun deliver(task: Runnable) = main { task.run() }
    private fun notice(expected: String = "unspecified"): Notice = checkNotNull(notifications.poll(15, TimeUnit.SECONDS)) {
        "Browser received no notification for $expected; calls=${fixture.calls.get()}, current=${BrowseTree.getSearchResult().map { it.mediaId }}, invalidations=${fixture.invalidations}"
    }
    private fun assertNoNotice() {
        instrumentation.waitForIdleSync()
        assertNull("Stale notification reached the actual browser", notifications.poll(250, TimeUnit.MILLISECONDS))
    }
    private fun await(latch: CountDownLatch) { assertTrue("Fixture gate timed out", latch.await(15, TimeUnit.SECONDS)) }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun awaitDestroyed() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (binder.service != null && System.nanoTime() < deadline) Thread.sleep(20)
        assertNull("Service did not finish teardown", binder.service)
    }
}
