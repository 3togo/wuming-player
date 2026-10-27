package com.wuming.tv.core.runtime.guardspider

import com.wuming.tv.core.catalog.CatalogDetail
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogHome
import com.wuming.tv.core.catalog.CatalogPage
import com.wuming.tv.core.catalog.CatalogPlayback
import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.catalog.CatalogSourceExecutor
import com.wuming.tv.core.catalog.CatVodCatalogCodec
import com.wuming.tv.core.catalog.withConfiguredCategories
import com.wuming.tv.core.cloud.CloudDriveDetector
import com.wuming.tv.core.cloud.CloudDriveProviderKind
import com.wuming.tv.core.runtime.guardspider.AndroidRuntimeProviderBridge
import com.wuming.tv.core.runtime.guardspider.DisabledAndroidRuntimeProviderBridge
import com.wuming.tv.core.runtime.guardspider.GmsMarker
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class GuardedCatVodCatalogSourceExecutor(
    private val spec: GuardedCatVodSiteSpec,
    artifact: PreparedGuardedSpiderArtifact,
    private val emulatorBridge: AndroidEmulatorWebViewBridge = DisabledEmulatorWebViewBridge,
    private val runtimeBridge: AndroidRuntimeProviderBridge = DisabledAndroidRuntimeProviderBridge,
) : CatalogSourceExecutor, AutoCloseable {
    private val logger = Logger.getLogger("tvbox.guarded.catalog")
    override val source = CatalogSource(spec.key, spec.name)
    private val codec = CatVodCatalogCodec(source)
    private val host = GuardedCatVodHostProcess(artifact, spec)
    private val cloudEpisodes = ConcurrentHashMap<Pair<String, String>, CloudDriveProviderKind>()
    private val reportedEmptyDetails = ConcurrentHashMap.newKeySet<String>()

    override fun search(query: String): List<CatalogItem> {
        require(query.isNotBlank()) { "Search query is required" }
        val response = host.request(GuardedCatVodHostRequest("search", query = query))
        if (response.isBlank()) {
            logger.warning(
                "search key=${source.key} query=${tag(query)} results=0 providerResponse=empty${diagnosticSuffix()}",
            )
            return emptyList()
        }
        return codec.search(response).also { logger.info("search key=${source.key} query=${tag(query)} results=${it.size}") }
    }

    override fun detail(id: String): CatalogDetail {
        require(id.isNotBlank()) { "Detail ID is required" }
        val request = GuardedCatVodHostRequest("detail", id = id)
        val response = runCatching { host.request(request) }
            .getOrElse { failure ->
                if (GmsMarker.matches(failure.message)) {
                    runtimeBridge.runOnDevice(spec, request)?.also { routed ->
                        logger.info("detail key=${source.key} id=${tag(id)} routedToDeviceGms=true")
                    } ?: throw failure
                } else {
                    throw failure
                }
            }
        val result = if (response.isNotBlank()) {
            codec.detail(response, id)
        } else {
            GuardedCloudShareDetail.decode(source.key, id) ?: throw emptyDetailFailure(id)
        }
        return result.also { detail ->
            cloudEpisodes.clear()
            detail.lines.forEach { line ->
                line.episodes.forEach { episode ->
                    val provider = CloudDriveDetector.detect(line.flag, episode.id, line.name)
                    if (provider != CloudDriveProviderKind.NONE) cloudEpisodes[line.flag to episode.id] = provider
                }
            }
            logger.info("detail key=${source.key} id=${tag(id)} lines=${detail.lines.size} episodes=${detail.lines.sumOf { line -> line.episodes.size }}")
        }
    }

    override fun play(flag: String, id: String): CatalogPlayback {
        require(id.isNotBlank()) { "Playback ID is required" }
        cloudEpisodes[flag to id]?.let { provider ->
            return CatalogPlayback(message = CloudDriveDetector.accountRequiredMessage(provider)).also {
                logPlayback(flag, id, it, 0)
            }
        }
        val request = GuardedCatVodHostRequest("play", id = id, flag = flag)
        val rawResponse = runCatching { host.request(request) }
        val failureMessage = rawResponse.exceptionOrNull()?.message
        if (rawResponse.isFailure && EmulatorMarker.matches(failureMessage)) {
            return routeEmulatorPlayback(request, flag, id)
        }
        if (rawResponse.isFailure && GmsMarker.matches(failureMessage)) {
            return routeGmsPlayback(request, flag, id)
        }
        val first = rawResponse.mapCatching { codec.playback(it) }
        val initial = first.getOrNull()
        if (initial?.supported == true) return initial.also { logPlayback(flag, id, it, 1) }
        if (initial != null && !initial.message.contains("did not return an HTTP", ignoreCase = true)) {
            return initial.also { logPlayback(flag, id, it, 1) }
        }
        val retry = runCatching { codec.playback(host.request(request)) }.getOrNull()
        if (retry?.supported == true) return retry.also { logPlayback(flag, id, it, 2) }
        if (isDirectMediaUrl(id)) return CatalogPlayback(supported = true, url = id).also {
            logger.info("play key=${source.key} flag=${tag(flag)} id=${tag(id)} directIdFallback=true")
        }
        val failure = retry ?: initial ?: first.exceptionOrNull()?.let {
            CatalogPlayback(message = it.message.orEmpty())
        } ?: CatalogPlayback()
        return failure.copy(message = buildString {
            append(failure.message.ifBlank { "Guarded CatVod provider returned no usable playback response." })
            diagnosticSuffix().takeIf(String::isNotBlank)?.let { append(' '); append(it.removePrefix(": ")) }
        }).also { logPlayback(flag, id, it, 2) }
    }

    /**
     * The guarded host's WebView shim could not render on the desktop (no JCEF). Consult the
     * pluggable [emulatorBridge]; if it is wired to a device/emulator, use it, otherwise surface a
     * typed, emulator-only result so the UI can route the source to Android.
     */
    private fun routeEmulatorPlayback(request: GuardedCatVodHostRequest, flag: String, id: String): CatalogPlayback {
        emulatorBridge.playOnEmulator(spec, request)?.let { routed ->
            logPlayback(flag, id, routed, 1)
            return routed
        }
        logger.info("play key=${source.key} flag=${tag(flag)} id=${tag(id)} requiresEmulator=true")
        return CatalogPlayback(
            supported = false,
            requiresEmulator = true,
            message = "This source resolves playback through an Android WebView; run it on an Android " +
                "device or emulator (no desktop Chromium renderer is configured).",
        ).also { logPlayback(flag, id, it, 1) }
    }

    /**
     * The guarded host cannot satisfy this source's `play()` because it links against Google Play
     * Services (`com.google.android.gms.*`) that the desktop JVM lacks. Consult the pluggable
     * [runtimeBridge]; if it is wired to a device/emulator with GMS, run the op there and return the
     * resolved [CatalogPlayback]; otherwise surface a typed, GMS-only result so the UI can route the
     * source to Android.
     */
    private fun routeGmsPlayback(request: GuardedCatVodHostRequest, flag: String, id: String): CatalogPlayback {
        runtimeBridge.runOnDevice(spec, request)?.let { routed ->
            val playback = runCatching { codec.playback(routed) }.getOrNull()
            if (playback != null) {
                logPlayback(flag, id, playback, 1)
                return playback
            }
        }
        logger.info("play key=${source.key} flag=${tag(flag)} id=${tag(id)} requiresAndroidRuntimeGms=true")
        return CatalogPlayback(
            supported = false,
            message = "This source needs Google Play Services to resolve playback; run it on an Android " +
                "device or emulator that has Google services installed.",
        ).also { logPlayback(flag, id, it, 1) }
    }

    override fun home(): List<CatalogItem> = browseHome().items

    /**
     * Harvests real catalogue vod ids by running the provider's browse ops *on the wired GMS device*
     * instead of the desktop host. Some providers (e.g. 红果) return an empty body from the desktop
     * JVM but surface items on a real Android runtime; 红果 in particular has an empty `home` (no
     * `class` types), so this also falls back to a `search` query. Returns the first page's ids, or
     * empty when no device bridge is wired or the device still yields nothing.
     */
    fun deviceHomeIds(searchQueries: List<String> = listOf("短剧", "红果", "热门")): List<String> {
        if (!runtimeBridge.available) return emptyList()
        runCatching { runtimeBridge.runOnDevice(spec, GuardedCatVodHostRequest("home")) }.getOrNull()
            ?.let { codec.home(it) }?.let { home ->
                val ids = home.items.map { it.id }.filter { it.isNotBlank() }
                if (ids.isNotEmpty()) return ids
                for (type in home.types) {
                    runCatching {
                        runtimeBridge.runOnDevice(spec, GuardedCatVodHostRequest("category", id = type.id, page = "1"))
                    }.getOrNull()?.let { codec.category(it, 1) }?.let { page ->
                        val catIds = page.items.map { it.id }.filter { it.isNotBlank() }
                        if (catIds.isNotEmpty()) return catIds
                    }
                }
            }
        for (query in searchQueries) {
            runCatching { runtimeBridge.runOnDevice(spec, GuardedCatVodHostRequest("search", query = query)) }
                .getOrNull()?.let { json ->
                    val ids = codec.search(json).map { it.id }.filter { it.isNotBlank() }
                    if (ids.isNotEmpty()) return ids
                }
        }
        return emptyList()
    }

    override fun browseHome(): CatalogHome {
        // A provider may return an empty body, or let its own parser throw (e.g. org.json
        // "End of input at character 0 of ") when homeContent yields no data. Degrade to an empty
        // home rather than failing the whole source, matching search()/detail() graceful handling.
        val home = runCatching { codec.home(host.request(GuardedCatVodHostRequest("home"))) }
            .getOrElse { cause ->
                logger.warning("home key=${source.key} empty=true reason=${cause.message?.take(160)}${diagnosticSuffix()}")
                CatalogHome(emptyList(), emptyList())
            }
        val homeVideo = runCatching { host.request(GuardedCatVodHostRequest("homeVideo")) }.getOrNull().orEmpty()
        val recommendations = if (homeVideo.isBlank()) emptyList() else runCatching { codec.home(homeVideo).items }.getOrElse { emptyList() }
        val items = (recommendations + home.items).distinctBy { it.sourceKey to it.id }
        logger.info(
            "home key=${source.key} recommendations=${recommendations.size} " +
                "providerItems=${home.items.size} renderedItems=${items.size}",
        )
        return home.copy(items = items)
            .withConfiguredCategories(spec.categories)
    }

    override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage =
        codec.category(host.request(GuardedCatVodHostRequest(
            operation = "category",
            id = typeId,
            page = page.toString(),
            filters = filters,
        )), page)

    /**
     * An empty `detail` body means the spider could not parse its own site: anti-bot pages and
     * markup changes reach the host as an obfuscated stack trace. Log the full diagnostics once per
     * source and cause, then hand the UI a single actionable line instead of the raw trace.
     */
    private fun emptyDetailFailure(id: String): IllegalArgumentException {
        val diagnostics = host.recentDiagnostics().takeLast(12).filter(String::isNotBlank)
        val reason = SiteFailureMarker.classify(diagnostics)
        val summary = "detail key=${source.key} id=${tag(id)} results=0 providerResponse=empty " +
            "reason=${reason ?: "unknown"}"
        if (reportedEmptyDetails.add("${source.key}:${reason ?: "unknown"}")) {
            logger.warning(
                diagnostics.takeIf { it.isNotEmpty() }
                    ?.let { "$summary diagnostics=${it.joinToString(" | ")}" } ?: summary,
            )
        } else {
            logger.warning("$summary diagnostics=already-reported")
        }
        return IllegalArgumentException(
            "Guarded CatVod provider returned no detail data" +
                (reason?.let { " because $it" } ?: "") +
                ". This source needs an upstream site-adapter update; try another source.",
        )
    }

    private fun diagnosticSuffix(): String = host.recentDiagnostics().takeLast(12)
        .filter(String::isNotBlank).joinToString(" | ").takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()

    private fun isDirectMediaUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val http = (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
        val path = uri.path.orEmpty().lowercase()
        http && listOf(".m3u8", ".mp4", ".mkv", ".webm", ".flv", ".mp3", ".aac").any(path::endsWith)
    }.getOrDefault(false)

    private fun logPlayback(flag: String, id: String, playback: CatalogPlayback, attempt: Int) {
        logger.info(
            "play key=${source.key} flag=${tag(flag)} id=${tag(id)} attempt=$attempt supported=${playback.supported} " +
                "format=${playback.format} headers=${playback.headers.keys.sorted()} message=${playback.message}",
        )
    }

    private fun tag(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
        return "len=${value.length},sha=$digest"
    }

    override fun close() = host.close()
}
