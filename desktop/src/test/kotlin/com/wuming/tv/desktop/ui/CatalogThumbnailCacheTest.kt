package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.CatalogItem
import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.swing.ImageIcon
import java.util.concurrent.Executors
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogThumbnailCacheTest {

    @Test
    fun `unreachable JPJ poster mirror falls back and decodes the image`() {
        val requested = mutableListOf<String>()
        val bytes = Base64.getDecoder().decode(imageUri(Color.GREEN).substringAfter(','))
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requested.add(request.url.host)
            if (request.url.host == "img.ypfbj.com") throw java.net.UnknownHostException("fixture")
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body(okhttp3.ResponseBody.create(null, bytes)).build()
        }.build()
        val loader = com.wuming.tv.desktop.net.DesktopResourceLoader(client = client)
        CatalogThumbnailCache(loader = loader, diskCache = null).use { cache ->
            val image = load(cache, "https://img.ypfbj.com/upload/poster.png")
            assertEquals(Color.GREEN.rgb, requireNotNull(PosterImages.pixels(image)).getRGB(20, 20))
            assertEquals(listOf("img.ypfbj.com", "img.cqkgy.com"), requested)
        }
    }

    @Test
    fun `evicts the least recently used poster instead of refusing new posters`() {
        CatalogThumbnailCache(diskCache = null, maximumEntries = 2).use { cache ->
            val first = imageUri(Color.RED)
            val second = imageUri(Color.BLUE)
            val third = imageUri(Color.GREEN)
            val red = load(cache, first)
            val blue = load(cache, second)
            assertSame(red, cache.iconFor(first))
            load(cache, third)
            assertSame(red, cache.iconFor(first))
            assertNotSame(blue, load(cache, second))
        }
    }

    @Test
    fun `a disk cache failure still displays the downloaded image`() {
        val unavailableDirectory = Files.createTempFile("poster-cache", ".file")
        try {
            CatalogThumbnailCache(diskCache = ThumbnailDiskCache(unavailableDirectory)).use { cache ->
                val image = load(cache, imageUri(Color.RED))
                assertEquals(Color.RED.rgb, requireNotNull(PosterImages.pixels(image)).getRGB(20, 20))
            }
        } finally {
            Files.deleteIfExists(unavailableDirectory)
        }
    }

    @Test
    fun `close suppresses queued UI notifications and further requests`() {
        val executor = Executors.newSingleThreadExecutor()
        val cache = CatalogThumbnailCache(diskCache = null, executor = executor)
        val uiBlocked = CountDownLatch(1)
        val releaseUi = CountDownLatch(1)
        val callbacks = AtomicInteger()
        SwingUtilities.invokeLater {
            uiBlocked.countDown()
            releaseUi.await(5, TimeUnit.SECONDS)
        }
        try {
            assertTrue(uiBlocked.await(5, TimeUnit.SECONDS))
            val location = imageUri(Color.RED)
            cache.iconFor(location) { callbacks.incrementAndGet() }
            executor.submit {}.get(5, TimeUnit.SECONDS)
            cache.close()
            releaseUi.countDown()
            SwingUtilities.invokeAndWait {}
            assertEquals(0, callbacks.get())
            assertSame(cache.iconFor(location), cache.iconFor(imageUri(Color.BLUE)))
        } finally {
            releaseUi.countDown()
            cache.close()
        }
    }

    @Test
    fun `repeated renders register a shared listener only once`() {
        val executor = Executors.newSingleThreadExecutor()
        val releaseWorker = CountDownLatch(1)
        executor.submit { releaseWorker.await(5, TimeUnit.SECONDS) }
        CatalogThumbnailCache(diskCache = null, executor = executor).use { cache ->
            val calls = AtomicInteger()
            val ready = CountDownLatch(1)
            val callback: () -> Unit = { calls.incrementAndGet(); ready.countDown() }
            val location = imageUri(Color.RED)
            repeat(100) { cache.iconFor(location, callback) }
            releaseWorker.countDown()
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait {}
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `oversized image dimensions are rejected before pixel decoding`() {
        // A valid BMP header can describe a huge image without containing any pixels.
        val header = java.nio.ByteBuffer.allocate(54).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put(0, 'B'.code.toByte()).put(1, 'M'.code.toByte())
        header.putInt(2, 54).putInt(10, 54).putInt(14, 40)
        header.putInt(18, 100_000).putInt(22, 100_000)
        header.putShort(26, 1).putShort(28, 24)
        val location = "data:image/bmp;base64," + Base64.getEncoder().encodeToString(header.array())
        CatalogThumbnailCache(diskCache = null).use { cache ->
            val ready = CountDownLatch(1)
            val placeholder = cache.iconFor(location, ready::countDown)
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertSame(placeholder, cache.iconFor(location))
        }
    }

    private fun imageUri(color: Color): String {
        val bytes = ByteArrayOutputStream().also { output ->
            val image = BufferedImage(24, 36, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try {
                graphics.color = color
                graphics.fillRect(0, 0, image.width, image.height)
            } finally {
                graphics.dispose()
            }
            ImageIO.write(image, "png", output)
        }.toByteArray()
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    private fun load(cache: CatalogThumbnailCache, location: String): ImageIcon {
        val ready = CountDownLatch(1)
        val placeholder = cache.iconFor(location, ready::countDown)
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return cache.iconFor(location).also { assertNotSame(placeholder, it) }
    }

    @Test
    fun `memory cache evicts least recently used poster and retries after backoff`() {
        val clock = com.wuming.tv.desktop.cache.MutableCacheClock()
        val bytes = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(24, 36, BufferedImage.TYPE_INT_RGB), "png", output)
        }.toByteArray()
        val requests = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val count = requests.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                exchange.sendResponseHeaders(if (path == "/retry" && count == 1) 503 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            CatalogThumbnailCache(diskCache = null, clock = clock, maximumEntries = 2).use { cache ->
                val base = "http://127.0.0.1:${server.address.port}"
                fun load(path: String) {
                    val ready = CountDownLatch(1)
                    cache.iconFor("Title", base + path, ready::countDown)
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                }
                load("/a"); load("/b")
                cache.iconFor("Title", "$base/a")
                load("/c"); load("/b")
                assertEquals(1, requests.getValue("/a").get())
                assertEquals(2, requests.getValue("/b").get())
                load("/retry")
                cache.iconFor("Title", "$base/retry")
                assertEquals(1, requests.getValue("/retry").get())
                clock.millisNow += com.wuming.tv.core.catalog.ThumbnailRetryPolicy.COOLDOWN_MS
                load("/retry")
                assertEquals(2, requests.getValue("/retry").get())
            }
        } finally { server.stop(0) }
    }

    @Test
    fun `all views waiting on one prefetched thumbnail are refreshed`() {
        val bytes = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(24, 36, BufferedImage.TYPE_INT_RGB), "png", output)
        }.toByteArray()
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/shared.png") { exchange ->
                requests.incrementAndGet()
                requestStarted.countDown()
                releaseResponse.await(5, TimeUnit.SECONDS)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            CatalogThumbnailCache(diskCache = null).use { cache ->
                val prefetchReady = CountDownLatch(1)
                val previewReady = CountDownLatch(1)
                val location = "http://127.0.0.1:${server.address.port}/shared.png"
                cache.prefetch(listOf(CatalogItem(name = "Shared", picture = location)), prefetchReady::countDown)
                assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
                cache.iconFor("Shared", location, previewReady::countDown)
                releaseResponse.countDown()

                assertTrue(prefetchReady.await(5, TimeUnit.SECONDS))
                assertTrue(previewReady.await(5, TimeUnit.SECONDS))
                assertEquals(1, requests.get())
            }
        } finally {
            releaseResponse.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `failed thumbnail can be retried without clearing successful cache entries`() {
        val bytes = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(24, 36, BufferedImage.TYPE_INT_RGB), "png", output)
        }.toByteArray()
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/retry.png") { exchange ->
                val attempt = requests.incrementAndGet()
                val response = if (attempt == 1) "temporary failure".toByteArray() else bytes
                exchange.sendResponseHeaders(if (attempt == 1) 503 else 200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        try {
            CatalogThumbnailCache(diskCache = null).use { cache ->
                val location = "http://127.0.0.1:${server.address.port}/retry.png"
                val first = CountDownLatch(1)
                val placeholder = cache.iconFor("Retry", location, first::countDown)
                assertTrue(first.await(5, TimeUnit.SECONDS))

                cache.retryFailed()
                val second = CountDownLatch(1)
                cache.iconFor("Retry", location, second::countDown)
                assertTrue(second.await(5, TimeUnit.SECONDS))
                assertNotSame(placeholder.image, cache.iconFor("Retry", location).image)
                assertEquals(2, requests.get())
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `loads and scales a remote thumbnail away from the Swing thread`() {
        val bytes = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(24, 36, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().also { graphics ->
                    try {
                        graphics.color = Color.RED
                        graphics.fillRect(0, 0, width, height)
                    } finally {
                        graphics.dispose()
                    }
                }
            }, "png", output)
        }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/poster.png") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val cache = CatalogThumbnailCache(diskCache = null)
            try {
                val ready = CountDownLatch(1)
                val location = "http://127.0.0.1:${server.address.port}/poster.png"
                val placeholder = cache.iconFor(location, ready::countDown)
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                val loaded = cache.iconFor(location)
                assertNotSame(placeholder.image, loaded.image)
                assertTrue(loaded.iconWidth == CatalogThumbnailCache.WIDTH)
                assertTrue(loaded.iconHeight == CatalogThumbnailCache.HEIGHT)
            } finally {
                cache.close()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `loads and scales WebP provider thumbnails`() {
        val bytes = Base64.getDecoder().decode(
            "UklGRh4AAABXRUJQVlA4TBEAAAAvAYAAAAdQkEIXpv+BiOh/AAA=",
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/poster.webp") { exchange ->
                exchange.responseHeaders.add("Content-Type", "image/webp")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            CatalogThumbnailCache(diskCache = null).use { cache ->
                val ready = CountDownLatch(1)
                val location = "http://127.0.0.1:${server.address.port}/poster.webp"
                val placeholder = cache.iconFor(location, ready::countDown)
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                val loaded = cache.iconFor(location)
                assertNotSame(placeholder.image, loaded.image)
                assertTrue(loaded.iconWidth == CatalogThumbnailCache.WIDTH)
                assertTrue(loaded.iconHeight == CatalogThumbnailCache.HEIGHT)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `prefetches provider thumbnail with Android inline headers`() {
        val bytes = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(30, 48, BufferedImage.TYPE_INT_RGB), "png", output)
        }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/guarded.png") { exchange ->
                val accepted = exchange.requestHeaders.getFirst("Referer") == "https://provider.example/" &&
                    exchange.requestHeaders.getFirst("Cookie") == "session=desktop"
                val response = if (accepted) bytes else "missing headers".toByteArray()
                exchange.sendResponseHeaders(if (accepted) 200 else 403, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        try {
            CatalogThumbnailCache(diskCache = null).use { cache ->
                val ready = CountDownLatch(1)
                val location = "http://127.0.0.1:${server.address.port}/guarded.png" +
                    "@Referer=https://provider.example/@Cookie=session=desktop"
                val placeholder = cache.iconFor("Guarded title", "")
                cache.prefetch(listOf(CatalogItem(name = "Guarded title", picture = location)), ready::countDown)

                assertTrue(ready.await(5, TimeUnit.SECONDS))
                val loaded = cache.iconFor("Guarded title", location)
                assertNotSame(placeholder.image, loaded.image)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `decoder preserves stripe averages instead of sampling only black pixels`() {
        val source = BufferedImage(1088, 1472, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setRGB(x, y, if (x % 2 == 0) Color.BLACK.rgb else Color.WHITE.rgb)
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()
        CatalogThumbnailCache(diskCache = null).use { cache ->
            val icon = load(cache, "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))
            val pixels = requireNotNull(PosterImages.pixels(icon))
            assertEquals(544, pixels.width)
            assertEquals(736, pixels.height)
            assertTrue(Color(pixels.getRGB(100, 100)).red in 120..136)
        }
    }

    @Test
    fun `disk cache refreshes old small posters and preserves detailed pixels`() {
        val directory = Files.createTempDirectory("poster-resolution-test-")
        try {
            val disk = ThumbnailDiskCache(directory)
            val key = "https://example.test/poster.png"
            disk.write(key, ImageIcon(BufferedImage(136, 184, BufferedImage.TYPE_INT_RGB)))
            assertEquals(null, disk.read(key))
            val pixels = BufferedImage(544, 736, BufferedImage.TYPE_INT_ARGB).apply {
                setRGB(301, 400, Color.RED.rgb)
                setRGB(302, 400, Color.BLUE.rgb)
            }
            disk.write(key, PosterImages.icon(pixels))
            val loaded = requireNotNull(disk.read(key))
            assertEquals(136, loaded.iconWidth)
            assertEquals(184, loaded.iconHeight)
            val retained = requireNotNull(PosterImages.pixels(loaded))
            assertEquals(Color.RED.rgb, retained.getRGB(301, 400))
            assertEquals(Color.BLUE.rgb, retained.getRGB(302, 400))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scaled poster survives process cache recreation without another download`() {
        val bytes = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(24, 36, BufferedImage.TYPE_INT_RGB), "png", output)
        }.toByteArray()
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/cached.png") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val directory = Files.createTempDirectory("tvbox-thumbnail-cache-test-")
        val location = "http://127.0.0.1:${server.address.port}/cached.png"
        try {
            CatalogThumbnailCache(diskCache = ThumbnailDiskCache(directory)).use { cache ->
                val ready = CountDownLatch(1)
                cache.iconFor(location, ready::countDown)
                assertTrue(ready.await(5, TimeUnit.SECONDS))
            }
            server.stop(0)
            CatalogThumbnailCache(diskCache = ThumbnailDiskCache(directory)).use { cache ->
                val ready = CountDownLatch(1)
                val placeholder = cache.iconFor(location, ready::countDown)
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                assertNotSame(placeholder.image, cache.iconFor(location).image)
            }
            assertTrue(requests.get() == 1)
        } finally {
            server.stop(0)
            directory.toFile().deleteRecursively()
        }
    }
}
