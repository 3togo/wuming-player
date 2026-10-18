package com.wuming.android.tv.player.exo

import android.net.TrafficStats
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.wuming.android.tv.utils.AppLog
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Emits privacy-safe throughput measurements at ExoPlayer's data-source boundary.
 *
 * The URL path, query, cookies, and header values are deliberately excluded because cloud-drive
 * download URLs contain credentials. A progress record is emitted every few seconds so a transfer
 * that remains open for the whole playback session is still measurable.
 */
@UnstableApi
internal class PlaybackTransferTrace : TransferListener {

    internal companion object {
        const val TAG = "PlaybackTransfer"
        private const val LOG_INTERVAL_MILLIS = 3_000L
        private val NEXT_ID = AtomicLong()
    }

    private val transfers = IdentityHashMap<DataSource, Transfer>()

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        // Initialization can fail and therefore has no guaranteed matching end callback. Start
        // accounting only after the connection has opened successfully.
    }

    @Synchronized
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val transfer = Transfer(
            NEXT_ID.incrementAndGet(),
            now,
            uidRxBytes(),
            host(dataSpec.uri),
            dataSpec.position,
            dataSpec.length,
        )
        transfers[source] = transfer
        AppLog.i(TAG, describe("start", source, dataSpec, isNetwork, transfer, now, 0L))
    }

    @Synchronized
    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        val transfer = transfers[source] ?: return
        transfer.totalBytes += if (bytesTransferred > 0) bytesTransferred else 0
        transfer.intervalBytes += if (bytesTransferred > 0) bytesTransferred else 0
        val now = SystemClock.elapsedRealtime()
        if (now - transfer.lastLogAt < LOG_INTERVAL_MILLIS) return
        AppLog.i(TAG, describe("progress", source, dataSpec, isNetwork, transfer, now, transfer.intervalBytes))
        transfer.intervalBytes = 0L
        transfer.lastLogAt = now
        transfer.lastUidRxBytes = uidRxBytes()
    }

    @Synchronized
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        val transfer = transfers.remove(source) ?: return
        val now = SystemClock.elapsedRealtime()
        AppLog.i(TAG, describe("end", source, dataSpec, isNetwork, transfer, now, transfer.intervalBytes))
    }

    private fun describe(
        event: String,
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        transfer: Transfer,
        now: Long,
        intervalBytes: Long,
    ): String {
        val elapsed = if (now - transfer.startedAt > 1L) now - transfer.startedAt else 1L
        val intervalElapsed = if (now - transfer.lastLogAt > 1L) now - transfer.lastLogAt else 1L
        val currentUidRx = uidRxBytes()
        val uidTotal = delta(currentUidRx, transfer.startedUidRxBytes)
        val uidInterval = delta(currentUidRx, transfer.lastUidRxBytes)
        val resolved = source.uri
        val responseHeaders = source.responseHeaders
        return "event=" + event +
            ",id=" + transfer.id +
            ",network=" + isNetwork +
            ",host=" + host(if (resolved == null) dataSpec.uri else resolved) +
            ",originHost=" + transfer.originHost +
            ",code=" + responseCode(source) +
            ",position=" + transfer.position +
            ",requestedLength=" + transfer.requestedLength +
            ",contentLength=" + header(responseHeaders, "Content-Length") +
            ",contentRange=" + header(responseHeaders, "Content-Range") +
            ",contentType=" + header(responseHeaders, "Content-Type") +
            ",requestCookie=" + hasHeader(dataSpec.httpRequestHeaders, "Cookie") +
            ",requestReferer=" + hasHeader(dataSpec.httpRequestHeaders, "Referer") +
            ",requestUserAgent=" + hasHeader(dataSpec.httpRequestHeaders, "User-Agent") +
            ",bytes=" + transfer.totalBytes +
            ",elapsedMs=" + elapsed +
            ",avgBps=" + bytesPerSecond(transfer.totalBytes, elapsed) +
            ",intervalBytes=" + intervalBytes +
            ",intervalMs=" + intervalElapsed +
            ",intervalBps=" + bytesPerSecond(intervalBytes, intervalElapsed) +
            ",uidRxBytes=" + uidTotal +
            ",uidIntervalRxBytes=" + uidInterval
    }

    private fun responseCode(source: DataSource): Int =
        if (source is HttpDataSource) source.responseCode else -1

    private fun host(uri: Uri?): String {
        if (uri == null || uri.host == null) return ""
        return uri.host!!.lowercase(Locale.ROOT)
    }

    private fun hasHeader(headers: Map<String, String>?, name: String): Boolean {
        if (headers == null) return false
        for (key in headers.keys) if (name.equals(key, ignoreCase = true)) return true
        return false
    }

    private fun header(headers: Map<String, List<String>?>?, name: String): String {
        if (headers == null) return ""
        for ((key, value) in headers) {
            if (!name.equals(key, ignoreCase = true) || value == null || value.isEmpty()) continue
            val v = value[0] as String?
            return if (v == null) "" else v.replace(',', ';')
        }
        return ""
    }

    private fun uidRxBytes(): Long {
        val value = TrafficStats.getUidRxBytes(Process.myUid())
        return if (value < 0L) -1L else value
    }

    private fun delta(current: Long, previous: Long): Long =
        if (current < 0L || previous < 0L) -1L else if (current - previous > 0L) current - previous else 0L

    private fun bytesPerSecond(bytes: Long, elapsedMillis: Long): Long =
        if (elapsedMillis <= 0L) 0L else bytes * 1_000L / elapsedMillis

    private class Transfer(
        val id: Long,
        val startedAt: Long,
        val startedUidRxBytes: Long,
        val originHost: String,
        val position: Long,
        val requestedLength: Long,
    ) {
        var lastLogAt: Long = startedAt
        var lastUidRxBytes: Long = startedUidRxBytes
        var totalBytes: Long = 0L
        var intervalBytes: Long = 0L
    }
}
