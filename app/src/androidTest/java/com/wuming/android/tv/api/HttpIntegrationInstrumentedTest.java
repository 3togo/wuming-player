package com.wuming.android.tv.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Response;

@RunWith(AndroidJUnit4.class)
public class HttpIntegrationInstrumentedTest {

    private FixtureServer server;
    private String baseUrl;

    @Before
    public void startServer() throws IOException {
        server = new FixtureServer();
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        baseUrl = "http://127.0.0.1:" + server.getListeningPort();
    }

    @After
    public void stopServer() {
        server.stop();
    }

    @Test
    public void decoderLoadsConfigAndResolvesRelativeResourcePaths() throws Exception {
        String json = Decoder.getJson(baseUrl + "/config/config.json", "config-test");

        JsonObject config = JsonParser.parseString(json).getAsJsonObject();
        JsonObject site = config.getAsJsonArray("sites").get(0).getAsJsonObject();
        assertEquals(baseUrl + "/config/sites.json", site.get("api").getAsString());
        assertEquals(baseUrl + "/scripts/spider.js?v=1", config.get("spider").getAsString());
        assertEquals("../Provider name", site.get("name").getAsString());
    }

    @Test
    public void sharedHttpClientFollowsRedirects() throws Exception {
        try (Response response = OkHttp.newCall(baseUrl + "/redirect").execute()) {
            assertEquals(200, response.code());
            assertEquals("redirected", response.body().string());
        }
    }

    @Test
    public void requestHeadersReachProviderEndpoints() throws Exception {
        try (Response response = OkHttp.newCall(baseUrl + "/headers",
                Map.of("User-Agent", "TVBox-Test", "Referer", "https://origin.test/")).execute()) {
            assertEquals("TVBox-Test|https://origin.test/", response.body().string());
        }
    }

    @Test
    public void configuredTimeoutStopsSlowProviderRequests() {
        assertThrows(IOException.class,
                () -> OkHttp.newCall(OkHttp.client(50), baseUrl + "/slow").execute());
    }

    private static final class FixtureServer extends NanoHTTPD {
        FixtureServer() { super(0); }

        @Override
        public Response serve(IHTTPSession session) {
            return switch (session.getUri()) {
                case "/config/config.json" -> newFixedLengthResponse(Response.Status.OK,
                        "application/json",
                        "{\"sites\":[{\"key\":\"fixture\",\"name\":\"../Provider name\","
                                + "\"api\":\"./sites.json\"}],\"spider\":\"../scripts/spider.js?v=1\"}");
                case "/redirect" -> redirect(session);
                case "/target" -> newFixedLengthResponse("redirected");
                case "/headers" -> newFixedLengthResponse(
                        session.getHeaders().get("user-agent") + "|" + session.getHeaders().get("referer"));
                case "/slow" -> slowResponse();
                default -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing");
            };
        }

        private Response redirect(IHTTPSession session) {
            Response response = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
            response.addHeader("Location", "http://127.0.0.1:" + getListeningPort() + "/target");
            return response;
        }

        private Response slowResponse() {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return newFixedLengthResponse("slow");
        }
    }
}
