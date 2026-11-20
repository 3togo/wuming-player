package com.wuming.tv.desktop.epg

import com.wuming.tv.core.epg.ChannelNameMatcher
import com.wuming.tv.core.epg.NowNext
import com.wuming.tv.core.epg.ProgrammeGuidePolicy
import com.wuming.tv.core.epg.XmlTvGuide
import com.wuming.tv.core.epg.XmlTvParser
import com.wuming.tv.core.live.LiveChannel
import com.wuming.tv.desktop.cache.ResourceDiskCache
import com.wuming.tv.desktop.net.DesktopResourceCancellation
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
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

    private fun resource(
        location: String, headers: Map<String, String>, forceRefresh: Boolean, cancellation: DesktopResourceCancellation?,
    ): ByteArray =
        loadResource?.invoke(location, headers) ?: ResourceDiskCache.epg.load(location, headers, forceRefresh, cancellation) {
            parse(it, cancellation)
            true
        }.also { cacheStatus = it.origin.name.lowercase() }.bytes

    fun load(location: String, forceRefresh: Boolean = false): DesktopProgrammeGuide = load(location, forceRefresh, null)

    internal fun load(location: String, forceRefresh: Boolean, cancellation: DesktopResourceCancellation?): DesktopProgrammeGuide {
        cancellation?.check()
        val bytes = try {
            resource(location, emptyMap(), forceRefresh, cancellation)
        } catch (failure: Exception) {
            cancellation?.check()
            if (!isHttpForbidden(failure)) throw failure
            // aptv.app deliberately rejects generic desktop user agents but publishes this EPG
            // for APTV-compatible clients. Retry only its explicit HTTP 403 compatibility case.
            resource(location, mapOf("User-Agent" to "AptvPlayer/1.4"), forceRefresh, cancellation)
        }
        return parse(bytes, cancellation)
    }

    private fun parse(bytes: ByteArray, cancellation: DesktopResourceCancellation?): DesktopProgrammeGuide {
        val stream = ByteArrayInputStream(bytes)
        val input = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(stream)
        } else {
            stream
        }
        return input.use { stream ->
            var remaining = MAX_EXPANDED_BYTES.toLong()
            val bounded = object : FilterInputStream(stream) {
                private fun count(read: Int): Int {
                    if (read > 0) remaining -= read
                    require(remaining >= 0) { "Decompressed EPG exceeds 128 MiB." }
                    return read
                }

                override fun read(): Int {
                    cancellation?.check()
                    return super.read().also { if (it >= 0) count(1) }
                }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    cancellation?.check()
                    return count(super.read(bytes, offset, minOf(length.toLong(), remaining + 1).toInt()))
                }

                override fun skip(length: Long): Long {
                    cancellation?.check()
                    return super.skip(minOf(length, remaining + 1)).also { count(it.toInt()) }
                }
            }
            DesktopProgrammeGuide(XmlTvParser.parse(bounded)).also { cancellation?.check() }
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
