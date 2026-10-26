package com.wuming.android.tv.player

import android.net.Uri
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.bean.Flag
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.player.extractor.Push
import com.wuming.android.tv.player.extractor.Strm
import com.wuming.android.tv.player.extractor.Video
import com.wuming.android.tv.player.extractor.Youtube
import com.wuming.android.tv.exception.ExtractException
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.ResUtil
import com.wuming.tv.core.source.PlaybackProtocolPolicy
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.Task
import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class Source {

    private val extractors: MutableList<Extractor> = ArrayList()

    init {
        extractors.addAll(com.wuming.android.tv.edition.AppExtensions.current.beforeLocalExtractors())
        extractors.add(Push())
        extractors.add(Strm())
        extractors.addAll(com.wuming.android.tv.edition.AppExtensions.current.afterLocalExtractors())
        extractors.add(Video())
        extractors.add(Youtube())
    }

    private fun getExtractor(uri: Uri): Extractor? {
        for (extractor in extractors) { if (extractor.match(uri)) return extractor }
        return null
    }

    private fun addCallable(iterator: MutableIterator<Episode?>, items: MutableList<Callable<List<Episode>>>) {
        val episode = iterator.next() ?: return
        val url = episode.getUrl()
        val extensionParser = com.wuming.android.tv.edition.AppExtensions.current.episodeParser(url)
        if (extensionParser != null) {
            items.add(extensionParser)
            iterator.remove()
        } else if (Youtube.Parser.match(url)) {
            items.add(Callable { Youtube.Parser.get(url).call() })
            iterator.remove()
        }
    }

    @Throws(Exception::class)
    fun parse(vod: Vod) {
        val executor: ExecutorService = Task.largeExecutor()
        for (flag in vod.flags) {
            val items: MutableList<Callable<List<Episode>>> = ArrayList()
            @Suppress("UNCHECKED_CAST")
            val iterator = flag.getEpisodes().iterator() as MutableIterator<Episode?>
            while (iterator.hasNext()) addCallable(iterator, items)
            for (future in executor.invokeAll(items, 30, TimeUnit.SECONDS)) {
                try {
                    flag.getEpisodes().addAll(future.get())
                } catch (ignored: CancellationException) {
                }
            }
        }
    }

    @Throws(Exception::class)
    fun fetch(result: Result): String? {
        val uri: Uri = result.url.uri()
        val url: String = result.url.v()
        if (PlaybackProtocolPolicy.unsupported(url)) {
            throw ExtractException(ResUtil.getString(R.string.error_play_protocol_unsupported))
        }
        if (url.contains("[") && url.contains("]")) {
            AppLog.i("Source", "IPv6 media URL entered extractor selection")
        }
        val extractor = getExtractor(uri)
        if (extractor != null) {
            AppLog.i("Source", "Extractor matched: " + extractor.javaClass.simpleName)
            result.setParse(0)
        }
        if (extractor is Video) result.setParse(1)
        val finalUrl = if (extractor == null) url else extractor.fetch(result)
        if (finalUrl.contains("[") && finalUrl.contains("]")) {
            AppLog.i("Source", "Extractor returned an IPv6 media URL")
        }
        return finalUrl
    }

    fun stop() {
        extractors.forEach { it.stop() }
    }

    fun exit() {
        Task.execute { extractors.forEach { it.exit() } }
    }

    interface Extractor {
        @Throws(Exception::class)
        fun fetch(result: Result): String {
            return fetch(result.url.v())
        }

        @Throws(Exception::class)
        fun fetch(url: String): String

        fun match(uri: Uri): Boolean

        fun stop()

        fun exit()
    }

    private class Loader {
        companion object {
            @Volatile
            var INSTANCE = Source()
        }
    }

    companion object {
        @JvmStatic
        fun get(): Source = Loader.INSTANCE
    }
}
