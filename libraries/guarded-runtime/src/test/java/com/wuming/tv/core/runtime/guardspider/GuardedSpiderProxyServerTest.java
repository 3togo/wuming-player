package com.wuming.tv.core.runtime.guardspider;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuardedSpiderProxyServerTest {
    private final HttpClient client = HttpClient.newHttpClient();

    @Test public void rewritesEscapedPlaybackUrlsAndPreservesProviderMetadata() throws Exception {
        try (GuardedSpiderProxyServer server = new GuardedSpiderProxyServer(params -> null)) {
            String original = "http://127.0.0.1:-1/proxy?do=m3u8&url=https%3A%2F%2Fmedia.test%2Findex.m3u8";
            String escaped = original.replace("/", "\\/");
            String json = "{\"parse\":0,\"url\":\"" + escaped + "\",\"header\":\"keep\",\"danmaku\":\"" + escaped + "\"}";
            var result = com.google.gson.JsonParser.parseString(server.rewritePlaybackJson(json)).getAsJsonObject();
            assertEquals(server.rewrite(original), result.get("url").getAsString());
            assertEquals(server.rewrite(original), result.get("danmaku").getAsString());
            assertEquals("keep", result.get("header").getAsString());
            assertEquals(0, result.get("parse").getAsInt());
            String array = "{\"url\":[\"480P\",\"" + escaped + "\",\"remote\",\"https://media.test/video.mp4\"]}";
            var urls = com.google.gson.JsonParser.parseString(server.rewritePlaybackJson(array)).getAsJsonObject().getAsJsonArray("url");
            assertEquals("480P", urls.get(0).getAsString());
            assertEquals(server.rewrite(original), urls.get(1).getAsString());
            assertEquals("https://media.test/video.mp4", urls.get(3).getAsString());
            String object = "{\"url\":{\"position\":0,\"values\":[{\"n\":\"HD\",\"v\":\"" + escaped + "\"}]}}";
            var nested = com.google.gson.JsonParser.parseString(server.rewritePlaybackJson(object))
                    .getAsJsonObject().getAsJsonObject("url");
            assertEquals(0, nested.get("position").getAsInt());
            assertEquals(server.rewrite(original), nested.getAsJsonArray("values").get(0).getAsJsonObject().get("v").getAsString());
            assertEquals("not json", server.rewritePlaybackJson("not json"));
        }
    }

    @Test public void streamsProviderResponseAndPassesRangeWithLifecycleCleanup() throws Exception {
        AtomicReference<Map<String, String>> parameters = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        String url;
        try (GuardedSpiderProxyServer server = new GuardedSpiderProxyServer(params -> {
            parameters.set(params);
            return new Object[] {206, "video/mp4", new ByteArrayInputStream("data".getBytes()) {
                @Override public void close() { closed.set(true); }
            }, Map.of("Content-Length", "4", "Content-Range", "bytes 0-3/10", "Connection", "close")};
        })) {
            url = server.rewrite("http://127.0.0.1:-1/proxy?do=bili&name=a%2Bb");
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url))
                    .header("Range", "bytes=0-3").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(206, response.statusCode());
            assertEquals("data", response.body());
            assertEquals("bytes 0-3/10", response.headers().firstValue("Content-Range").orElseThrow());
            assertEquals("bytes=0-3", parameters.get().get("Range"));
            assertEquals("a+b", parameters.get().get("name"));
            assertTrue(closed.get());
            assertEquals("https://example.test/proxy?do=bili", server.rewrite("https://example.test/proxy?do=bili"));
        }
        try {
            client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding());
            fail("Closed provider endpoint must not remain reachable");
        } catch (java.io.IOException expected) { }
    }

    @Test public void rewritesNestedManifestRequestsAndRestrictsEndpointAndMethods() throws Exception {
        try (GuardedSpiderProxyServer server = new GuardedSpiderProxyServer(params -> new Object[] {
                200, "application/dash+xml", new ByteArrayInputStream(
                    "<MPD>http://127.0.0.1:-1/proxy?do=bili</MPD>".getBytes(StandardCharsets.UTF_8))
        })) {
            URI url = URI.create(server.rewrite("http://127.0.0.1:-1/proxy?do=bili"));
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertFalse(response.body().contains(":-1"));
            assertTrue(response.body().contains(url.toString()));
            assertEquals(response.body().getBytes(StandardCharsets.UTF_8).length,
                    Integer.parseInt(response.headers().firstValue("Content-Length").orElseThrow()));
            assertEquals(404, client.send(HttpRequest.newBuilder(url.resolve("/proxy?do=bili")).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(405, client.send(HttpRequest.newBuilder(url).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            HttpResponse<String> head = client.send(HttpRequest.newBuilder(url).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, head.statusCode());
            assertEquals("", head.body());
        }
    }

    @Test public void providerFailuresReturnAnErrorWithoutLeakingDetails() throws Exception {
        try (GuardedSpiderProxyServer server = new GuardedSpiderProxyServer(params -> { throw new Exception("secret-url"); })) {
            URI url = URI.create(server.rewrite("http://127.0.0.1:-1/proxy?do=bili"));
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(502, response.statusCode());
            assertFalse(response.body().contains("secret"));
        }
    }
}
