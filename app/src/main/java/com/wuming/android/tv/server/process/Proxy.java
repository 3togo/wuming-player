package com.wuming.android.tv.server.process;

import com.wuming.android.tv.api.loader.BaseLoader;
import com.wuming.android.tv.server.Nano;
import com.wuming.android.tv.server.impl.Process;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import com.wuming.android.tv.utils.AppLog;

public class Proxy implements Process {
    private static final String TAG = "Proxy";
    private static final int STREAM_BUFFER_SIZE = 256 * 1024;
    // Track active streams for logging only — do NOT reject duplicate connections
    // DLNA TVs (especially for Quark cloud drive) legitimately need multiple
    // parallel range requests for buffering and seeking.
    private static final int MAX_ACTIVE_STREAMS = 256;
    private static final java.util.Set<String> activeStreams = java.util.Collections.synchronizedSet(
            new java.util.LinkedHashSet<String>(64, 0.75f) {
                @Override
                public boolean add(String s) {
                    if (size() >= MAX_ACTIVE_STREAMS) {
                        java.util.Iterator<String> it = iterator();
                        if (it.hasNext()) { it.next(); it.remove(); }
                    }
                    return super.add(s);
                }
            });

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/proxy");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        long requestStart = System.currentTimeMillis();
        Map<String, String> requestParams = Nano.params(session);
        String streamId = requestParams.get("stream");
        String range = session.getHeaders().get("range");
        AppLog.w(TAG, "doResponse: stream=" + streamId + ", range=" + range + ", url=" + url + ", params=" + requestParams.keySet());

        logStreamConnection(session, streamId, range);

        Object[] rs = null;
        try {
            Map<String, String> params = buildProxyParams(session, files, range);

            long proxyStart = System.currentTimeMillis();
            rs = BaseLoader.get().proxy(params);
            long proxyElapsed = System.currentTimeMillis() - proxyStart;
            if (rs == null || rs.length == 0) {
                AppLog.e(TAG, String.format(java.util.Locale.US,
                        "Proxy returned no data after %d ms", proxyElapsed));
                return Nano.error("Proxy returned no data");
            }
            if (rs[0] instanceof Response) return (Response) rs[0];
            Map<String, String> rawHeaders = rs.length > 3 && rs[3] != null
                    ? (Map<String, String>) rs[3] : java.util.Collections.emptyMap();
            Map<String, String> responseHeaders = new java.util.LinkedHashMap<>(rawHeaders);
            long contentLength = contentLength(responseHeaders);
            String mimeType = (String) rs[1];
            int statusCode = (Integer) rs[0];
            AppLog.d(TAG, String.format(java.util.Locale.US,
                    "Proxy upstream: code=%d, mime=%s, contentLength=%d, time=%d ms",
                    statusCode, mimeType, contentLength, proxyElapsed));
            InputStream bufferedStream = new BufferedInputStream((InputStream) rs[2], STREAM_BUFFER_SIZE);
            String dlnaMime = fixMimeTypeForDlna(mimeType, params);
            Response response = buildProxyResponse(responseHeaders, contentLength, dlnaMime, statusCode, bufferedStream);
            AppLog.d(TAG, String.format(java.util.Locale.US,
                    "Proxy response prepared in %d ms, status=%d, mime=%s -> %s, range=%s",
                    System.currentTimeMillis() - requestStart, statusCode, mimeType, dlnaMime, range));
            return response;
        } catch (Throwable e) {
            long elapsed = System.currentTimeMillis() - requestStart;
            AppLog.e(TAG, String.format(java.util.Locale.US,
                    "Proxy error after %d ms: %s", elapsed, e.getMessage()), e);
            if (rs != null && rs.length > 2 && rs[2] instanceof InputStream) {
                try { ((InputStream) rs[2]).close(); } catch (Exception ignored) {}
            }
            return Nano.error(e.getMessage());
        }
    }

    private void logStreamConnection(IHTTPSession session, String streamId, String range) {
        if (streamId == null || streamId.isEmpty()) return;
        boolean isDuplicate = !activeStreams.add(streamId);
        if (isDuplicate) {
            AppLog.w(TAG, "Duplicate connection ALLOWED for stream: " + streamId + ", range=" + range);
        } else {
            AppLog.w(TAG, "New connection for stream: " + streamId + ", range=" + range);
        }
        String[] dlnaHeaders = {"user-agent", "transfermode.dlna.org", "getcontentfeatures.dlna.org",
                "timeSeekRange.dlna.org", "playSpeed.dlna.org", "connection", "accept"};
        for (String h : dlnaHeaders) {
            String v = session.getHeaders().get(h);
            if (v != null) AppLog.w(TAG, "TV request header: " + h + "=" + v);
        }
    }

    private Map<String, String> buildProxyParams(IHTTPSession session, Map<String, String> files, String range) {
        Map<String, String> params = Nano.params(session);
        for (Map.Entry<String, String> header : session.getHeaders().entrySet()) {
            params.putIfAbsent(header.getKey(), header.getValue());
        }
        params.putAll(files);
        boolean isQuarkUrl = files.get("url") != null &&
                (files.get("url").contains("pan.quark.cn") ||
                 files.get("url").contains("quark.cn"));
        if (range != null && !range.isEmpty() && !isQuarkUrl) {
            params.put("Range", range);
            AppLog.w(TAG, "Passing through Range header: " + range);
        } else if (isQuarkUrl && range != null && !range.isEmpty()) {
            AppLog.w(TAG, "Blocking Range header for Quark URL: " + range);
        }
        return params;
    }

    @SuppressWarnings("unchecked")
    private Response buildProxyResponse(Map<String, String> responseHeaders, long contentLength,
                                        String dlnaMime, int statusCode, InputStream bufferedStream) {
        Response response;
        if (contentLength >= 0) {
            response = Nano.newFixedLengthResponse(Status.lookup(statusCode), dlnaMime, bufferedStream, contentLength);
        } else {
            response = Nano.newChunkedResponse(Status.lookup(statusCode), dlnaMime, bufferedStream);
        }
        for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
            if (!"Content-Length".equalsIgnoreCase(entry.getKey())) {
                response.addHeader(entry.getKey(), entry.getValue());
            }
        }
        response.addHeader("transferMode.dlna.org", "Streaming");
        response.addHeader("realTimeInfo.dlna.org", "REALTIME.INFORMATION=TRUE");
        if (contentLength > 0) {
            String dlnaProfile = dlnaMime.contains("matroska") ? "MPEG_PS_PAL" : "MP4";
            response.addHeader("contentFeatures.dlna.org",
                    "DLNA.ORG_PN=" + dlnaProfile + ";DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000");
            response.addHeader("Accept-Ranges", "bytes");
        }
        return response;
    }

    static long contentLength(Map<String, String> headers) {
        if (headers == null) return -1L;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!"Content-Length".equalsIgnoreCase(entry.getKey())) continue;
            try {
                return Math.max(-1L, Long.parseLong(entry.getValue()));
            } catch (Exception ignored) {
                return -1L;
            }
        }
        return -1L;
    }

    /**
     * Fix MIME type for DLNA casting. Quark cloud drive returns video/mp4 for all
     * video files, but MKV files require video/x-matroska for proper TV playback.
     * TVs will reject playback if the declared MIME doesn't match the actual container.
     */
    private static String fixMimeTypeForDlna(String upstreamMime, Map<String, String> params) {
        if (upstreamMime == null || upstreamMime.isEmpty()) {
            return "application/octet-stream";
        }

        // Check if the stream URL contains file extension information
        String streamUrl = params.get("url");
        if (streamUrl == null || streamUrl.isEmpty()) {
            // Try to get from referer or other params
            streamUrl = params.get("referer");
        }

        // If we can determine the actual file extension from the URL path, use correct MIME.
        // Only inspect the path (query/fragment stripped): matching the full URL hits false
        // positives in hostnames, e.g. "vd.wmvbo.com" contains ".wmv" and mislabelled an HLS
        // playlist as video/x-ms-wmv, which DLNA renderers then fail to play.
        if (streamUrl != null && !streamUrl.isEmpty()) {
            String pathOnly = streamUrl.toLowerCase(java.util.Locale.US);
            int query = pathOnly.indexOf('?');
            if (query >= 0) pathOnly = pathOnly.substring(0, query);
            int fragment = pathOnly.indexOf('#');
            if (fragment >= 0) pathOnly = pathOnly.substring(0, fragment);
            if (pathOnly.endsWith(".mkv") || pathOnly.contains("matroska")) {
                if (!"video/x-matroska".equals(upstreamMime)) {
                    AppLog.w(TAG, "Fixing MIME type: " + upstreamMime + " -> video/x-matroska (MKV detected from URL path)");
                    return "video/x-matroska";
                }
            } else if (pathOnly.endsWith(".mp4")) {
                if (!"video/mp4".equals(upstreamMime)) {
                    AppLog.w(TAG, "Fixing MIME type: " + upstreamMime + " -> video/mp4");
                    return "video/mp4";
                }
            } else if (pathOnly.endsWith(".avi")) {
                return "video/x-msvideo";
            } else if (pathOnly.endsWith(".wmv")) {
                return "video/x-ms-wmv";
            } else if (pathOnly.endsWith(".flv")) {
                return "video/x-flv";
            } else if (pathOnly.endsWith(".webm")) {
                return "video/webm";
            }
        }

        // If upstream says video/mp4 but we can't verify, keep it as-is
        // The DLNA profile will handle compatibility
        return upstreamMime;
    }
}
