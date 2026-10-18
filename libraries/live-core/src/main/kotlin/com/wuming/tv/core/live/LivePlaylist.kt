package com.wuming.tv.core.live

data class LivePlaylist(
    val groups: List<LiveChannelGroup>,
    val epgUrls: List<String> = emptyList(),
) {
    val channelCount: Int get() = groups.sumOf { it.channels.size }
}

data class LiveChannelGroup(
    val name: String,
    val channels: List<LiveChannel>,
)

data class LiveChannel(
    val name: String,
    val tvgId: String = "",
    val tvgName: String = "",
    val logo: String = "",
    val number: String = "",
    val streams: List<LiveStream>,
)

data class LiveStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)
