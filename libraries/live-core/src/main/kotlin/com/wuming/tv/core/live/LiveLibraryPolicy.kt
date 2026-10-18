package com.wuming.tv.core.live

import java.security.MessageDigest

data class LiveSelection(val channel: String, val route: String)
interface LiveLibraryStore {
    fun favorite(source: String, channel: String): Boolean
    fun setFavorite(source: String, channel: String, favorite: Boolean)
    fun selection(source: String): LiveSelection?
    fun saveSelection(source: String, selection: LiveSelection)
}

/** Never persist signed playlist/stream URLs. Identity is independent of ordering. */
class LiveLibraryPolicy(private val store: LiveLibraryStore) {
    fun sourceKey(location: String): String = hash(location)
    fun channelKey(channel: LiveChannel): String = hash(
        if (channel.tvgId.isNotBlank()) "id:${channel.tvgId}" else "name:${channel.name}\u0000${channel.tvgName}")
    fun routeKey(stream: LiveStream): String = hash(stream.url)
    fun favorite(source: String, channel: LiveChannel) = store.favorite(source, channelKey(channel))
    fun toggle(source: String, channel: LiveChannel) {
        store.setFavorite(source, channelKey(channel), !favorite(source, channel))
    }
    fun remember(source: String, channel: LiveChannel, route: Int) {
        val stream = channel.streams.getOrNull(route) ?: return
        store.saveSelection(source, LiveSelection(channelKey(channel), routeKey(stream)))
    }
    fun selection(source: String) = store.selection(source)
    fun route(channel: LiveChannel, saved: LiveSelection): Int =
        channel.streams.indexOfFirst { routeKey(it) == saved.route }.coerceAtLeast(0)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
