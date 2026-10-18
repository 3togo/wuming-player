package com.wuming.tv.guardhost;

import com.github.catvod.crawler.Spider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GuardedCatVodHostMainTest {
    @Test
    public void mapsCloudLinesToEmbeddedPlayers() {
        assertEquals("N", GuardedCatVodHostMain.cloudPlayerField("优汐智1\nopaque"));
        assertEquals("yq", GuardedCatVodHostMain.cloudPlayerField("夸父原画\nopaque"));
        assertEquals("SN", GuardedCatVodHostMain.cloudPlayerField("阿狸原画\nopaque"));
        assertNull(GuardedCatVodHostMain.cloudPlayerField("BD5播放\nhttps://media/video.m3u8"));
    }

    @Test
    public void carriesOnlyMatchingProviderCookiesIntoPlaybackHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/resolve", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "guarded_session=desktop; Path=/media");
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            try (Response ignored = Spider.client().newCall(
                    new Request.Builder().url(base + "/resolve").build()).execute()) {
                // Reading the response is unnecessary; OkHttp stores Set-Cookie before returning.
            }
            String result = "{\"url\":\"" + base + "/media/episode.m3u8\"," +
                    "\"header\":\"{\\\"Origin\\\":\\\"https://provider.example\\\"}\"}";

            JsonObject augmented = JsonParser.parseString(
                    GuardedCatVodHostMain.attachPlaybackCookies(result)).getAsJsonObject();
            JsonObject headers = JsonParser.parseString(augmented.get("header").getAsString())
                    .getAsJsonObject();

            assertEquals("https://provider.example", headers.get("Origin").getAsString());
            assertTrue(headers.get("Cookie").getAsString().contains("guarded_session=desktop"));
            assertEquals("", Spider.cookieHeader(base + "/outside/episode.m3u8"));
        } finally {
            server.stop(0);
        }
    }
}
