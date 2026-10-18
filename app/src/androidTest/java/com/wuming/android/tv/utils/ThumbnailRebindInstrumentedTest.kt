package com.wuming.android.tv.utils

import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.wuming.android.tv.ui.activity.MemoryTestActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ThumbnailRebindInstrumentedTest {
    @Test
    fun remotePosterIsDownloadedOnceAcrossSizes() {
        val output = java.io.ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) } finally { bitmap.recycle() }
        val bytes = output.toByteArray()
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val downloads = java.util.concurrent.atomic.AtomicInteger()
        val worker = kotlin.concurrent.thread(isDaemon = true, name = "thumbnail-test-http") {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        downloads.incrementAndGet()
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (error: java.io.IOException) {
                if (!server.isClosed) throw error
            }
        }
        try {
            ActivityScenario.launch(MemoryTestActivity::class.java).use { scenario ->
                lateinit var view: ImageView
                val url = "http://127.0.0.1:${server.localPort}/${java.util.UUID.randomUUID()}.png"
                scenario.onActivity { activity ->
                    view = ImageView(activity)
                    activity.findViewById<ViewGroup>(android.R.id.custom).addView(view, ViewGroup.LayoutParams(64, 64))
                }
                // Grid and detail sizes must reuse downloaded bytes even after the memory cache is cleared.
                for (size in listOf(64, 128)) {
                    scenario.onActivity {
                        Glide.with(view).clear(view)
                        ImgUtil.clearMemory()
                        view.layout(0, 0, size, size)
                        ImgUtil.load("Poster", url, view)
                    }
                    var complete = false
                    val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                    while (!complete && android.os.SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { complete = DrawableImageViewTarget(view).request?.isComplete == true }
                        if (!complete) android.os.SystemClock.sleep(50)
                    }
                    assertTrue("Remote poster must load at size $size", complete)
                }
                assertEquals("A second poster size must reuse the cached original", 1, downloads.get())
                scenario.onActivity { Glide.with(view).clear(view) }
            }
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun transientFailureRetriesOnceWhileCardRemainsVisible() {
        val output = java.io.ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) } finally { bitmap.recycle() }
        val bytes = output.toByteArray()
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val downloads = java.util.concurrent.atomic.AtomicInteger()
        val worker = kotlin.concurrent.thread(isDaemon = true, name = "thumbnail-test-http") {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val attempt = downloads.incrementAndGet()
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 ${if (attempt == 1) "503 Service Unavailable" else "200 OK"}\r\nContent-Type: image/png\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (error: java.io.IOException) {
                if (!server.isClosed) throw error
            }
        }
        try {
            ActivityScenario.launch(MemoryTestActivity::class.java).use { scenario ->
                lateinit var view: ImageView
                val url = "http://127.0.0.1:${server.localPort}/${java.util.UUID.randomUUID()}.png"
                scenario.onActivity { activity ->
                    view = ImageView(activity)
                    activity.findViewById<ViewGroup>(android.R.id.custom).addView(view, ViewGroup.LayoutParams(64, 64))
                }
                // The first response fails; the visible card must recover without a rebind.
                for (size in listOf(64)) {
                    scenario.onActivity {
                        Glide.with(view).clear(view)
                        ImgUtil.clearMemory()
                        view.layout(0, 0, size, size)
                        ImgUtil.load("Poster", url, view)
                    }
                    var complete = false
                    val deadline = android.os.SystemClock.elapsedRealtime() + 25000
                    while (!complete && android.os.SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { complete = DrawableImageViewTarget(view).request?.isComplete == true }
                        if (!complete) android.os.SystemClock.sleep(50)
                    }
                    assertTrue("Remote poster must load at size $size", complete)
                    scenario.onActivity {
                        assertTrue("Recovered bitmap must replace the visible fallback",
                            view.drawable is android.graphics.drawable.BitmapDrawable)
                    }
                }
                assertEquals("Only one delayed recovery request is allowed", 2, downloads.get())
                scenario.onActivity { Glide.with(view).clear(view) }
            }
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun emptyRebindCancelsPendingArtwork() {
        ActivityScenario.launch(MemoryTestActivity::class.java).use { scenario ->
            lateinit var view: ImageView
            scenario.onActivity { activity ->
                view = ImageView(activity)
                activity.findViewById<ViewGroup>(android.R.id.custom).addView(view, ViewGroup.LayoutParams(64, 64))
                // Start before layout, then bind an item with no artwork before the first request completes.
                ImgUtil.load("First", "file:///missing-first-poster.png", view)
                val firstRequest = DrawableImageViewTarget(view).request
                assertNull("An unmeasured card must defer its request until visible", firstRequest)
                ImgUtil.load("Second", null, view)
                assertNull("The deferred previous binding must be cancelled",
                    view.getTag(com.wuming.android.tv.R.id.tag_image_retry))
                assertNull(DrawableImageViewTarget(view).request)
                assertNotNull("The new item must have a visible fallback", view.drawable)
            }
            scenario.onActivity { activity -> Glide.with(activity).clear(view) }
        }
    }

    @Test
    fun unmeasuredCardLoadsAfterLayout() {
        ActivityScenario.launch(MemoryTestActivity::class.java).use { scenario ->
            lateinit var view: ImageView
            lateinit var file: File
            scenario.onActivity { activity ->
                file = File.createTempFile("poster-rebind-", ".png", activity.cacheDir)
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                try {
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
                view = ImageView(activity)
                activity.findViewById<ViewGroup>(android.R.id.custom).addView(view, ViewGroup.LayoutParams(64, 64))
                assertEquals(0, view.width)
                ImgUtil.load("Poster", Uri.fromFile(file).toString(), view)
            }
            try {
                var complete = false
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (!complete && android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { complete = DrawableImageViewTarget(view).request?.isComplete == true }
                    if (!complete) android.os.SystemClock.sleep(50)
                }
                assertTrue("Poster must load when the card receives dimensions", complete)
            } finally {
                scenario.onActivity { activity -> Glide.with(activity).clear(view) }
                file.delete()
            }
        }
    }
}
