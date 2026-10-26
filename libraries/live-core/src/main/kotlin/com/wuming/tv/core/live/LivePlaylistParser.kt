package com.wuming.tv.core.live

import com.wuming.tv.core.base.native.NativeLoader
import com.google.gson.Gson
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

object LivePlaylistParser {

    private const val DEFAULT_GROUP = "Live TV"
    private val attribute = Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")
    private val channelKeyCleaner = Regex("[^\\p{L}\\p{N}]+")
    private val nativeAvailable: Boolean get() = NativeLoader.available
    private val gson = Gson()

    fun parse(content: String): LivePlaylist {
        val playlistText = LivePlaylistEncoding.stripMark(content)
        if (playlistText.trimStart().startsWith('[')) return parseJson(playlistText)
        if (nativeAvailable) {
            val json = nativeParse(playlistText)
            if (json.isNotEmpty()) {
                val result = gson.fromJson(json, LivePlaylistJson::class.java)
                if (result != null && result.groups.isNotEmpty()) {
                    return result.toLivePlaylist()
                }
            }
        }
        val lines = playlistText.lineSequence().map(String::trim).toList()
        require(lines.any(String::isNotEmpty)) { "Playlist is empty" }
        return if (lines.any { it.startsWith("#EXTM3U", ignoreCase = true) }) {
            parseM3u(lines)
        } else {
            parseTxt(lines)
        }
    }

    private fun parseJson(content: String): LivePlaylist {
        val groups = linkedMapOf<String, LinkedHashMap<String, MutableChannel>>()
        for (entry in LivePlaylistJsonCodec.groups(content)) {
            val group = entry.asJsonObject
            val groupName = LivePlaylistJsonCodec.text(group, "name").ifBlank { DEFAULT_GROUP }
            for (item in group.getAsJsonArray("channel")) {
                val channel = item.asJsonObject
                val name = LivePlaylistJsonCodec.text(channel, "name")
                if (name.isBlank()) continue
                val metadata = PendingChannel(
                    group = groupName,
                    name = name,
                    tvgId = LivePlaylistJsonCodec.text(channel, "tvgId"),
                    tvgName = LivePlaylistJsonCodec.text(channel, "tvgName"),
                    logo = LivePlaylistJsonCodec.text(channel, "logo"),
                    number = LivePlaylistJsonCodec.text(channel, "number"),
                    headers = LivePlaylistJsonCodec.headers(channel),
                )
                for (url in channel.getAsJsonArray("urls")) {
                    if ("://" in url.asString) add(groups, metadata, stream(url.asString, metadata.headers))
                }
            }
        }
        return playlist(groups, emptyList())
    }

    private fun parseM3u(lines: List<String>): LivePlaylist {
        val groups = linkedMapOf<String, LinkedHashMap<String, MutableChannel>>()
        val epgUrls = linkedSetOf<String>()
        var pending: PendingChannel? = null
        var directiveHeaders = linkedMapOf<String, String>()

        for (line in lines) {
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = attributes(line)
                    listOf("tvg-url", "x-tvg-url", "url-tvg").forEach { key ->
                        attributes[key].orEmpty().split(',').map(String::trim)
                            .filter(String::isNotEmpty).forEach(epgUrls::add)
                    }
                }

                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val attributes = attributes(line)
                    pending = PendingChannel(
                        group = attributes["group-title"].orEmpty().ifBlank { DEFAULT_GROUP },
                        name = line.substringAfterLast(',', attributes["tvg-name"].orEmpty())
                            .trim().ifBlank { attributes["tvg-name"].orEmpty().ifBlank { "Unnamed channel" } },
                        tvgId = attributes["tvg-id"].orEmpty(),
                        tvgName = attributes["tvg-name"].orEmpty(),
                        logo = attributes["tvg-logo"].orEmpty(),
                        number = attributes["tvg-chno"].orEmpty(),
                        headers = linkedMapOf<String, String>().apply {
                            attributes["http-user-agent"]?.takeIf(String::isNotBlank)
                                ?.let { put("User-Agent", it) }
                        },
                    )
                    directiveHeaders = linkedMapOf()
                }

                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                    directiveHeaders["User-Agent"] = line.substringAfter('=').trim()

                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ->
                    directiveHeaders["Referer"] = line.substringAfter('=').trim()

                line.startsWith("#EXTVLCOPT:http-origin=", ignoreCase = true) ->
                    directiveHeaders["Origin"] = line.substringAfter('=').trim()

                line.isNotEmpty() && !line.startsWith('#') && "://" in line -> {
                    val metadata = pending ?: PendingChannel(DEFAULT_GROUP, "Unnamed channel")
                    val stream = stream(line, metadata.headers + directiveHeaders)
                    add(groups, metadata, stream)
                    directiveHeaders = linkedMapOf()
                }
            }
        }
        return playlist(groups, epgUrls)
    }

    private fun parseTxt(lines: List<String>): LivePlaylist {
        val groups = linkedMapOf<String, LinkedHashMap<String, MutableChannel>>()
        var group = DEFAULT_GROUP
        var directiveHeaders = linkedMapOf<String, String>()

        for (line in lines) {
            when {
                line.isBlank() || line.startsWith("//") -> Unit
                line.startsWith("ua=", ignoreCase = true) ->
                    directiveHeaders["User-Agent"] = line.substringAfter('=').trim().trim('"')
                line.startsWith("referer=", ignoreCase = true) ->
                    directiveHeaders["Referer"] = line.substringAfter('=').trim().trim('"')
                line.startsWith("origin=", ignoreCase = true) ->
                    directiveHeaders["Origin"] = line.substringAfter('=').trim().trim('"')
                line.contains("#genre#", ignoreCase = true) -> {
                    group = line.substringBefore(',').trim().ifBlank { DEFAULT_GROUP }
                    directiveHeaders = linkedMapOf()
                }
                ',' in line -> {
                    val name = line.substringBefore(',').trim()
                    val locations = line.substringAfter(',').trim()
                    if (name.isNotEmpty() && "://" in locations) {
                        val metadata = PendingChannel(group, name)
                        locations.split('#').map(String::trim).filter { "://" in it }
                            .forEach { add(groups, metadata, stream(it, directiveHeaders)) }
                    }
                }
            }
        }
        return playlist(groups, emptySet())
    }

    private fun attributes(line: String): Map<String, String> = attribute.findAll(line).associate {
        it.groupValues[1].lowercase() to it.groupValues[2].trim()
    }

    private fun stream(value: String, inheritedHeaders: Map<String, String>): LiveStream {
        val parts = value.split('|', limit = 2)
        val headers = LinkedHashMap(inheritedHeaders)
        if (parts.size == 2) {
            parts[1].split('&').forEach { parameter ->
                val pair = parameter.split('=', limit = 2)
                if (pair.size == 2 && pair[0].isNotBlank()) {
                    headers[decode(pair[0]).trim()] = decode(pair[1]).trim().trim('"')
                }
            }
        }
        return LiveStream(parts[0].trim(), headers)
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    }.getOrDefault(value)

    private fun add(
        groups: LinkedHashMap<String, LinkedHashMap<String, MutableChannel>>,
        metadata: PendingChannel,
        stream: LiveStream,
    ) {
        if (stream.url.isBlank()) return
        val channels = groups.getOrPut(metadata.group) { linkedMapOf() }
        channels.getOrPut(channelKey(metadata)) { MutableChannel(metadata) }.streams.add(stream)
    }

    private fun channelKey(metadata: PendingChannel): String =
        sequenceOf(metadata.tvgId, metadata.tvgName, metadata.name)
            .first(String::isNotBlank)
            .lowercase()
            .replace(channelKeyCleaner, "")

    private fun playlist(
        groups: LinkedHashMap<String, LinkedHashMap<String, MutableChannel>>,
        epgUrls: Collection<String>,
    ): LivePlaylist {
        // Most txt playlists name no channel at all, and tuning by number needs one, so unnumbered
        // channels take the next ordinal in document order.
        var ordinal = 0
        val result = groups.map { (name, channels) ->
            LiveChannelGroup(name, channels.values.map { channel ->
                channel.build(channel.number.takeIf(String::isNotEmpty) ?: LiveChannelNumbering.label(++ordinal))
            })
        }.filter { it.channels.isNotEmpty() }
        require(result.isNotEmpty()) { "Playlist contains no playable channels" }
        return LivePlaylist(result, epgUrls.toList())
    }

    private data class PendingChannel(
        val group: String,
        val name: String,
        val tvgId: String = "",
        val tvgName: String = "",
        val logo: String = "",
        val number: String = "",
        val headers: Map<String, String> = emptyMap(),
    )

    private class MutableChannel(private val metadata: PendingChannel) {
        val streams = mutableListOf<LiveStream>()
        val number: String get() = metadata.number

        fun build(number: String) = LiveChannel(
            metadata.name,
            metadata.tvgId,
            metadata.tvgName,
            metadata.logo,
            number,
            streams.distinct(),
        )
    }

    @JvmStatic
    private external fun nativeParse(content: String): String

    private data class LivePlaylistJson(
        val groups: List<LiveGroupJson> = emptyList(),
        val epg_urls: List<String> = emptyList(),
    ) {
        fun toLivePlaylist(): LivePlaylist = LivePlaylist(
            groups.map { it.toLiveChannelGroup() },
            epg_urls,
        )
    }

    private data class LiveGroupJson(
        val name: String = "",
        val channels: List<LiveChannelJson> = emptyList(),
    ) {
        fun toLiveChannelGroup(): LiveChannelGroup = LiveChannelGroup(name, channels.map { it.toLiveChannel() })
    }

    private data class LiveChannelJson(
        val name: String = "",
        val tvg_id: String = "",
        val tvg_name: String = "",
        val logo: String = "",
        val number: String = "",
        val streams: List<LiveStreamJson> = emptyList(),
    ) {
        fun toLiveChannel(): LiveChannel = LiveChannel(name, tvg_id, tvg_name, logo, number, streams.map { it.toLiveStream() })
    }

    private data class LiveStreamJson(
        val url: String = "",
        val headers: Map<String, String> = emptyMap(),
    ) {
        fun toLiveStream(): LiveStream = LiveStream(url, headers)
    }
}
