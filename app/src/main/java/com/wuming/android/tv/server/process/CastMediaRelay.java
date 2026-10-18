package com.wuming.android.tv.server.process;

import com.wuming.android.tv.server.CastMediaAccess;
import com.wuming.android.tv.player.exo.CastRelayDataSource;
import com.wuming.tv.core.playback.CastRelayTransfer;
import com.wuming.tv.core.playback.PlaybackTransportPlan;
import com.wuming.android.tv.server.Nano;
import com.wuming.android.tv.server.impl.Process;
import com.wuming.android.tv.utils.AppLog;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.ResponseBody;

/** Relays authenticated live media from the phone to a DLNA renderer. */
public class CastMediaRelay implements Process {

    private static final String TAG = "CastMediaRelay";
    private static final int RELAY_BUFFER_SIZE = 256 * 1024;

    private static final int AGGREGATED_CACHE_MAX = 64;
    private static final Map<CastRemuxCache.Key, AggregatedMedia> AGGREGATED_CACHE =
            com.wuming.tv.core.cache.BoundedLruMap.create(AGGREGATED_CACHE_MAX);

    public static void clearAggregatedCache() {
        synchronized (AGGREGATED_CACHE) {
            AGGREGATED_CACHE.clear();
        }
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) {
        return url.startsWith("/cast-relay/");
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) {
        long requestStart = System.currentTimeMillis();
        CastMediaAccess.Relay relay = CastMediaAccess.getRelay(url);
        String target = CastMediaAccess.getRelayTarget(url);
        if (relay == null || target == null) return Nano.error(NanoHTTPD.Response.Status.FORBIDDEN, "Cast media link expired");

        String trace = "relay=" + CastDebug.route(url)
                + " request=" + CastDebug.nextRequest();
        CastDebug.event(trace, "event=request method=" + session.getMethod()
                + " resource=" + (target.equals(relay.url()) ? "root" : "child")
                + " upstream=" + CastDebug.endpoint(target)
                + " range=" + safeRange(session.getHeaders().get("range")));


        if (url.endsWith("/aggregated.ts") || url.endsWith("/aggregated.mp4")) {
            CastDebug.event(trace, "event=aggregate_prepare");
            NanoHTTPD.Response result = aggregatedStream(session, relay);
            CastDebug.event(trace, "event=aggregate_ready elapsedMs=" + (System.currentTimeMillis() - requestStart));
            if (session.getMethod() != NanoHTTPD.Method.HEAD && result.getData() != null)
                result.setData(CastDebug.stream(result.getData(), -1, trace));
            return result;
        }

        try {
            boolean head = session.getMethod() == NanoHTTPD.Method.HEAD;
            boolean manifestHead = head && com.wuming.tv.core.playback.DashRelayPolicy.isManifest(target);
            // Rewriting requires a complete MPD for GET probes as well as HEAD. Upstream
            // offsets cannot describe the rewritten XML; media children retain their ranges.
            Request request = com.wuming.tv.core.playback.DashRelayPolicy.upstreamRequest(
                    relay.url(), target, relay.headers(), session.getMethod().name(),
                    session.getHeaders().get("range"), session.getHeaders().get("accept"));

            long upstreamStart = System.currentTimeMillis();
            okhttp3.Response upstream = RelayHttpClient.client().newCall(request).execute();
            long upstreamElapsed = System.currentTimeMillis() - upstreamStart;

            ResponseBody body = upstream.body();
            if (body == null) {
                upstream.close();
                AppLog.e(TAG, String.format(Locale.US,
                        "Empty cast media response after %d ms, code=%d", upstreamElapsed, upstream.code()));
                return Nano.error("Empty cast media response");
            }

            AppLog.d(TAG, String.format(Locale.US,
                    "Upstream connected: code=%d, time=%d ms, contentType=%s, contentLength=%d",
                    upstream.code(), upstreamElapsed,
                    body.contentType() != null ? body.contentType().toString() : "null",
                    body.contentLength()));

            CastDebug.event(trace, "event=upstream status=" + upstream.code()
                    + " connectMs=" + upstreamElapsed + " length=" + body.contentLength());
            NanoHTTPD.Response.Status status = NanoHTTPD.Response.Status.lookup(upstream.code());
            if (status == null) status = upstream.isSuccessful() ? NanoHTTPD.Response.Status.OK : NanoHTTPD.Response.Status.BAD_REQUEST;
            String mime = body.contentType() == null ? "application/octet-stream" : body.contentType().toString();
            NanoHTTPD.Response response;
            boolean hlsPlaylist = !head && HlsPlaylistRewriter.canRewritePlaylist(upstream.code())
                    && HlsPlaylistRewriter.isHls(target, mime, body);
            boolean dashManifest = (!head || manifestHead) && !hlsPlaylist
                    && MpdPlaylistRewriter.canRewriteManifest(upstream.code())
                    && MpdPlaylistRewriter.isDash(target, mime, body);
            if (hlsPlaylist) {
                AppLog.d(TAG, "Detected HLS playlist, rewriting");
                String playlist;
                try (upstream) {
                    playlist = readManifest(upstream);
                }
                playlist = HlsPlaylistRewriter.preferHighestResolution(playlist);
                String rewritten = HlsPlaylistRewriter.rewritePlaylist(playlist, upstream.request().url(),
                        child -> CastMediaAccess.childUrl(url, child));
                byte[] bytes = rewritten.getBytes(StandardCharsets.UTF_8);
                response = NanoHTTPD.newFixedLengthResponse(status,
                        "application/vnd.apple.mpegurl",
                        new ByteArrayInputStream(bytes), bytes.length);
                AppLog.d(TAG, String.format(Locale.US,
                        "HLS playlist rewritten: original=%d bytes, rewritten=%d bytes",
                        playlist.length(), bytes.length));
            } else if (dashManifest) {
                AppLog.d(TAG, "Detected DASH manifest, rewriting");
                String manifest;
                try (upstream) {
                    manifest = readManifest(upstream);
                }
                String rewritten = MpdPlaylistRewriter.rewriteManifest(manifest, url, trace);
                byte[] bytes = rewritten.getBytes(StandardCharsets.UTF_8);
                // HEAD advertises GET's length but MUST send zero body bytes. Supplying the
                // manifest body here corrupted the next keep-alive response (XML was parsed
                // as an HTTP status line). Covered by the on-device test
                // realDashRelayHasValidHeadAndAuthenticatedMediaRanges.
                response = NanoHTTPD.newFixedLengthResponse(status,
                        "application/dash+xml",
                        new ByteArrayInputStream(head ? new byte[0] : bytes), bytes.length);
                AppLog.d(TAG, String.format(Locale.US,
                        "DASH manifest rewritten: original=%d bytes, rewritten=%d bytes",
                        manifest.length(), bytes.length));
            } else {
                long contentLength = CastRelayTransfer.responseLength(head, body.contentLength(),
                        upstream.header("Content-Length"));
                InputStream mediaStream = body.byteStream();
                // Only the root progressive item owns these capabilities. Playlist children
                // (keys, segments, alternate tracks) must never inherit its length or profile.
                CastRelayTransfer.Slice slice = CastRelayTransfer.slice(upstream.code(),
                        contentLength, upstream.header("Content-Range"),
                        relay.playbackPlan().parallelRanges() ? "bytes" : upstream.header("Accept-Ranges"),
                        upstream.header("Content-Encoding"));
                PlaybackTransportPlan plan = !head && target.equals(relay.url()) && slice != null
                        && CastRelayTransfer.remoteHttp(target)
                        ? CastRelayTransfer.plan(relay.playbackPlan(), slice) : null;
                String transferMode = "sequential";
                if (plan != null) {
                    try {
                        mediaStream = CastRelayDataSource.open(target, plan, slice);
                        upstream.close();
                        transferMode = "parallel_" + plan.parallelProfile();
                        AppLog.i(TAG, "Cast uses playback range profile=" + plan.parallelProfile());
                    } catch (Exception failure) {
                        // The original response is still open and byte-exact if range setup fails.
                        CastDebug.event(trace, "event=parallel_fallback error=" + failure.getClass().getSimpleName());
                    }
                }
                CastDebug.event(trace, "event=transfer mode=" + transferMode + " head=" + head);
                InputStream bufferedStream = new BufferedInputStream(mediaStream, RELAY_BUFFER_SIZE);
                if (!head) bufferedStream = CastDebug.stream(bufferedStream, contentLength, trace);
                response = contentLength >= 0L
                        ? NanoHTTPD.newFixedLengthResponse(status, mime, bufferedStream,
                        contentLength)
                        : NanoHTTPD.newChunkedResponse(status, mime, bufferedStream);
                AppLog.d(TAG, String.format(Locale.US,
                        "Starting stream relay: mime=%s, contentLength=%d, bufferSize=%d KB",
                        mime, contentLength, RELAY_BUFFER_SIZE / 1024));
            }
            if (!hlsPlaylist && !dashManifest) {
                copyResponseHeader(upstream, response, "Accept-Ranges");
                copyResponseHeader(upstream, response, "Content-Range");
            }
            copyResponseHeader(upstream, response, "Cache-Control");

            long totalElapsed = System.currentTimeMillis() - requestStart;
            AppLog.d(TAG, String.format(Locale.US,
                    "Response prepared in %d ms, hls=%b, dash=%b", totalElapsed, hlsPlaylist, dashManifest));
            return response;
        } catch (Exception e) {
            long totalElapsed = System.currentTimeMillis() - requestStart;
            AppLog.e("CastDebug", trace + " event=request_error elapsedMs=" + totalElapsed
                    + " error=" + e.getClass().getSimpleName()
                    + " causes=" + com.wuming.tv.core.upnp.UpnpFailureDiagnostic.types(e));
            return Nano.error(e.getMessage());
        }
    }

    private static String safeRange(String value) {
        if (value == null) return "none";
        return value.matches("bytes=[0-9]{0,19}-[0-9]{0,19}") ? value : "invalid_or_multi";
    }

    private static void copyResponseHeader(okhttp3.Response upstream, NanoHTTPD.Response response, String name) {
        String value = upstream.header(name);
        if (value != null && !value.isEmpty()) response.addHeader(name, value);
    }

    private static NanoHTTPD.Response aggregatedStream(NanoHTTPD.IHTTPSession session, CastMediaAccess.Relay relay) {
        String range = session.getHeaders().get("range");
        AggregatedMedia media = probeAggregatedMedia(relay);
        if (media != null && media.total > 0 && media.total <= TsToMp4Remuxer.MP4_REMUX_LIMIT) {
            CastRemuxCache.Lease mp4 = TsToMp4Remuxer.obtainRemuxedMp4(relay, media);
            if (mp4 != null) return serveLocalFile(mp4, "video/mp4", range);
        }
        if (media == null || media.total <= 0) {
            List<String> segments;
            try {
                segments = fetchSegmentList(relay);
            } catch (IOException e) {
                AppLog.e(TAG, "Aggregation failed to fetch playlist", e);
                return Nano.error(NanoHTTPD.Response.Status.INTERNAL_ERROR, "HLS aggregation failed");
            }
            if (segments == null || segments.isEmpty()) {
                AppLog.w(TAG, "Aggregation produced no segments (encrypted or unsupported playlist)");
                return Nano.error(NanoHTTPD.Response.Status.INTERNAL_ERROR, "HLS aggregation unsupported");
            }
            AppLog.i(TAG, "Aggregating " + segments.size() + " HLS segments (chunked, size unknown)");
            return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "video/mp2t",
                    new BufferedInputStream(CastSegmentStreams.unknown(segments, relay.headers()),
                            RELAY_BUFFER_SIZE));
        }
        long total = media.total;
        long[] bounds = parseRange(range, total);
        long start = bounds[0];
        long end = bounds[1];
        boolean partial = bounds[2] == 1;
        if (partial && start >= total) {
            NanoHTTPD.Response unsatisfiable =
                    Nano.error(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, "Bad range");
            unsatisfiable.addHeader("Content-Range", "bytes */" + total);
            return unsatisfiable;
        }
        long length = end - start + 1;
        AppLog.i(TAG, "Aggregating " + media.segments.size() + " HLS segments (" + total
                + " bytes), " + (partial ? "range " + start + "-" + end : "full file"));
        InputStream stream = CastSegmentStreams.range(media, relay.headers(), start, length);
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                partial ? NanoHTTPD.Response.Status.PARTIAL_CONTENT : NanoHTTPD.Response.Status.OK,
                "video/mp2t", stream, length);
        response.addHeader("Accept-Ranges", "bytes");
        if (partial) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
        return response;
    }

    private static long[] parseRange(String range, long total) {
        long start = 0;
        long end = total - 1;
        boolean partial = false;
        if (range != null && range.trim().toLowerCase(Locale.US).startsWith("bytes=")) {
            String spec = range.substring(range.indexOf('=') + 1).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash == 0) {
                    long suffix = Long.parseLong(spec.substring(1).trim());
                    if (suffix > 0) {
                        start = Math.max(0, total - suffix);
                        partial = true;
                    }
                } else if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash).trim());
                    String endText = spec.substring(dash + 1).trim();
                    if (!endText.isEmpty()) end = Math.min(total - 1, Long.parseLong(endText));
                    partial = true;
                }
            } catch (NumberFormatException e) {
                AppLog.w(TAG, "Unparseable Range header: " + range);
                start = 0;
                end = total - 1;
                partial = false;
            }
        }
        if (partial && (end < start || start >= total)) partial = false;
        if (partial) end = Math.min(end, total - 1);
        return new long[]{start, end, partial ? 1 : 0};
    }

    static NanoHTTPD.Response serveLocalFile(CastRemuxCache.Lease stream, String mime, String range) {
        long total = stream.length();
        long[] bounds = parseRange(range, total);
        long start = bounds[0];
        long end = bounds[1];
        boolean partial = bounds[2] == 1;
        long length = end - start + 1;
        try {
            long skipped = 0;
            while (skipped < start) {
                long step = stream.skip(start - skipped);
                if (step <= 0) throw new IOException("Unable to seek cast file");
                skipped += step;
            }
            NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(
                    partial ? NanoHTTPD.Response.Status.PARTIAL_CONTENT : NanoHTTPD.Response.Status.OK,
                    mime, stream, length);
            response.addHeader("Accept-Ranges", "bytes");
            if (partial) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
            return response;
        } catch (IOException e) {
            try { stream.close(); } catch (IOException ignored) {}
            AppLog.e(TAG, "Failed to serve local cast file", e);
            return Nano.error(NanoHTTPD.Response.Status.INTERNAL_ERROR, "File serve failed");
        }
    }

    private static AggregatedMedia probeAggregatedMedia(CastMediaAccess.Relay relay) {
        CastRemuxCache.Key key = new CastRemuxCache.Key(relay.url(), relay.headers());
        synchronized (AGGREGATED_CACHE) {
            AggregatedMedia cached = AGGREGATED_CACHE.get(key);
            if (cached != null) return cached;
        }
        List<String> segments;
        try {
            segments = fetchSegmentList(relay);
        } catch (IOException e) {
            AppLog.w(TAG, "Aggregation probe failed to fetch playlist: " + e.getMessage());
            return null;
        }
        if (segments == null || segments.isEmpty()) return null;
        long[] sizes;
        try {
            sizes = CastSegmentProbe.sizes(segments.size(), (index, method, range) ->
                    RelayHttpClient.upstreamCall(segments.get(index), key.headers(), method, range));
        } catch (IOException error) {
            AppLog.w(TAG, "Aggregation probe unavailable; using chunked transfer");
            return null;
        }
        AggregatedMedia media = new AggregatedMedia(segments, sizes);
        synchronized (AGGREGATED_CACHE) {
            AGGREGATED_CACHE.put(key, media);
        }
        AppLog.i(TAG, "Aggregation probe: " + segments.size() + " segments, " + media.total + " bytes total");
        return media;
    }

    static String readManifest(okhttp3.Response response) throws IOException {
        return com.wuming.android.tv.utils.HttpResponseText.read(response, 2 * 1024 * 1024);
    }

    private static List<String> fetchSegmentList(CastMediaAccess.Relay relay) throws IOException {
        okhttp3.Response upstream = RelayHttpClient.fetchUpstream(relay.url(), relay.headers());
        ResponseBody body = upstream.body();
        if (!HlsPlaylistRewriter.canRewritePlaylist(upstream.code()) || body == null) {
            AppLog.w(TAG, "Aggregation: playlist request failed, code=" + upstream.code());
            upstream.close();
            return null;
        }
        HttpUrl base = upstream.request().url();
        String playlist;
        try (okhttp3.Response response = upstream) {
            playlist = HlsPlaylistRewriter.preferHighestResolution(readManifest(response));
        }
        if (playlist.contains("#EXT-X-KEY")) {
            AppLog.w(TAG, "Aggregation: encrypted HLS (EXT-X-KEY) cannot be served as plain TS");
            return null;
        }
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            String variant = firstUriLine(playlist);
            HttpUrl resolved = variant == null ? null : base.resolve(variant);
            if (resolved == null) {
                AppLog.w(TAG, "Aggregation: master playlist has no resolvable variant");
                return null;
            }
            upstream = RelayHttpClient.fetchUpstream(resolved.toString(), relay.headers());
            body = upstream.body();
            if (!HlsPlaylistRewriter.canRewritePlaylist(upstream.code()) || body == null) {
                AppLog.w(TAG, "Aggregation: variant request failed, code=" + upstream.code());
                upstream.close();
                return null;
            }
            base = upstream.request().url();
            try (okhttp3.Response response = upstream) {
                playlist = readManifest(response);
            }
            if (playlist.contains("#EXT-X-KEY")) {
                AppLog.w(TAG, "Aggregation: encrypted variant HLS cannot be served as plain TS");
                return null;
            }
        }
        List<String> segments = new ArrayList<>();
        for (String line : playlist.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            HttpUrl resolved = base.resolve(line);
            if (resolved != null) segments.add(resolved.toString());
        }
        return segments;
    }

    private static String firstUriLine(String playlist) {
        for (String line : playlist.split("\\r?\\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) return line;
        }
        return null;
    }
}
