package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class WebDavOpenDriveProviderTest {

    private static final WebDavConnectorStore.Connector CONNECTOR =
            new WebDavConnectorStore.Connector(7, "My OpenList",
                    "https://dav.example/dav/", "Media/TV", "viewer", "secret", "");

    private static final String FS_LIST = "{\"code\":200,\"data\":{\"content\":["
            + "{\"name\":\"movie.mp4\",\"is_dir\":false,\"size\":1234,\"modified\":\"2024-01-01T00:00:00\"},"
            + "{\"name\":\"sub\",\"is_dir\":true,\"size\":0,\"modified\":\"2024-01-01T00:00:00\"}]}}";

    private static final String FS_GET = "{\"code\":200,\"data\":{\"raw_url\":\"https://cdn.example/file.mp4\"}}";

    @Test
    public void exposesConnectorsAndRoutesVideoThroughStandardPlayback() throws Exception {
        AtomicReference<String> requested = new AtomicReference<>();
        WebDavOpenDriveProvider provider = provider((url, username, password) -> {
            requested.set(url);
            assertEquals("viewer", username);
            assertEquals("secret", password);
            return List.of(
                    new WebDavOpenDriveProvider.DavEntry(URI.create(url), "TV", true, 0),
                    new WebDavOpenDriveProvider.DavEntry(URI.create("Episode%201.mp4"),
                            "Episode 1.mp4", false, 1234),
                    new WebDavOpenDriveProvider.DavEntry(URI.create("poster.jpg"),
                            "poster.jpg", false, 10));
        });

        List<OpenDriveItem> roots = provider.list("root");
        assertEquals(1, roots.size());
        assertEquals("connector.7", roots.get(0).id());

        List<OpenDriveItem> files = provider.list(roots.get(0).id());
        assertEquals("https://dav.example/dav/Media/TV/", requested.get());
        assertEquals(1, files.size());
        assertEquals("Episode 1.mp4", files.get(0).name());
        assertTrue(files.get(0).id().matches("item\\.[a-f0-9]{64}"));

        OpenDrivePlayback playback = provider.resolve(files.get(0).id());
        assertEquals("https://dav.example/dav/Media/TV/Episode%201.mp4", playback.url());
        assertEquals("Basic " + Base64.getEncoder().encodeToString(
                        "viewer:secret".getBytes(StandardCharsets.UTF_8)),
                playback.headers().get("Authorization"));
        assertFalse(playback.url().contains("secret"));
    }

    @Test
    public void rejectsCrossOriginAndParentTraversalHrefs() throws Exception {
        WebDavOpenDriveProvider provider = provider((url, username, password) -> List.of(
                new WebDavOpenDriveProvider.DavEntry(
                        URI.create("https://evil.example/stolen.mp4"), "stolen.mp4", false, 1),
                new WebDavOpenDriveProvider.DavEntry(
                        URI.create("../../../outside.mp4"), "outside.mp4", false, 1),
                new WebDavOpenDriveProvider.DavEntry(
                        URI.create("%2e%2e/%2e%2e/%2e%2e/encoded.mp4"),
                        "encoded.mp4", false, 1)));

        assertTrue(provider.list("connector.7").isEmpty());
    }

    @Test
    public void validatesConfigurationUrlsAndPaths() {
        assertEquals("https://dav.example/dav/",
                WebDavConnectorStore.normalizeBaseUrl("https://dav.example/dav"));
        assertThrows(IllegalArgumentException.class,
                () -> WebDavConnectorStore.normalizeBaseUrl("https://user:pass@dav.example/dav"));
        assertThrows(IllegalArgumentException.class,
                () -> WebDavConnectorStore.normalizeInitialPath("../private"));
    }

    private static WebDavOpenDriveProvider provider(WebDavOpenDriveProvider.DavClient client) {
        return new WebDavOpenDriveProvider(new WebDavOpenDriveProvider.ConnectorSource() {
            @Override
            public List<WebDavConnectorStore.Connector> list() {
                return List.of(CONNECTOR);
            }

            @Override
            public WebDavConnectorStore.Connector get(int id) {
                return id == CONNECTOR.id() ? CONNECTOR : null;
            }
        }, client);
    }

    private static WebDavOpenDriveProvider provider(WebDavOpenDriveProvider.DavClient client,
            WebDavOpenDriveProvider.AlistClient alist) {
        return new WebDavOpenDriveProvider(new WebDavOpenDriveProvider.ConnectorSource() {
            @Override
            public List<WebDavConnectorStore.Connector> list() {
                return List.of(CONNECTOR);
            }

            @Override
            public WebDavConnectorStore.Connector get(int id) {
                return id == CONNECTOR.id() ? CONNECTOR : null;
            }
        }, client, alist);
    }

    @Test
    public void listsAndResolvesAnAListV3Instance() throws Exception {
        WebDavOpenDriveProvider provider = provider((url, u, p) -> List.of(),
                new StubAlistClient(200, false, FS_LIST, FS_GET));

        List<OpenDriveItem> roots = provider.list("root");
        assertEquals(1, roots.size());

        List<OpenDriveItem> items = provider.list(roots.get(0).id());
        assertEquals(2, items.size());

        OpenDriveItem file = items.stream().filter(i -> !i.directory()).findFirst().orElseThrow();
        OpenDriveItem folder = items.stream().filter(OpenDriveItem::directory).findFirst().orElseThrow();
        assertEquals("movie.mp4", file.name());
        assertTrue(file.id().startsWith("alist."));

        OpenDrivePlayback playback = provider.resolve(file.id());
        assertEquals("https://cdn.example/file.mp4", playback.url());
        assertFalse(playback.headers().containsKey("Authorization"));
    }

    @Test
    public void authenticatesAnAListInstanceWhenSettingsAreBlocked() throws Exception {
        WebDavOpenDriveProvider provider = provider((url, u, p) -> List.of(),
                new StubAlistClient(401, true, FS_LIST, FS_GET));

        List<OpenDriveItem> items = provider.list("connector.7");
        assertEquals(2, items.size());

        OpenDriveItem file = items.stream().filter(i -> !i.directory()).findFirst().orElseThrow();
        OpenDrivePlayback playback = provider.resolve(file.id());
        assertEquals("tok123", playback.headers().get("Authorization"));
    }

    private static final class StubAlistClient implements WebDavOpenDriveProvider.AlistClient {
        private final int settingsCode;
        private final boolean issueToken;
        private final String fsListJson;
        private final String fsGetJson;

        StubAlistClient(int settingsCode, boolean issueToken, String fsListJson, String fsGetJson) {
            this.settingsCode = settingsCode;
            this.issueToken = issueToken;
            this.fsListJson = fsListJson;
            this.fsGetJson = fsGetJson;
        }

        @Override
        public JsonObject publicSettings(String baseUrl) {
            JsonObject data = new JsonObject();
            if (settingsCode == 200) data.addProperty("version", 3);
            JsonObject root = new JsonObject();
            root.addProperty("code", settingsCode);
            root.add("data", data);
            return root;
        }

        @Override
        public String authLogin(String baseUrl, String user, String password) {
            return issueToken ? "tok123" : "";
        }

        @Override
        public JsonObject fsList(String baseUrl, String token, String path) {
            return JsonParser.parseString(fsListJson).getAsJsonObject();
        }

        @Override
        public JsonObject fsGet(String baseUrl, String token, String path) {
            return JsonParser.parseString(fsGetJson).getAsJsonObject();
        }
    }
}
