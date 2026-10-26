package com.wuming.android.tv.server;

import com.wuming.android.tv.api.config.LiveConfig;
import com.wuming.android.tv.ProductCore;
import com.wuming.android.tv.bean.Device;
import com.wuming.android.tv.server.impl.Process;
import com.wuming.android.tv.server.process.Action;
import com.wuming.android.tv.server.process.Cache;
import com.wuming.android.tv.server.process.CastMediaRelay;
import com.wuming.android.tv.server.process.Desktop;
import com.wuming.android.tv.server.process.Local;
import com.wuming.android.tv.server.process.M3u8Proxy;
import com.wuming.android.tv.server.process.Media;
import com.wuming.android.tv.server.process.Parse;
import com.wuming.android.tv.server.process.Proxy;
import com.github.catvod.utils.Asset;
import com.wuming.tv.core.source.CompanionProtocol;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

public class Nano extends NanoHTTPD {

    private static final String INDEX = "index.html";

    private List<Process> process;

    public Nano(int port) {
        super(port);
        setAsyncRunner(new PooledAsyncRunner());
        addProcess();
    }

    @Override
    protected ClientHandler createClientHandler(Socket socket, InputStream input) {
        return new ClientHandler(input, socket) {
            @Override
            public void run() {
                TempFileManager files = getTempFileManagerFactory().create();
                try (OutputStream output = socket.getOutputStream()) {
                    InetAddress peer = socket.getInetAddress();
                    // NanoHTTPD calls getHostName() when constructing a session. Avoid
                    // blocking every LAN request on a reverse-DNS lookup (slow on TVs).
                    // Keep the real socket IP, never a caller-supplied forwarding header.
                    InetAddress numericPeer = InetAddress.getByAddress(peer.getHostAddress(), peer.getAddress());
                    HTTPSession session = new HTTPSession(files, input, output, numericPeer);
                    while (!socket.isClosed()) session.execute();
                } catch (Exception ignored) {
                    // Disconnects, idle timeouts and server shutdown end the client loop.
                } finally {
                    files.clear();
                    close();
                    asyncRunner.closed(this);
                }
            }
        };
    }

    private void addProcess() {
        process = new ArrayList<>();
        process.addAll(com.wuming.android.tv.edition.AppExtensions.getCurrent().serverProcesses());
        process.add(new Action());
        process.add(new Cache());
        process.add(new Local());
        process.add(new com.wuming.android.tv.packages.PackageProcess());
        process.add(new M3u8Proxy());
        process.add(new CastMediaRelay());
        process.add(new Desktop());
        process.add(new Media());
        process.add(new Parse());
        process.add(new Proxy());
    }

    public static Response ok() {
        return ok("OK");
    }

    public static Response ok(String text) {
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, text);
    }

    public static Response error(String text) {
        return error(Response.Status.INTERNAL_ERROR, text != null ? text : "Unknown error");
    }

    public static Response error(Response.Status status, String text) {
        return newFixedLengthResponse(status, MIME_PLAINTEXT, text != null ? text : "Unknown error");
    }

    public static Response json(String text) {
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text);
    }

    public static Response jsonError(Response.Status status, String text) {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", text);
    }

    public static Map<String, String> params(IHTTPSession session) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : session.getParameters().entrySet()) {
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) result.put(entry.getKey(), values.get(0));
        }
        return result;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String requestedUrl = session.getUri().trim();
        boolean castMediaAuthorized = requestedUrl.startsWith("/cast/") || requestedUrl.startsWith("/cast-relay/");
        String url = castMediaAuthorized ? CastMediaAccess.resolve(requestedUrl) : requestedUrl;
        if (castMediaAuthorized && url == null) return error(Response.Status.FORBIDDEN, "Cast media link expired");
        if (url.equals(CompanionProtocol.PAIR) || url.equals("/dlna-pair")) {
            Response response;
            if (session.getMethod() != Method.POST) {
                response = error(Response.Status.METHOD_NOT_ALLOWED, "Scan the QR code on the TV");
            } else {
                String code = session.getHeaders().get("x-pairing-code");
                String peer = session.getRemoteIpAddress();
                String token = url.equals(CompanionProtocol.PAIR) ? RemoteAccess.claimPairing(code, peer)
                        : (ProductCore.getDeviceType() == 0 && DlnaPairing.SESSION.claim(code, peer) ? "Paired" : "");
                response = token.isEmpty() ? error(Response.Status.FORBIDDEN, "QR code expired or already used. Refresh it on the TV and scan again.") : ok(token);
            }
            response.addHeader("Cache-Control", "no-store");
            return response;
        }
        if (!RemoteAccess.allow(session, url, castMediaAuthorized)) {
            return error(Response.Status.UNAUTHORIZED, "Pairing required");
        }
        Map<String, String> files = Collections.emptyMap();
        if (session.getMethod() == Method.POST) { files = new HashMap<>(); parse(session, files); }
        if (url.startsWith("/device")) return ok(Device.get().toString());
        for (Process process : process) if (process.isRequest(session, url)) return process.doResponse(session, url, files);
        return getAssets(url.substring(1));
    }

    private void parse(IHTTPSession session, Map<String, String> files) {
        try {
            String ct = session.getHeaders().get("content-type");
            if (ct != null) session.getHeaders().put("content-type", ct.replace("multipart/form-data", "multipart/form-data; charset=utf-8"));
            session.parseBody(files);
        } catch (Exception e) {
            android.util.Log.w("Nano", "Failed to parse request body", e);
        }
    }

    private Response getAssets(String path) {
        try {
            if (path.isEmpty()) path = INDEX;
            InputStream stream = Asset.open(path);
            return newFixedLengthResponse(Response.Status.OK, getMimeTypeForFile(path), stream, stream.available());
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, null, 0);
        }
    }

    private static class PooledAsyncRunner implements AsyncRunner {
        private final ThreadPoolExecutor executor;
        private final List<ClientHandler> running = Collections.synchronizedList(new ArrayList<>());

        PooledAsyncRunner() {
            int processors = Runtime.getRuntime().availableProcessors();
            int coreThreads = Math.max(4, Math.min(8, processors * 2));
            executor = new ThreadPoolExecutor(coreThreads, clientThreadLimit(processors),
                    30L, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "NanoHTTPD-client");
                t.setDaemon(true);
                return t;
            }, (task, ignored) -> {
                if (task instanceof ClientTask clientTask) clientTask.close();
            });
            executor.allowCoreThreadTimeOut(true);
        }

        @Override
        public void exec(ClientHandler clientHandler) {
            running.add(clientHandler);
            executor.execute(new ClientTask(clientHandler, running));
        }

        @Override
        public void closed(ClientHandler clientHandler) {
            running.remove(clientHandler);
        }

        @Override
        public void closeAll() {
            for (ClientHandler handler : new ArrayList<>(running)) {
                handler.close();
            }
            executor.shutdownNow();
        }

        private static final class ClientTask implements Runnable {
            private final ClientHandler handler;
            private final List<ClientHandler> running;

            private ClientTask(ClientHandler handler, List<ClientHandler> running) {
                this.handler = handler;
                this.running = running;
            }

            @Override
            public void run() {
                try {
                    handler.run();
                } finally {
                    running.remove(handler);
                }
            }

            private void close() {
                running.remove(handler);
                handler.close();
            }
        }
    }

    static int clientThreadLimit(int processors) {
        return Math.min(96, Math.max(48, Math.max(1, processors) * 8));
    }
}
