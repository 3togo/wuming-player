package com.wuming.android.tv.player.offline

import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Path
import com.wuming.android.tv.App
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.VideoNetworkPolicy
import com.wuming.android.tv.utils.VideoNetworkPolicy.downloadRequirementFlags
import com.wuming.android.tv.utils.VideoNetworkPolicy.requireAllowed
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Interceptor
import okhttp3.Interceptor.Chain
import okhttp3.OkHttpClient

/** Process-wide, non-evicting storage used only by explicit offline downloads.  */
@UnstableApi
object OfflinePlaybackStore {
    private const val TAG = "OfflinePlaybackStore"
    private val THREAD_ID = AtomicInteger()
    private var cache: SimpleCache? = null
    private var manager: DownloadManager? = null
    private var executor: ExecutorService? = null
    private var database: StandaloneDatabaseProvider? = null

    @Synchronized
    @JvmStatic
    fun downloadManager(): DownloadManager {
        val currentManager = manager
        if (currentManager != null) return currentManager
        val downloadExecutor = Executors.newFixedThreadPool(
            2,
            ThreadFactory { runnable: Runnable? ->
                val thread: Thread = Thread(
                    runnable,
                    "offline-download-" + THREAD_ID.incrementAndGet()
                )
                thread.setPriority(Thread.NORM_PRIORITY - 1)
                thread
            }
        )
        executor = downloadExecutor
        val client: OkHttpClient = OkHttp.player().newBuilder()
            .addInterceptor(
                Interceptor { chain: Chain ->
                    requireAllowed()
                    val currentChain = chain
                    var request = currentChain.request()
                    val headers = AuthHeaderRegistry.lookup(request.url.toString())
                    if (headers != null) {
                        val builder = request.newBuilder()
                        for ((key, value) in headers) builder.addHeader(key, value)
                        request = builder.build()
                    }
                    currentChain.proceed(request)
                }
            )
            .build()
        val upstream: DataSource.Factory = OkHttpDataSource.Factory(client)
        val newManager = DownloadManager(
            App.get(),
            database(),
            cache(),
            upstream,
            downloadExecutor
        )
        newManager.setMaxParallelDownloads(2)
        newManager.setMinRetryCount(5)
        newManager.setRequirements(Requirements(downloadRequirementFlags()))
        newManager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                for (download in downloadManager.currentDownloads) {
                    val headers = AuthHeaderRegistry.deserialize(download.request.data)
                    if (headers.isNotEmpty()) {
                        AuthHeaderRegistry.register(download.request.uri.toString(), headers)
                    }
                }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                AuthHeaderRegistry.unregister(download.request.uri.toString())
            }
        })
        manager = newManager
        return newManager
    }

    @Synchronized
    @JvmStatic
    fun updateRequirements() {
        manager?.setRequirements(Requirements(downloadRequirementFlags()))
    }

    @Synchronized
    @JvmStatic
    private fun cache(): SimpleCache {
        val currentCache = cache
        if (currentCache != null) return currentCache
        val directory: File = File(Path.files(), "offline_media")
        check(!(!directory.exists() && !directory.mkdirs())) {
            "Offline media directory could not be created"
        }
        val newCache = SimpleCache(directory, NoOpCacheEvictor(), database())
        cache = newCache
        return newCache
    }

    @JvmStatic
    private fun database(): StandaloneDatabaseProvider {
        var currentDb = database
        if (currentDb == null) {
            currentDb = StandaloneDatabaseProvider(App.get())
            database = currentDb
        }
        return currentDb
    }

    /** Reads completed/offline spans first and never lets ordinary playback mutate that cache.  */
    @JvmStatic
    fun readOnly(upstream: DataSource.Factory): DataSource.Factory {
        // Defer opening the on-disk index until Media3's loader asks for a data source. Creating a
        // MediaItem often happens on the UI thread and must not pay this disk-I/O cost.
        return DataSource.Factory {
            if (!hasOfflineIndex()) return@Factory upstream.createDataSource()
            CacheDataSource.Factory()
                .setCache(cache())
                .setUpstreamDataSourceFactory(upstream)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
        }
    }

    @JvmStatic
    private fun hasOfflineIndex(): Boolean {
        val children: Array<File?>? = directory().listFiles()
        return children != null && children.size > 0
    }

    @JvmStatic
    fun directory(): File = File(Path.files(), "offline_media")

    /**
     * Explicit cleanup for process-wide resources. Should be called when the offline playback
     * feature is no longer needed (e.g., on app shutdown or low memory conditions).
     */
    @Synchronized
    @JvmStatic
    fun release() {
        manager?.release()
        manager = null
        executor?.shutdownNow()
        executor = null
        // Dropping these references alone leaks the cache directory lock and the
        // SQLite handle: the next cache() would open a second SimpleCache over a
        // folder the abandoned instance still owns.
        val currentCache = cache
        cache = null
        if (currentCache != null) {
            try {
                currentCache.release()
            } catch (e: Exception) {
                AppLog.w(TAG, "Unable to release offline cache", e)
            }
        }
        val currentDatabase = database
        database = null
        if (currentDatabase != null) {
            try {
                currentDatabase.close()
            } catch (e: Exception) {
                AppLog.w(TAG, "Unable to close offline database", e)
            }
        }
    }
}
