package com.wuming.tv.desktop.epg

import com.wuming.tv.core.epg.ChannelNameMatcher
import com.wuming.tv.core.epg.NowNext
import com.wuming.tv.core.epg.ProgrammeGuidePolicy
import com.wuming.tv.core.epg.XmlTvGuide
import com.wuming.tv.core.epg.XmlTvParser
import com.wuming.tv.core.live.LiveChannel
import com.wuming.tv.desktop.cache.ResourceDiskCache
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class DesktopProgrammeGuide(private val guide: XmlTvGuide) {

    private val programmes = guide.programmes.groupBy { it.channelId }
    private val namesToId = buildMap {
        guide.channels.forEach { channel ->
            putIfAbsent(channel.displayName, channel.id)
            channel.aliases.forEach { putIfAbsent(it, channel.id) }
        }
    }

    val programmeCount: Int get() = guide.programmes.size

    fun nowNext(channel: LiveChannel, timeMs: Long = System.currentTimeMillis()): NowNext {
        val id = channel.tvgId.takeIf(programmes::containsKey) ?: findChannelId(channel)
        return ProgrammeGuidePolicy.nowNext(programmes[id].orEmpty(), timeMs)
    }

    private fun findChannelId(channel: LiveChannel): String? {
        val match = ChannelNameMatcher.findBest(
            listOf(channel.tvgName, channel.name),
            namesToId.keys,
        ) ?: return null
        return namesToId[match.candidate]
    }
}

class DesktopProgrammeGuideLoader internal constructor(
    private val loadResource: ((String, Map<String, String>) -> ByteArray)?,
) {
    constructor() : this(null)

    @Volatile var cacheStatus: String = "network"
        private set

    private fun resource(location: String, headers: Map<String, String>, forceRefresh: Boolean): ByteArray =
        loadResource?.invoke(location, headers) ?: ResourceDiskCache.epg.load(location, headers, forceRefresh) {
            parse(it)
            true
        }.also { cacheStatus = it.origin.name.lowercase() }.bytes

    fun load(location: String, forceRefresh: Boolean = false): DesktopProgrammeGuide {
        val bytes = try {
            resource(location, emptyMap(), forceRefresh)
        } catch (failure: Throwable) {
            if (!isHttpForbidden(failure)) throw failure
            // aptv.app deliberately rejects generic desktop user agents but publishes this EPG
            // for APTV-compatible clients. Retry only its explicit HTTP 403 compatibility case.
            resource(location, mapOf("User-Agent" to "AptvPlayer/1.4"), forceRefresh)
        }
        return parse(bytes)
    }

    private fun parse(bytes: ByteArray): DesktopProgrammeGuide {
        val stream = ByteArrayInputStream(bytes)
        val input = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(stream)
        } else {
            stream
        }
        return input.use {
            val expanded = it.readNBytes(MAX_EXPANDED_BYTES + 1)
            require(expanded.size <= MAX_EXPANDED_BYTES) { "Decompressed EPG exceeds 128 MiB." }
            DesktopProgrammeGuide(XmlTvParser.parse(ByteArrayInputStream(expanded)))
        }
    }

    private companion object { const val MAX_EXPANDED_BYTES = 128 * 1024 * 1024 }

    private fun isHttpForbidden(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current.message?.contains("HTTP 403") == true) return true
            current = current.cause
        }
        return false
    }
}
