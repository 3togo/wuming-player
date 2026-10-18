package com.wuming.tv.core.live
import org.junit.Assert.*
import org.junit.Test

class LiveLibraryPolicyTest {
    @Test fun `selection is source scoped and stable through reorder with changed route fallback`() {
        val store = object : LiveLibraryStore {
            val favorites = mutableSetOf<Pair<String, String>>()
            val selections = mutableMapOf<String, LiveSelection>()
            override fun favorite(source: String, channel: String) = source to channel in favorites
            override fun setFavorite(source: String, channel: String, favorite: Boolean) {
                if (favorite) favorites.add(source to channel) else favorites.remove(source to channel)
            }
            override fun selection(source: String) = selections[source]
            override fun saveSelection(source: String, selection: LiveSelection) { selections[source] = selection }
        }
        val policy = LiveLibraryPolicy(store)
        val channel = LiveChannel("News", tvgId = "news", streams = listOf(LiveStream("https://example.test/one?token=old"), LiveStream("https://example.test/two")))
        policy.toggle("source-a", channel)
        policy.remember("source-a", channel, 0)
        val reopened = LiveLibraryPolicy(store)
        val changed = channel.copy(streams = listOf(channel.streams[1], channel.streams[0]))
        assertTrue(reopened.favorite("source-a", changed))
        assertFalse(reopened.favorite("source-b", changed))
        assertNull(reopened.selection("source-b"))
        assertEquals(1, reopened.route(changed, reopened.selection("source-a")!!))
        assertEquals(0, reopened.route(channel.copy(streams = listOf(LiveStream("https://example.test/removed"))), reopened.selection("source-a")!!))
        assertFalse(reopened.selection("source-a").toString().contains("token"))
    }
}
