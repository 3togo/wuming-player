package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;

import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.db.AppDatabase;
import com.wuming.tv.core.media.OpenDriveProviderIdentityCodec;
import com.wuming.android.tv.media.AndroidMediaPipelineFactory;
import com.wuming.tv.core.media.OpenDriveUri;
import com.wuming.android.tv.media.catalog.MediaSourceEntity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Discovers opaque, app-owned device fixtures without exporting credentials. */
public final class OpenDriveDeviceFixtures {

    private static final String TAG = "OpenDriveFixtures";

    private OpenDriveDeviceFixtures() {
    }

    public static String mediaUri(String preferredProvider) {
        List<MediaSourceEntity> sources = AppDatabase.get().getMediaCatalogDao().findAllSources();
        for (int index = sources.size() - 1; index >= 0; index--) {
            MediaSourceEntity source = sources.get(index);
            if (!OpenDriveProviderIdentityCodec.ADAPTER_ID.equals(source.adapterId)
                    || !OpenDriveUri.isValid(source.resourceUri)) continue;
            JsonObject locator = object(source.locatorJson);
            String provider = string(locator, "provider");
            String handle = string(locator, "handle");
            if (!preferredProvider.isBlank() && !preferredProvider.equals(provider)) continue;
            String reference = source.credentialRef.isBlank()
                    ? OpenDriveHandleVault.reference(provider, handle) : source.credentialRef;
            if (!OpenDriveTokenVault.get(reference).isBlank()) return source.resourceUri;
        }
        return "";
    }

    /** Persists one real provider item in the same vault/catalog path used by the app. */
    public static String ensureMediaUri(String preferredProvider) {
        String existing = mediaUri(preferredProvider);
        if (!existing.isBlank() || preferredProvider == null || preferredProvider.isBlank()) {
            return existing;
        }
        return provisionMediaUri(preferredProvider);
    }

    /** Re-scans the live account and persists a current item instead of reusing a stale fixture. */
    public static String refreshMediaUri(String preferredProvider) {
        if (preferredProvider == null || preferredProvider.isBlank()) return "";
        return provisionMediaUri(preferredProvider);
    }

    private static String provisionMediaUri(String preferredProvider) {
        try {
            OpenDriveEngine engine = new OpenDriveEngine();
            OpenDriveProvider provider = engine.provider(preferredProvider);
            if (!provider.authenticated()) {
                Log.i(TAG, "provider=" + preferredProvider + ",provision=not_authenticated");
                return "";
            }
            ArrayDeque<String> folders = new ArrayDeque<>();
            folders.add("root");
            int visited = 0;
            while (!folders.isEmpty() && visited++ < 32) {
                for (OpenDriveItem item : engine.list(preferredProvider, folders.removeFirst())) {
                    if (item.directory()) {
                        folders.addLast(item.id());
                    } else if (engine.persistHandle(preferredProvider, item.id())) {
                        String uri = AndroidMediaPipelineFactory.providerIdentityCodec().encode(
                                preferredProvider, item.id(), item.name());
                        if (OpenDriveUri.isValid(uri)) {
                            Log.i(TAG, "provider=" + preferredProvider + ",provision=stored");
                            return uri;
                        }
                    }
                }
            }
            Log.i(TAG, "provider=" + preferredProvider + ",provision=no_media");
        } catch (Exception error) {
            Log.i(TAG, "provider=" + preferredProvider + ",provision=failed,type="
                    + error.getClass().getSimpleName());
        }
        return "";
    }

    public static List<ShareFixture> publicShares() {
        LinkedHashMap<String, ShareFixture> fixtures = new LinkedHashMap<>();
        for (ShareFixture fixture : bundledPublicShares()) add(fixtures, fixture);
        for (History history : AppDatabase.get().getHistoryDao().findAll()) {
            String value = history.getEpisodeUrl();
            ShareFixture fixture = share(value);
            add(fixtures, fixture);
        }
        List<MediaSourceEntity> sources = AppDatabase.get().getMediaCatalogDao().findAllSources();
        for (int index = sources.size() - 1; index >= 0; index--) {
            MediaSourceEntity source = sources.get(index);
            if (!OpenDriveProviderIdentityCodec.ADAPTER_ID.equals(source.adapterId)) continue;
            JsonObject locator = object(source.locatorJson);
            String provider = string(locator, "provider");
            String handle = string(locator, "handle");
            String reference = source.credentialRef.isBlank()
                    ? OpenDriveHandleVault.reference(provider, handle) : source.credentialRef;
            JsonObject file = object(OpenDriveTokenVault.get(reference));
            ShareFixture fixture = reconstructed(provider, file);
            add(fixtures, fixture);
        }
        return new ArrayList<>(fixtures.values());
    }

    /** Public, disposable fixtures bundled only in the test runner; never credentials. */
    public static List<ShareFixture> bundledPublicShares() {
        List<ShareFixture> fixtures = new ArrayList<>();
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("opendrive-public-shares.json")) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (var value : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject item = value.getAsJsonObject();
                String providerId = string(item, "providerId");
                String url = string(item, "url");
                String fileName = string(item, "fileName");
                ShareFixture parsed = share(url);
                if (parsed != null && parsed.providerId().equals(providerId)
                        && !fileName.isBlank()) {
                    fixtures.add(new ShareFixture(providerId, parsed.url(), fileName));
                }
            }
        } catch (Exception error) {
            Log.i(TAG, "bundledPublicShares=unavailable,type="
                    + error.getClass().getSimpleName());
        }
        return fixtures;
    }

    /** Finds the newest raw external episode for a provider without logging its payload. */
    public static ExternalFixture externalEpisode(String providerId) {
        ExternalFixture newest = null;
        long newestTime = Long.MIN_VALUE;
        for (History history : AppDatabase.get().getHistoryDao().findAll()) {
            String flag = history.getVodFlag();
            String payload = history.getEpisodeUrl();
            if (!providerId.equals(ExternalEpisodePayload.labeledProviderId(flag))
                    || payload.isBlank() || OpenDriveUri.hasScheme(payload)
                    || OpenDriveSpider.isRoutedId(payload)) continue;
            if (history.getCreateTime() >= newestTime) {
                newest = new ExternalFixture(flag, payload);
                newestTime = history.getCreateTime();
            }
        }
        return newest;
    }

    private static void add(LinkedHashMap<String, ShareFixture> fixtures, ShareFixture fixture) {
        if (fixture != null) fixtures.putIfAbsent(fixture.providerId() + '\n' + fixture.url(), fixture);
    }

    private static ShareFixture reconstructed(String provider, JsonObject file) {
        String value = switch (provider) {
            case "alipan" -> url("https://www.alipan.com/s/", string(file, "shareId"), "");
            case "quark" -> bool(file, "personal") ? ""
                    : url("https://pan.quark.cn/s/", string(file, "shareId"), "");
            case "uc" -> bool(file, "personal") ? ""
                    : url("https://drive.uc.cn/s/", string(file, "shareId"), "");
            case "baidu" -> url("https://pan.baidu.com/s/1", string(file, "surl"), "");
            case "123pan" -> url("https://www.123pan.com/s/", string(file, "shareKey"),
                    string(file, "sharePassword"));
            default -> "";
        };
        return value.isBlank() ? null : share(value);
    }

    private static ShareFixture share(String value) {
        String canonical = AliPanOpenDriveProvider.canonicalShareUrl(value);
        if (canonical != null) return new ShareFixture("alipan", canonical);
        canonical = QuarkOpenDriveProvider.canonicalShareUrl(value);
        if (canonical != null) return new ShareFixture("quark", AbstractOpenDriveProvider.shareUrlWithPasscode(canonical, value, "Quark"));
        canonical = UcOpenDriveProvider.canonicalShareUrl(value);
        if (canonical != null) return new ShareFixture("uc", AbstractOpenDriveProvider.shareUrlWithPasscode(canonical, value, "UC"));
        canonical = BaiduOpenDriveProvider.canonicalShareUrl(value);
        if (canonical != null) return new ShareFixture("baidu", canonical);
        canonical = Pan123OpenDriveProvider.canonicalShareUrl(value);
        if (canonical != null) return new ShareFixture("123pan", canonical);
        canonical = TianyiOpenDriveProvider.canonicalShareUrl(value);
        return canonical == null ? null : new ShareFixture("tianyi", canonical);
    }

    private static String url(String prefix, String id, String passcode) {
        if (id.isBlank()) return "";
        return prefix + id + (passcode.isBlank() ? "" : "?pwd=" + passcode);
    }

    private static JsonObject object(String value) {
        try {
            return JsonParser.parseString(value).getAsJsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    public record ShareFixture(String providerId, String url, String fileName) {
        public ShareFixture(String providerId, String url) {
            this(providerId, url, "");
        }
    }

    public record ExternalFixture(String flag, String payload) {
    }
}
