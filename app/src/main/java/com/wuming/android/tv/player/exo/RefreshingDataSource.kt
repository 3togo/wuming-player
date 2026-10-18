package com.wuming.android.tv.player.exo

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.wuming.android.tv.media.OpenDriveUriResolver
import com.wuming.tv.core.media.PlaybackTarget
import com.wuming.tv.core.playback.PlaybackTransportPlan
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Objects
import java.util.TreeMap
import java.util.function.LongSupplier

/**
 * Rewrites progressive reads to the latest signed OpenDrive target. The initial target is reused;
 * recovery is bounded to one refresh and one failover, shared by concurrent range requests.
 * DNS failures, timeouts and arbitrary server errors keep Media3's load-error policy.
 */
internal class RefreshingDataSource private constructor(
    private val upstreamFactory: DataSource.Factory,
    private val targetState: TargetState
) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var upstream: DataSource? = null

    class Factory(upstreamFactory: DataSource.Factory, initialUrl: String, initialHeaders: Map<String, String>?,
                  initialExpiry: Long, refreshUri: String, resolver: OpenDriveUriResolver) : DataSource.Factory {
        private val upstreamFactory = Objects.requireNonNull(upstreamFactory)
        private val targetState = TargetState(PlaybackTarget(initialUrl, initialHeaders, initialExpiry), refreshUri, resolver)
        override fun createDataSource(): DataSource = RefreshingDataSource(upstreamFactory, targetState)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        if (listeners.contains(transferListener)) return
        listeners.add(transferListener)
        upstream?.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        close()
        var failedTarget: TargetSnapshot? = null
        var attempt = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Playback target open cancelled")
            val target = targetState.target(attempt, failedTarget)
            try {
                val source = upstreamFactory.createDataSource()
                upstream = source
                for (listener in listeners) source.addTransferListener(listener)
                val length = source.open(resolve(dataSpec, target.playback!!))
                if (attempt > 0) android.util.Log.d("RefreshingDataSource", "Recovered playback through bounded OpenDrive target resolution")
                return length
            } catch (failure: IOException) {
                closeFailedOpen(failure)
                if (attempt == 2 || !isRefreshableResponse(failure)) throw failure
                failedTarget = target
            } catch (failure: RuntimeException) {
                closeFailedOpen(failure)
                throw failure
            }
            attempt++
        }
    }

    private fun closeFailedOpen(failure: Throwable) {
        try { close() }
        catch (cleanupError: IOException) { if (cleanupError !== failure) failure.addSuppressed(cleanupError) }
        catch (cleanupError: RuntimeException) { if (cleanupError !== failure) failure.addSuppressed(cleanupError) }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (upstream ?: throw IOException("Data source is not open")).read(buffer, offset, length)

    override fun getUri(): Uri? = upstream?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    @Throws(IOException::class)
    override fun close() {
        val current = upstream
        upstream = null
        current?.close()
    }

    // Identity, rather than structural equality, identifies a completed resolution.
    @JvmRecord
    data class TargetSnapshot(val playback: PlaybackTarget?) {
        override fun toString(): String = "TargetSnapshot[playback=$playback]"
    }

    class TargetState @JvmOverloads constructor(initial: PlaybackTarget, private val refreshUri: String,
                                               private val resolver: OpenDriveUriResolver,
                                               private val clock: LongSupplier = LongSupplier(System::currentTimeMillis)) {
        private var current = TargetSnapshot(initial)

        @Synchronized
        @Throws(IOException::class)
        fun target(recoveryStage: Int, failedTarget: TargetSnapshot?): TargetSnapshot {
            val now = clock.asLong
            val expiry = current.playback!!.expiresAtEpochMs
            val expiring = expiry > 0L && expiry <= now + REFRESH_WINDOW_MS
            if (recoveryStage == 0 && !expiring) return current
            if (recoveryStage > 0 && current !== failedTarget && (expiry == 0L || expiry > now)) return current
            try {
                val resolved = if (recoveryStage >= 2) resolver.failover(refreshUri) else resolver.refresh(refreshUri)
                current = TargetSnapshot(Objects.requireNonNull(resolved, "Missing refreshed playback target"))
                return current
            } catch (failure: RuntimeException) {
                // An outage shortly before expiry must not discard a URL which is still valid.
                if (recoveryStage == 0 && expiry > now) return current
                throw IOException("OpenDrive playback URL refresh failed", failure)
            }
        }

        companion object { private const val REFRESH_WINDOW_MS = 30_000L }
    }

    companion object {
        private fun resolve(source: DataSpec, target: PlaybackTarget): DataSpec {
            val plan = PlaybackTransportPlan.parse(target.url, null, target.headers)
            val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
            headers.putAll(source.httpRequestHeaders)
            headers.putAll(plan.networkHeaders)
            return source.buildUpon().setUri(target.url).setHttpRequestHeaders(headers).build()
        }

        @JvmStatic
        fun isRefreshableResponse(failure: Throwable?): Boolean {
            var current = failure
            while (current != null) {
                if (current is HttpDataSource.InvalidResponseCodeException) return isRefreshableStatus(current.responseCode)
                current = current.cause
            }
            return false
        }

        @JvmStatic
        fun isRefreshableStatus(responseCode: Int): Boolean =
            responseCode == 401 || responseCode == 403 || responseCode == 404 || responseCode == 410 || responseCode == 416
    }
}
