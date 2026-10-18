package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.bean.Vod;
import com.wuming.tv.core.media.PlaybackTarget;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenDriveSpiderTest {

    @Test
    public void ucOnlyForcesHlsForObservedTranscodePlaylist() {
        assertTrue(OpenDriveSpider.requiresExplicitHlsFormat(
                "https://video-play-h-zb.drive.uc.cn/qv/example/media.m3u8"));
        assertFalse(OpenDriveSpider.requiresExplicitHlsFormat(
                "https://dl-pc-zb.drive.uc.cn/file?auth_key=signed&filename=movie.mp4"));
        assertFalse(OpenDriveSpider.requiresExplicitHlsFormat(
                "https://drive.uc.cn.attacker.example/media.m3u8"));
    }

    @Test
    public void routedIdValidationRejectsInvalidProviderWithoutDecodingPayload() {
        assertFalse(OpenDriveSpider.isFileId("opendrive-file:Fake:cm9vdA"));
        assertFalse(OpenDriveSpider.isFileId("opendrive-file:fake!:cm9vdA"));
        assertFalse(OpenDriveSpider.isFileId("opendrive-file:fake:not-base64!"));
        assertTrue(OpenDriveSpider.isFileId("opendrive-file:fake:cm9vdA"));
    }

    @Test
    public void adapterCoversAccountsFoldersPlaybackLoginLogoutAndProxy() throws Exception {
        FakeProvider provider = new FakeProvider();
        OpenDriveSpider spider = new OpenDriveSpider(new OpenDriveEngine(List.of(provider)));

        JsonObject home = object(spider.homeContent(true));
        assertEquals("opendrive-accounts",
                home.getAsJsonArray("class").get(0).getAsJsonObject().get("type_id").getAsString());

        JsonObject accounts = object(spider.categoryContent(
                "opendrive-accounts", "1", true, new HashMap<>()));
        assertEquals(1, accounts.getAsJsonArray("list").size());
        JsonObject account = accounts.getAsJsonArray("list").get(0).getAsJsonObject();
        assertEquals("OpenDriveLogin:fake", account.get("action").getAsString());
        assertFalse(account.has("vod_tag"));

        JsonObject login = object(spider.action("OpenDriveLogin:fake"));
        assertEquals("https://login.example/challenge",
                login.getAsJsonObject("cloud_auth").get("authorization_url").getAsString());
        assertEquals(OpenDriveLoginState.WAITING,
                new OpenDriveEngine(List.of(provider)).pollLogin("fake", "session-1"));

        provider.authenticated = true;
        accounts = object(spider.categoryContent("opendrive-accounts", "1", true, new HashMap<>()));
        account = accounts.getAsJsonArray("list").get(0).getAsJsonObject();
        assertEquals("folder", account.get("vod_tag").getAsString());
        assertEquals(2, accounts.getAsJsonArray("list").size());
        JsonObject logout = accounts.getAsJsonArray("list").get(1).getAsJsonObject();
        assertEquals("OpenDriveLogout:fake", logout.get("action").getAsString());
        assertEquals("Log out of Fake Drive", logout.get("vod_name").getAsString());

        JsonObject folder = object(spider.categoryContent(
                account.get("vod_id").getAsString(), "1", true, new HashMap<>()));
        assertEquals(2, folder.getAsJsonArray("list").size());
        JsonObject file = folder.getAsJsonArray("list").get(1).getAsJsonObject();
        assertEquals("2 MB", file.get("vod_remarks").getAsString());

        String fileId = file.get("vod_id").getAsString();
        JsonObject detail = object(spider.detailContent(List.of(fileId)));
        assertTrue(detail.getAsJsonArray("list").get(0).getAsJsonObject()
                .get("vod_play_url").getAsString().endsWith(fileId));

        JsonObject playback = object(spider.playerContent("Fake Drive", fileId, Collections.emptyList()));
        assertEquals("https://media.example/video.mp4", playback.get("url").getAsString());
        assertEquals("Bearer opaque", playback.getAsJsonObject("header").get("Authorization").getAsString());

        Object[] proxy = spider.proxy(Map.of("provider", "fake"));
        assertEquals(200, proxy[0]);
        assertEquals("video/mp4", proxy[1]);
        assertArrayEquals("stream".getBytes(StandardCharsets.UTF_8),
                ((ByteArrayInputStream) proxy[2]).readAllBytes());

        spider.action("OpenDriveLogout:fake");
        assertFalse(provider.authenticated);
    }

    @Test
    public void accountPageIncludesBuiltInProvidersAnd115Management() throws Exception {
        OpenDriveSpider spider = new OpenDriveSpider();

        JsonObject accounts = object(spider.categoryContent(
                "opendrive-accounts", "1", true, new HashMap<>()));

        assertEquals(8, accounts.getAsJsonArray("list").size());
        for (int index = 0; index < 6; index++) {
            JsonObject account = accounts.getAsJsonArray("list").get(index).getAsJsonObject();
            assertTrue(account.get("action").getAsString().startsWith("OpenDriveLogin:"));
            assertEquals("Not logged in", account.get("vod_remarks").getAsString());
        }
        JsonObject webdav = accounts.getAsJsonArray("list").get(6).getAsJsonObject();
        assertEquals("OpenList / WebDAV", webdav.get("vod_name").getAsString());
        assertEquals("Pending — connector implementation is disabled",
                webdav.get("vod_remarks").getAsString());
        assertFalse(webdav.has("action"));
        JsonObject drive115 = accounts.getAsJsonArray("list").get(7).getAsJsonObject();
        assertEquals("opendrive-provider:115", drive115.get("vod_id").getAsString());
        assertEquals("115", drive115.get("vod_name").getAsString());
        assertEquals("Web management only", drive115.get("vod_remarks").getAsString());
        assertEquals("OpenDriveLogin:115", drive115.get("action").getAsString());
    }

    @Test
    public void routedSharePreservesPasscodeAndDropsTrackingParameters() throws Exception {
        for (String providerId : List.of("quark", "uc")) {
            var observed = new java.util.concurrent.atomic.AtomicReference<String>();
            OpenDriveProvider provider = new FakeProvider() {
                @Override public String id() { return providerId; }
                @Override public boolean supportsShare(String url) { return true; }
                @Override public List<OpenDriveItem> listShare(String url) {
                    observed.set(url);
                    return List.of();
                }
            };
            String base = "quark".equals(providerId)
                    ? "https://pan.quark.cn/s/Abc123" : "https://drive.uc.cn/s/Abc123";
            var spider = new OpenDriveSpider(new OpenDriveEngine(List.of(provider)));
            spider.detailContent(List.of(OpenDriveSpider.shareVodId(base + "?pwd=aB12&tracking=discard")));
            assertEquals(base + "?pwd=aB12", observed.get());
        }
    }

    @Test
    public void restrictedShareReturnsSafeMessageInsteadOfEmptyDetail() throws Exception {
        OpenDriveProvider provider = new FakeShareProvider();
        OpenDriveSpider spider = new OpenDriveSpider(new OpenDriveEngine(List.of(provider)));
        String id = OpenDriveSpider.shareVodId("https://pan.quark.cn/s/Abc123");

        JsonObject result = object(spider.detailContent(List.of(id)));

        assertEquals("Unable to open this cloud share. It may be expired, restricted, or require login.",
                result.get("msg").getAsString());
        assertFalse(result.toString().contains("capability-token"));
    }

    @Test
    public void externalEpisodeIsPreparedOnceAndMaterializedOnlyWhenSelected() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.authenticated = true;
        OpenDriveSpider spider = new OpenDriveSpider(new OpenDriveEngine(List.of(provider)));
        String payload = "{\"sToken\":\"capability-secret\"}";
        Vod vod = new Vod();
        vod.setPlayFrom("External cloud");
        vod.setPlayUrl("Episode 1$" + payload);
        vod.setFlags();

        assertEquals(1, spider.rewriteExternalEpisodes(vod));

        String routed = vod.getFlags().get(0).getEpisodes().get(0).getUrl();
        assertTrue(OpenDriveSpider.isDeferredId(routed));
        assertFalse(routed.contains("capability-secret"));
        assertFalse(vod.getPlayUrl().contains("capability-secret"));
        assertEquals(1, provider.authenticationChecks);
        assertEquals(1, provider.prepareCount);
        assertEquals(0, provider.materializeCount);

        spider.playerContent("External cloud", routed, Collections.emptyList());
        spider.playerContent("External cloud", routed, Collections.emptyList());

        assertEquals(1, provider.prepareCount);
        assertEquals(1, provider.materializeCount);
    }

    @Test
    public void selectedEpisodeCanBeRoutedWithoutReloadingDetailPage() {
        FakeProvider provider = new FakeProvider();
        provider.authenticated = true;
        OpenDriveSpider spider = new OpenDriveSpider(new OpenDriveEngine(List.of(provider)));

        String routed = spider.routeExternalEpisode(
                "External cloud", "{\"sToken\":\"capability-secret\"}");

        assertTrue(OpenDriveSpider.isDeferredId(routed));
        assertFalse(routed.contains("capability-secret"));
        assertEquals(1, provider.prepareCount);
        assertEquals(0, provider.materializeCount);
    }

    @Test
    public void externalEpisodeKeepsLegacyRouteUntilBuiltInProviderIsAuthenticated() {
        FakeProvider provider = new FakeProvider();
        OpenDriveSpider spider = new OpenDriveSpider(new OpenDriveEngine(List.of(provider)));
        String payload = "{\"sToken\":\"capability-secret\"}";
        Vod vod = new Vod();
        vod.setPlayFrom("External cloud");
        vod.setPlayUrl("Episode 1$" + payload);
        vod.setFlags();

        assertEquals(0, spider.rewriteExternalEpisodes(vod));
        assertEquals(payload, vod.getFlags().get(0).getEpisodes().get(0).getUrl());
    }

    @Test
    public void ucRouteLabelCannotBeClaimedByEarlierQuarkProbe() throws Exception {
        FakeProvider quark = new FakeProvider() {
            @Override public String id() { return "quark"; }
        };
        FakeProvider uc = new FakeProvider() {
            @Override public String id() { return "uc"; }
        };
        quark.authenticated = true;
        uc.authenticated = true;
        OpenDriveSpider spider = new OpenDriveSpider(new OpenDriveEngine(List.of(quark, uc)));
        Vod vod = new Vod();
        vod.setPlayFrom("优汐无限1");
        vod.setPlayUrl("Episode 1${\"provider\":\"quark\",\"sToken\":\"capability-secret\"}");
        vod.setFlags();

        assertEquals(1, spider.rewriteExternalEpisodes(vod));
        String routed = vod.getFlags().get(0).getEpisodes().get(0).getUrl();
        spider.playerContent("优汐无限1", routed, Collections.emptyList());

        assertEquals(0, quark.prepareCount);
        assertEquals(0, quark.materializeCount);
        assertEquals(1, uc.prepareCount);
        assertEquals(1, uc.materializeCount);
    }

    @Test
    public void canonicalUriUsesUniversalResolverAndPreservesHeaders() throws Exception {
        FakeProvider provider = new FakeProvider();
        String uri = "opendrive://local/asset/01234567-89ab-cdef-0123-456789abcdef";
        OpenDriveSpider spider = new OpenDriveSpider(new OpenDriveEngine(List.of(provider)),
                value -> {
                    assertEquals(uri, value);
                    return new PlaybackTarget("https://cdn.example/canonical.m3u8",
                            Map.of("Referer", "https://app.example/"), 987654321L);
                });

        JsonObject detail = object(spider.detailContent(List.of(uri)));
        assertTrue(detail.getAsJsonArray("list").get(0).getAsJsonObject()
                .get("vod_play_url").getAsString().endsWith(uri));

        JsonObject playback = object(spider.playerContent("OpenDrive", uri, Collections.emptyList()));
        assertEquals("https://cdn.example/canonical.m3u8", playback.get("url").getAsString());
        assertEquals("https://app.example/",
                playback.getAsJsonObject("header").get("Referer").getAsString());
    }

    @Test
    public void canonicalBaiduPreviewDeclaresHlsWithoutFileSuffix() throws Exception {
        String uri = "opendrive://local/asset/01234567-89ab-cdef-0123-456789abcdef";
        OpenDriveSpider spider = new OpenDriveSpider(
                new OpenDriveEngine(List.of(new FakeProvider())),
                value -> new PlaybackTarget(
                        "https://pan.baidu.com/share/streaming?type=M3U8_AUTO_1080",
                        Collections.emptyMap(), 987654321L));

        JsonObject playback = object(spider.playerContent("OpenDrive", uri, Collections.emptyList()));

        assertEquals("application/x-mpegURL", playback.get("format").getAsString());
    }

    @Test
    public void baiduPreparedOriginalDoesNotForceHlsFormat() throws Exception {
        FakeProvider provider = new FakeProvider() {
            @Override
            public String id() {
                return "baidu";
            }

            @Override
            public boolean authenticated() {
                return true;
            }
        };
        OpenDriveSpider spider = new OpenDriveSpider(new OpenDriveEngine(List.of(provider)));
        Vod vod = new Vod();
        vod.setPlayFrom("External cloud");
        vod.setPlayUrl("Episode 1${\"sToken\":\"capability-secret\"}");
        vod.setFlags();
        assertEquals(1, spider.rewriteExternalEpisodes(vod));
        String fileId = vod.getFlags().get(0).getEpisodes().get(0).getUrl();

        JsonObject playback = object(spider.playerContent("Baidu", fileId, Collections.emptyList()));

        assertFalse(playback.has("format"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedCanonicalUriFailsClosedBeforeResolver() throws Exception {
        OpenDriveSpider spider = new OpenDriveSpider(new OpenDriveEngine(List.of(new FakeProvider())),
                value -> {
                    throw new AssertionError("Malformed URI must not reach the catalog resolver");
                });

        spider.playerContent("OpenDrive", "opendrive://local/asset", Collections.emptyList());
    }

    private static JsonObject object(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }

    private static class FakeProvider implements OpenDriveProvider {

        private boolean authenticated;
        private int authenticationChecks;
        private int prepareCount;
        private int materializeCount;

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public String displayName() {
            return "Fake Drive";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String availabilityMessage() {
            return "";
        }

        @Override
        public boolean authenticated() {
            authenticationChecks++;
            return authenticated;
        }

        @Override
        public OpenDriveLoginChallenge beginLogin() {
            return new OpenDriveLoginChallenge(
                    "session-1", "https://login.example/challenge", 123456789L);
        }

        @Override
        public OpenDriveLoginState pollLogin(String sessionId) {
            assertEquals("session-1", sessionId);
            return OpenDriveLoginState.WAITING;
        }

        @Override
        public List<OpenDriveItem> list(String parentId) {
            assertEquals("root", parentId);
            return List.of(
                    new OpenDriveItem("folder-1", "Shows", true, 0),
                    new OpenDriveItem("file-1", "Episode.mp4", false, 2L * 1024L * 1024L));
        }

        @Override
        public OpenDrivePreparedExternal prepareExternal(OpenDriveExternalPayload payload) {
            if (!payload.raw().contains("capability-secret")) return null;
            prepareCount++;
            return OpenDrivePreparedExternal.of(() -> {
                materializeCount++;
                return "file-1";
            });
        }

        @Override
        public OpenDrivePlayback resolve(String itemId) {
            assertEquals("file-1", itemId);
            return new OpenDrivePlayback("https://media.example/video.mp4",
                    Map.of("Authorization", "Bearer opaque"), 123456789L);
        }

        @Override
        public OpenDriveProxyResponse proxy(Map<String, String> params) {
            return new OpenDriveProxyResponse(200, "video/mp4",
                    new ByteArrayInputStream("stream".getBytes(StandardCharsets.UTF_8)),
                    Collections.emptyMap());
        }

        @Override
        public void logout() {
            authenticated = false;
        }
    }

    private static final class FakeShareProvider extends FakeProvider {

        @Override
        public String id() {
            return "quark";
        }

        @Override
        public boolean supportsShare(String url) {
            return true;
        }

        @Override
        public List<OpenDriveItem> listShare(String url) {
            throw new IllegalStateException("capability-token");
        }
    }
}
