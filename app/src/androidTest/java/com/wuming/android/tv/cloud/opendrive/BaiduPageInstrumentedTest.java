package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.tv.core.media.MediaSourceDescriptor;
import com.wuming.tv.core.media.OpenDriveUri;
import com.wuming.tv.core.media.catalog.MediaManifest;
import com.wuming.android.tv.media.catalog.RoomMediaCatalog;
import com.github.catvod.net.PersistentCookieJar;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.Response;

@RunWith(AndroidJUnit4.class)
public class BaiduPageInstrumentedTest {

    @Test
    public void currentShareCookieOverridesStaleDeviceCookie() {
        Cookie stale = new Cookie.Builder().name("BDCLND").value("stale-share")
                .domain("baidu.com").path("/").build();

        String header = BaiduOpenDriveProvider.mergeCookieHeader(
                List.of(stale), "current-share");

        assertFalse(header.contains("stale-share"));
        assertTrue(header.contains("BDCLND=current-share"));
    }

    @Test
    public void liveFileHandleReturnsPlaylist() throws Exception {
        String fileId = InstrumentationRegistry.getArguments().getString("fileId", "");
        if (!OpenDriveSpider.isFileId(fileId)) {
            fileId = OpenDriveDeviceFixtures.ensureMediaUri("baidu");
        }
        assumeTrue(OpenDriveSpider.isFileId(fileId) || OpenDriveUri.isValid(fileId));
        JsonObject player = JsonParser.parseString(
                new OpenDriveSpider().playerContent("OpenDrive", fileId, List.of()))
                .getAsJsonObject();
        String url = player.get("url").getAsString();
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry
                : player.getAsJsonObject("header").entrySet()) {
            headers.put(entry.getKey(), entry.getValue().getAsString());
        }
        try (Response response = OpenDriveHttp.open(url, "bytes=0-65535", headers)) {
            assertNotNull(response.body());
            String content = response.peekBody(65_536).string();
            assertFalse("status=" + response.code() + ", bodyLength=" + content.length(),
                    !response.isSuccessful() || content.isBlank());
        }
    }

    @Test
    public void liveSharePageContainsParsableToken() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String mediaUri = arguments.getString("mediaUri", "");
        if (!OpenDriveUri.isValid(mediaUri)) {
            mediaUri = OpenDriveDeviceFixtures.ensureMediaUri("baidu");
        }
        assumeTrue(OpenDriveUri.isValid(mediaUri));
        MediaManifest manifest = new RoomMediaCatalog().find(OpenDriveUri.parse(mediaUri)).orElseThrow();
        MediaSourceDescriptor source = manifest.sources().stream()
                .filter(item -> "org.opendrive.provider".equals(item.adapterId()))
                .findFirst().orElseThrow();
        String reference = OpenDriveHandleVault.reference(
                source.locator().get("provider"), source.locator().get("handle"));
        String plaintext = OpenDriveTokenVault.get(reference);
        assertFalse("encrypted provider handle could not be opened", plaintext.isBlank());
        JsonObject file = JsonParser.parseString(plaintext).getAsJsonObject();
        String surl = file.get("surl").getAsString();
        String randsk = file.get("randsk").getAsString();
        assumeTrue("Persisted Baidu URI is account-owned, not share-backed", !surl.isBlank());

        HttpUrl host = HttpUrl.parse("https://pan.baidu.com/");
        assertNotNull(host);
        String cookieHeader = BaiduOpenDriveProvider.mergeCookieHeader(
                PersistentCookieJar.get().loadForRequest(host), randsk);
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", "https://pan.baidu.com");
        headers.put("Referer", "https://pan.baidu.com/");
        headers.put("Cookie", cookieHeader);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        String html = OpenDriveHttp.getText("https://pan.baidu.com/s/" + surl, headers);
        String token = BaiduOpenDriveProvider.extractJsToken(html);
        assertFalse("pageLength=" + html.length()
                + ", encodedBootstrap=" + html.contains("eval(decodeURIComponent(")
                + ", jsTokenMarker=" + html.contains("jsToken")
                + ", loginMarker=" + html.contains("passport.baidu.com"), token.isBlank());
    }
}
