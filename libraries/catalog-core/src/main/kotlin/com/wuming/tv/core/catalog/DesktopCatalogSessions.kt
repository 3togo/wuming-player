package com.wuming.tv.core.catalog

import com.wuming.tv.core.base.Hex
import com.wuming.tv.core.base.Hex.encodeLower
import java.security.SecureRandom
import java.util.LinkedHashMap

/** Keeps provider identifiers and embedded tokens inside the Android companion process.  */
object DesktopCatalogSessions {
    private val LIFETIME_MS = 30 * 60 * 1000L
    private const val MAXIMUM_HANDLES = 4096
    private val RANDOM = SecureRandom()
    private val ITEMS: MutableMap<String?, ItemRef?> =
        LinkedHashMap<String?, ItemRef?>(16, 0.75f, true)
    private val EPISODES: MutableMap<String?, EpisodeRef?> =
        LinkedHashMap<String?, EpisodeRef?>(16, 0.75f, true)

    @Synchronized
    @JvmStatic
    fun issueItem(source: String?, providerId: String?): String {
        cleanup()
        trim()
        val handle: String = handle("i_")
        ITEMS.put(handle, ItemRef(source, providerId, expiresAt()))
        return handle
    }

    @Synchronized
    @JvmStatic
    fun resolveItem(source: String?, handle: String?): String? {
        cleanup()
        val reference: ItemRef? = ITEMS.get(handle)
        return if (reference != null &&
            reference.source!!.equals(source)
        ) {
            reference.providerId
        } else {
            null
        }
    }

    @Synchronized
    @JvmStatic
    fun issueEpisode(source: String?, flag: String?, providerId: String?): String {
        cleanup()
        trim()
        val handle: String = handle("e_")
        EPISODES.put(handle, EpisodeRef(source, flag, providerId, expiresAt()))
        return handle
    }

    @Synchronized
    @JvmStatic
    fun resolveEpisode(source: String?, flag: String?, handle: String?): String? {
        cleanup()
        val reference: EpisodeRef? = EPISODES.get(handle)
        return if (reference != null && reference.source!!.equals(source) &&
            reference.flag!!.equals(flag)
        ) {
            reference.providerId
        } else {
            null
        }
    }

    @Synchronized
    @JvmStatic
    fun clear() {
        ITEMS.clear()
        EPISODES.clear()
    }

    private fun cleanup() {
        val now: Long = System.currentTimeMillis()
        ITEMS.entries.removeIf { entry: MutableMap.MutableEntry<String?, ItemRef?>? ->
            now >=
                entry!!.value!!.expiresAt
        }
        EPISODES.entries.removeIf { entry: MutableMap.MutableEntry<String?, EpisodeRef?>? ->
            now >=
                entry!!.value!!.expiresAt
        }
    }

    private fun trim() {
        while (ITEMS.size + EPISODES.size >= MAXIMUM_HANDLES) {
            val episodes: MutableIterator<String?> = EPISODES.keys.iterator()
            if (episodes.hasNext()) {
                EPISODES.remove(episodes.next())
                continue
            }
            val items: MutableIterator<String?> = ITEMS.keys.iterator()
            if (items.hasNext()) {
                ITEMS.remove(items.next())
            } else {
                break
            }
        }
    }

    private fun expiresAt(): Long = System.currentTimeMillis() + LIFETIME_MS

    private fun handle(prefix: String): String {
        val bytes: ByteArray = ByteArray(24)
        RANDOM.nextBytes(bytes)
        val value: StringBuilder = StringBuilder(prefix)
        value.append(encodeLower(bytes))
        return value.toString()
    }

    @JvmRecord
    private data class ItemRef(val source: String?, val providerId: String?, val expiresAt: Long)

    @JvmRecord
    private data class EpisodeRef(
        val source: String?,
        val flag: String?,
        val providerId: String?,
        val expiresAt: Long
    )
}
