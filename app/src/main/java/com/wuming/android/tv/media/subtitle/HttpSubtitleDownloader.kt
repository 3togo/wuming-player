package com.wuming.android.tv.media.subtitle

import com.wuming.tv.core.media.subtitle.SubtitleCandidate
import com.wuming.tv.core.media.subtitle.SubtitleDownloadService
import com.wuming.android.tv.utils.UrlUtil
import com.github.catvod.net.OkHttp
import okhttp3.Call
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Path
import java.util.function.Function

/** HTTP adapter; cancellation and call ownership share the same monitor. */
class HttpSubtitleDownloader(
    private val storage: SubtitleDownloadService,
    private val calls: Function<String, Call>,
) : AutoCloseable {
    private var active: Call? = null
    private var closed = false

    constructor() : this(SubtitleDownloadService())
    constructor(storage: SubtitleDownloadService) : this(
        storage,
        Function {
            OkHttp.newCall(it)
        },
    )

    @Throws(IOException::class)
    fun download(
        candidate: SubtitleCandidate?,
        destination: Path,
    ): List<Path> {
        val uri = candidate?.downloadUri
        if (uri == null || !UrlUtil.isHttpScheme(uri.scheme)) {
            throw IOException("Subtitle download requires an HTTP(S) URI")
        }
        val call =
            synchronized(this) {
                if (closed) throw InterruptedIOException("Subtitle download cancelled")
                if (active != null) throw IOException("Subtitle download already running")
                calls.apply(uri.toString()).also {
                    active = it
                }
            }
        try {
            call.execute().use { response ->
                synchronized(this) {
                    if (closed) throw InterruptedIOException("Subtitle download cancelled")
                }
                if (!response.isSuccessful) throw IOException("Subtitle HTTP " + response.code)
                return storage.store(candidate, response.body.byteStream(), destination)
            }
        } finally {
            synchronized(this) {
                if (active === call) active = null
            }
        }
    }

    @Synchronized override fun close() {
        closed = true
        active?.cancel()
        active = null
    }
}
