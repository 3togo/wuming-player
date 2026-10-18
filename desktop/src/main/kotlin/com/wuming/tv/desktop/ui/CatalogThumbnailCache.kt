package com.wuming.tv.desktop.ui

import com.wuming.tv.core.base.Digests
import com.wuming.tv.core.catalog.CatalogImageRequest
import com.wuming.tv.core.catalog.CatalogImageRequestParser
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.desktop.net.DesktopResourceLoader
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import com.wuming.tv.desktop.cache.DesktopCacheGeneration
import java.util.concurrent.ExecutorService
import javax.imageio.stream.MemoryCacheImageInputStream
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.SwingUtilities

internal class CatalogThumbnailCache(
    private val loader: DesktopResourceLoader = DesktopResourceLoader(maximumBytes = MAX_IMAGE_BYTES).forThumbnails(),
    private val diskCache: ThumbnailDiskCache? = ThumbnailDiskCache.openDefault(),
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    private val maximumEntries: Int = memoryEntryLimit(),
    private val executor: ExecutorService = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "tvbox-thumbnail").apply { isDaemon = true }
    },
) : AutoCloseable {

    private val logger = Logger.getLogger("tvbox.ui.thumbnail")
    private val entries = lru<String, ImageIcon>(maximumEntries)
    private val placeholders = lru<String, ImageIcon>(minOf(16, maximumEntries))
    private val pending = mutableMapOf<String, Pending>()
    private val retries = com.wuming.tv.core.catalog.ThumbnailRetryPolicy<String> { clock.millis() }
    private val inlineKeys = WeakHashMap<String, String>()
    private val closedIcon = ImageIcon()
    private var closed = false
    private var cacheGeneration = DesktopCacheGeneration.value.get()

    init {
        require(maximumEntries > 0)
    }

    fun iconFor(location: String, ready: () -> Unit = {}): ImageIcon = iconFor("", location, ready)

    @Synchronized
    fun iconFor(title: String, location: String, ready: () -> Unit = {}): ImageIcon {
        if (closed) return closedIcon
        val generation = DesktopCacheGeneration.value.get()
        if (generation != cacheGeneration) {
            entries.clear()
            retries.clear()
            cancelPending()
            cacheGeneration = generation
        }
        val key = cacheKey(location)
        entries[key]?.let { return it }
        val placeholder = placeholder(title)
        if (retries.isBlocked(key)) return placeholder
        val request = request(location) ?: return placeholder
        pending[key]?.let { loading ->
            loading.listeners.add(ready)
            return placeholder
        }
        if (pending.size >= minOf(MAX_PREFETCH, maximumEntries)) return placeholder
        val loading = Pending(generation).apply { listeners.add(ready) }
        pending[key] = loading
        loading.future = executor.submit { loadThumbnail(key, request, loading) }
        return placeholder
    }

    private fun loadThumbnail(key: String, request: CatalogImageRequest, loading: Pending) {
        synchronized(this) {
            if (!isCurrent(loading) || pending[key] !== loading) return
        }
        var diskHit = false
        val result = runCatching {
            diskCache?.read(key)?.also { diskHit = true } ?: decode(load(request)).also { icon ->
                // A full or unavailable disk cache must not discard a successfully loaded poster.
                runCatching {
                    if (synchronized(this) { isCurrent(loading) }) diskCache?.write(key, icon)
                }
                    .onFailure { logger.fine("thumbnail disk cache unavailable: ${rootMessage(it)}") }
            }
        }
        synchronized(this) {
            if (!isCurrent(loading) || pending[key] !== loading) return
            pending.remove(key)
            result.onSuccess { entries[key] = it; retries.succeeded(key) }.onFailure {
                // Desktop retries on a later render or explicit refresh; share the cooldown policy.
                retries.failed(key, transient = false, alreadyRetried = false)
            }
        }
        result.onSuccess {
            logger.fine("thumbnail ready target=${safeTarget(request.location)} cache=${if (diskHit) "disk" else "network"}")
        }.onFailure { failure ->
            logger.log(Level.WARNING, "thumbnail failed target=${safeTarget(request.location)} error=${rootMessage(failure)}")
        }
        // Keep listeners on the request until delivery so close() can invalidate queued UI work.
        SwingUtilities.invokeLater {
            synchronized(this) {
                if (!isCurrent(loading)) return@invokeLater
                loading.listeners.forEach { listener -> runCatching { listener() } }
                loading.listeners.clear()
            }
        }
    }

    fun prefetch(items: List<CatalogItem>, ready: () -> Unit = {}) {
        items.asSequence().filter { it.picture.isNotBlank() }.take(minOf(MAX_PREFETCH, maximumEntries))
            .forEach { iconFor(it.name, it.picture, ready) }
    }

    @Synchronized
    fun retryFailed() {
        retries.clear()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cancelPending()
        executor.shutdownNow()
        entries.clear()
        placeholders.clear()
        retries.clear()
        inlineKeys.clear()
    }

    private fun isCurrent(loading: Pending): Boolean =
        !closed && loading.generation == cacheGeneration && loading.generation == DesktopCacheGeneration.value.get()

    private fun cancelPending() {
        pending.values.forEach {
            it.listeners.clear()
            it.future?.cancel(true)
        }
        pending.clear()
        (executor as? ThreadPoolExecutor)?.purge()
    }

    private class Pending(val generation: Long) {
        val listeners = linkedSetOf<() -> Unit>()
        var future: Future<*>? = null
    }

    private fun cacheKey(location: String): String {
        val value = location.trim()
        // Avoid retaining a multi-megabyte inline image as a cache key after decoding it.
        if (!value.startsWith("data:", true)) return value
        inlineKeys[value]?.let { return it }
        val key = "data:" + Digests.sha256Hex(value)
        if (inlineKeys.size >= maximumEntries) inlineKeys.keys.firstOrNull()?.let(inlineKeys::remove)
        inlineKeys[value] = key
        return key
    }

    private fun request(value: String): CatalogImageRequest? = CatalogImageRequestParser.parse(value).takeIf {
        it.location.startsWith("http://", true) || it.location.startsWith("https://", true) ||
            it.location.startsWith("data:image/", true)
    }

    private fun load(request: CatalogImageRequest): ByteArray = if (request.location.startsWith("data:", true)) {
        decodeDataUri(request.location)
    } else loader.load(request.location, request.headers)

    private fun decodeDataUri(value: String): ByteArray {
        val separator = value.indexOf(',')
        require(separator in 11..1024) { "Invalid poster data URI" }
        val metadata = value.substring(5, separator)
        val encodedLimit = if (metadata.endsWith(";base64", true)) ((MAX_IMAGE_BYTES + 2L) / 3L) * 4L
            else MAX_IMAGE_BYTES * 3L
        require(value.length - separator - 1 <= encodedLimit) { "Poster data URI is too large" }
        val payload = value.substring(separator + 1)
        val bytes = if (metadata.endsWith(";base64", true)) Base64.getDecoder().decode(payload)
        else URLDecoder.decode(payload, StandardCharsets.UTF_8).toByteArray(StandardCharsets.ISO_8859_1)
        require(bytes.size <= MAX_IMAGE_BYTES) { "Poster data URI is too large" }
        return bytes
    }

    private fun decode(bytes: ByteArray): ImageIcon {
        require(bytes.size <= MAX_IMAGE_BYTES) { "Poster is too large" }
        val source = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "Unsupported thumbnail image" }
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
                    "Thumbnail dimensions are too large"
                }
                // Keep ordinary provider posters intact. Subsample only oversized
                // images to bound decoder memory, not to the small logical icon size.
                val sample = maxOf(1, (maxOf(width, height) + 2047) / 2048)
                val parameters = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                reader.read(0, parameters)
            } finally {
                reader.dispose()
            }
        }
        // Crop before filtering so wide/tall sources never create giant intermediates.
        val cropWidth = minOf(source.width, (source.height.toLong() * WIDTH / HEIGHT).toInt()).coerceAtLeast(1)
        val cropHeight = minOf(source.height, (source.width.toLong() * HEIGHT / WIDTH).toInt()).coerceAtLeast(1)
        val cropped = source.getSubimage((source.width - cropWidth) / 2, (source.height - cropHeight) / 2,
            cropWidth, cropHeight)
        val pixels = PosterImages.resize(cropped, PosterImages.PIXEL_WIDTH, PosterImages.PIXEL_HEIGHT)
        return PosterImages.icon(pixels)
    }

    private fun placeholder(title: String): ImageIcon {
        val text = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        return placeholders.computeIfAbsent(text) {
            ImageIcon(BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB).apply {
                val graphics = createGraphics()
                try {
                    val hue = ((text.hashCode() and 0x7fffffff) % 360) / 360f
                    graphics.color = Color.getHSBColor(hue, 0.38f, 0.48f)
                    graphics.fillRect(0, 0, WIDTH, HEIGHT)
                    graphics.color = Color.WHITE
                    graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 46)
                    val metrics = graphics.fontMetrics
                    graphics.drawString(text, (WIDTH - metrics.stringWidth(text)) / 2, (HEIGHT + metrics.ascent) / 2 - 8)
                } finally {
                    graphics.dispose()
                }
            })
        }
    }

    private fun safeTarget(value: String): String = runCatching {
        if (value.startsWith("data:", true)) return@runCatching "data:image"
        val uri = URI(value)
        "${uri.scheme}://${uri.host.orEmpty()}${uri.path.orEmpty()}"
    }.getOrDefault("invalid")

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null) current = current.cause!!
        return current.message ?: current.javaClass.simpleName
    }

    companion object {
        const val WIDTH = 136
        const val HEIGHT = 184
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        private const val MAX_DIMENSION = 8192
        private fun memoryEntryLimit(): Int {
            val runtime = Runtime.getRuntime()
            val headroom = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            val budget = minOf(runtime.maxMemory() / 16, headroom.coerceAtLeast(0) / 8)
            return (budget / ((PosterImages.PIXEL_WIDTH * PosterImages.PIXEL_HEIGHT + WIDTH * HEIGHT * 5) * 4L)).coerceIn(8, 256).toInt()
        }

        private fun <K, V> lru(limit: Int): LinkedHashMap<K, V> =
            object : LinkedHashMap<K, V>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > limit
            }
        private const val MAX_PREFETCH = 96
    }
}
