package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertTrue;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.github.catvod.net.PersistentCookieJar;
import com.google.gson.JsonObject;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in, read-only check of the membership seen by the actual app session. */
@RunWith(AndroidJUnit4.class)
public class QuarkSessionInstrumentedTest {
    @Test public void reportPlaybackSessionMembership() throws Exception {
        Assume.assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("run_quark_session_probe", "false")));
        HttpUrl url = QuarkNetwork.playEndpoint().newBuilder()
                .encodedPath("/1/clouddrive/member")
                .addQueryParameter("uc_param_str", "")
                .addQueryParameter("fetch_subscribe", "true")
                .addQueryParameter("_ch", "home")
                .addQueryParameter("fetch_identity", "true").build();
        PersistentCookieJar jar = PersistentCookieJar.get();
        for (String name : new String[]{"__pus", "__puus"}) {
            boolean stored = false;
            boolean sent = false;
            for (Cookie c : jar.loadForStorageDomain("quark.cn")) {
                if (c.name().equals(name)) stored = true;
            }
            for (Cookie c : jar.loadForRequest(url)) {
                if (c.name().equals(name)) sent = true;
            }
            report(name + " stored=" + stored + " sent=" + sent);
        }
        JsonObject response = new QuarkNetwork.NetworkTransport().get(url);
        assertTrue("Membership response missing data", response.has("data")
                && response.get("data").isJsonObject());
        JsonObject data = response.getAsJsonObject("data");
        String type = data.has("member_type") ? data.get("member_type").getAsString() : "missing";
        // Log only a small enum-like membership label, never account data or the response.
        assertTrue("Unexpected membership label", type.matches("[A-Za-z0-9_, -]{1,80}"));
        report("member_type=" + type);
    }
    @Test public void probeMiddleQualityRequests() throws Exception {
        Assume.assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("run_quark_quality_probe", "false")));
        QuarkNetwork.NetworkTransport transport = new QuarkNetwork.NetworkTransport();
        String folder = null;
        for (com.google.gson.JsonElement element : list(transport, "0")) {
            JsonObject item = element.getAsJsonObject();
            if (item.has("file_name") && "TVBoxCache".equals(item.get("file_name").getAsString())) {
                folder = item.get("fid").getAsString();
                break;
            }
        }
        assertTrue("No existing app cache folder", folder != null);
        String fid = null;
        for (com.google.gson.JsonElement element : list(transport, folder)) {
            JsonObject item = element.getAsJsonObject();
            if (item.has("file") && item.get("file").getAsBoolean()) {
                fid = item.get("fid").getAsString();
                break;
            }
        }
        assertTrue("No existing cached video", fid != null);
        for (String quality : new String[]{"super", "high", "normal", "super,high,normal,low"}) {
            JsonObject body = new JsonObject();
            body.addProperty("fid", fid);
            body.addProperty("resolutions", quality);
            if (!quality.contains(",")) body.addProperty("supports", "fmp4");
            JsonObject result;
            try {
                result = transport.postTranscode(QuarkNetwork.playEndpoint(), body);
            } catch (QuarkNetwork.QuarkHttpException error) {
                report("request=" + quality + " http=" + error.code()
                        + " apiCode=" + error.apiCode());
                continue;
            }
            assertTrue("Missing playback data", result.has("data"));
            for (com.google.gson.JsonElement element : result.getAsJsonObject("data").getAsJsonArray("video_list")) {
                JsonObject video = element.getAsJsonObject();
                String label = video.get("resolution").getAsString();
                assertTrue(label.matches("[A-Za-z0-9]{1,12}"));
                JsonObject info = video.has("video_info") ? video.getAsJsonObject("video_info") : new JsonObject();
                report("request=" + quality + " returned=" + label
                        + " accessible=" + (video.has("accessable") && video.get("accessable").getAsBoolean())
                        + " urlPresent=" + info.has("url"));
            }
        }
    }

    /** Exact OpenList request; kept out of production until middle-quality playback is proven. */
    @Test public void probePublishedProjectRequest() throws Exception {
        Assume.assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("run_quark_project_probe", "false")));
        QuarkNetwork.NetworkTransport transport = new QuarkNetwork.NetworkTransport();
        String fid = null;
        int folders = 0, entries = 0;
        for (com.google.gson.JsonElement e : list(transport, "0")) {
            JsonObject item = e.getAsJsonObject();
            if (!item.has("file_name") || !"TVBoxCache".equals(item.get("file_name").getAsString())) continue;
            folders++;
            for (com.google.gson.JsonElement child : list(transport, item.get("fid").getAsString())) {
                JsonObject video = child.getAsJsonObject();
                entries++;
                if (video.has("file") && video.get("file").getAsBoolean()) {
                    fid = video.get("fid").getAsString();
                    break;
                }
            }
            if (fid != null) break;
        }
        report("fixture foldersChecked=" + folders + " entries=" + entries);
        assertTrue("No existing cached video", fid != null);
        // Source: OpenListTeam/OpenList drivers/quark_uc/{meta,util}.go.
        JsonObject body = new JsonObject();
        body.addProperty("fid", fid);
        body.addProperty("resolutions", "low,normal,high,super,2k,4k");
        body.addProperty("supports", "fmp4_av,m3u8,dolby_vision");
        okhttp3.OkHttpClient client = QuarkNetwork.newNetworkClient();
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 "
                + "Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch";
        for (boolean published : new boolean[]{false, true}) {
            String profile = published ? "publishedProject" : "currentBaseline";
            body.addProperty("resolutions", published ? "low,normal,high,super,2k,4k" : "normal,low,high,super,2k,4k");
            body.addProperty("supports", published ? "fmp4_av,m3u8,dolby_vision" : "fmp4");
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(published ? "https://drive.quark.cn/1/clouddrive/file/v2/play/project?pr=ucpro&fr=pc"
                            : QuarkNetwork.playEndpoint().toString())
                    .header("User-Agent", ua).header("Referer", published ? "https://pan.quark.cn" : "https://pan.quark.cn/")
                    .header("Accept", "application/json, text/plain, */*")
                    .post(okhttp3.RequestBody.create(body.toString(), okhttp3.MediaType.parse("application/json"))).build();
            try (okhttp3.Response response = client.newCall(request).execute()) {
                JsonObject result = com.google.gson.JsonParser.parseString(response.body().string()).getAsJsonObject();
                report(profile + " http=" + response.code()
                        + " apiCode=" + (result.has("code") ? result.get("code").getAsInt() : -1));
                if (result.has("data") && result.get("data").isJsonObject()
                        && result.getAsJsonObject("data").has("video_list")) {
                    for (com.google.gson.JsonElement e : result.getAsJsonObject("data").getAsJsonArray("video_list")) {
                        JsonObject v = e.getAsJsonObject();
                        String label = v.get("resolution").getAsString();
                        assertTrue(label.matches("[A-Za-z0-9]{1,12}"));
                        JsonObject info = v.has("video_info") ? v.getAsJsonObject("video_info") : new JsonObject();
                        report(profile + " tier=" + label
                                + " accessible=" + (v.has("accessable") && v.get("accessable").getAsBoolean())
                                + " urlPresent=" + info.has("url")
                                + " width=" + (info.has("width") ? info.get("width").getAsInt() : 0)
                                + " height=" + (info.has("height") ? info.get("height").getAsInt() : 0));
                    }
                }
            }
        }
        // Inspect only public asset URLs from the first-party page; never export session data.
        try (okhttp3.Response response = client.newCall(new okhttp3.Request.Builder()
                .url("https://pan.quark.cn/list").header("User-Agent", ua).build()).execute()) {
            String html = response.body().string();
            java.util.regex.Matcher assets = java.util.regex.Pattern.compile(
                    "https://g\\.alicdn\\.com/[A-Za-z0-9_./-]+\\.js").matcher(html);
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (assets.find()) if (seen.add(assets.group()))
                report("officialAsset=" + assets.group());
            report("officialPage http=" + response.code() + " assetCount=" + seen.size());
        }
    }

    private void report(String message) {
        Log.i("QuarkSessionProbe", message);
        android.os.Bundle status = new android.os.Bundle();
        status.putString("stream", message + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }

    private com.google.gson.JsonArray list(QuarkNetwork.NetworkTransport transport,
                                          String parent) throws Exception {
        HttpUrl url = QuarkNetwork.playEndpoint().newBuilder()
                .encodedPath("/1/clouddrive/file/sort")
                .addQueryParameter("pdir_fid", parent)
                .addQueryParameter("_page", "1")
                .addQueryParameter("_size", "100")
                .addQueryParameter("_sort", "file_type:asc,updated_at:desc").build();
        return transport.get(url).getAsJsonObject("data").getAsJsonArray("list");
    }

}
