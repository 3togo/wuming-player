package com.wuming.android.tv.update;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.App;
import com.wuming.android.tv.BuildConfig;
import com.wuming.android.tv.utils.Download;
import com.wuming.android.tv.utils.Github;
import com.wuming.android.tv.utils.UpdateClient;
import com.wuming.android.tv.utils.UpdateDownload;
import com.wuming.tv.core.design.update.UpdateArtifact;
import com.wuming.tv.core.design.update.UpdateManifest;
import com.github.catvod.utils.Prefers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;

/** Fault injection uses the actual signed next-version APK; no installer or app data reset. */
@RunWith(AndroidJUnit4.class)
public class UpdateMirrorsInstrumentedTest {
    private String previousHost;
    private String manifest;
    private File fixture;
    private File destination;
    private UpdateDownload download;

    @Before public void setup() throws Exception {
        previousHost = Prefers.getString("update_mirror_host");
        manifest = new String(Files.readAllBytes(new File(App.get().getCacheDir(), "mirror-test-feed.json").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        fixture = new File(App.get().getCacheDir(), "mirror-test-fixture.apk");
        destination = new File(App.get().getCacheDir(), "mirror-test-result.apk");
        assertTrue("Push the published next-version APK fixture before running", fixture.isFile());
    }

    @After public void cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { if (download != null) download.cancel(); });
        destination.delete();
        Prefers.put("update_mirror_host", previousHost);
    }

    private int installedCode() {
        try { return App.get().getPackageManager().getPackageInfo(App.get().getPackageName(), 0).versionCode; }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private UpdateArtifact artifact(boolean giteeFirst) {
        UpdateArtifact selected = UpdateManifest.select(manifest, BuildConfig.UPDATE_REPOSITORY, "debug", "mobile", "arm64_v8a", "com.wuming.android.tv", installedCode());
        assertNotNull(selected);
        List<String> urls = new ArrayList<>(selected.getUrls());
        urls.sort((a, b) -> Boolean.compare(a.contains("gitee.com") != giteeFirst, b.contains("gitee.com") != giteeFirst));
        assertEquals(2, urls.size());
        return new UpdateArtifact(selected.getCode(), selected.getName(), selected.getDescription(), urls.get(0), selected.getSha256(), selected.getCertificateSha256(), selected.getPackageName(), urls);
    }

    private Response response(Request request, ResponseBody body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build();
    }

    private ResponseBody apkBody() throws IOException {
        final BufferedSource source = Okio.buffer(Okio.source(fixture));
        return new ResponseBody() {
            @Override public MediaType contentType() { return MediaType.parse("application/vnd.android.package-archive"); }
            @Override public long contentLength() { return fixture.length(); }
            @Override public BufferedSource source() { return source; }
        };
    }

    private UpdateArtifact check(OkHttpClient http) throws Exception {
        return UpdateClient.check(Github.getUpdateFeeds(), BuildConfig.UPDATE_REPOSITORY, "debug", "mobile", "arm64_v8a", "com.wuming.android.tv", installedCode(), http);
    }

    @Test(timeout = 30000) public void eitherFeedCanFailAndTheOtherStillOffersUpdate() throws Exception {
        for (String failed : Arrays.asList("gitee.com", "raw.githubusercontent.com")) {
            OkHttpClient http = UpdateClient.feedHttp.newBuilder().addInterceptor(chain -> {
                if (chain.request().url().host().equals(failed)) throw new IOException("Injected feed outage");
                return response(chain.request(), ResponseBody.create(MediaType.parse("application/json"), manifest));
            }).build();
            assertEquals(artifact(true).getCode(), check(http).getCode());
        }
    }

    @Test(timeout = 30000) public void allFeedFailuresAreErrorsNotUpToDate() throws Exception {
        OkHttpClient http = UpdateClient.feedHttp.newBuilder().addInterceptor(chain -> { throw new IOException("Injected offline"); }).build();
        try { check(http); fail("Must fail when neither feed is available"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("All update feeds")); }
    }

    private void transfer(boolean giteeFirst, boolean corrupt) throws Exception {
        transfer(giteeFirst, corrupt, false);
    }

    private void transfer(boolean giteeFirst, boolean corrupt, boolean publicDownload) throws Exception {
        UpdateArtifact selected = artifact(giteeFirst);
        List<String> attempts = Collections.synchronizedList(new ArrayList<>());
        OkHttpClient http = UpdateClient.apkHttp.newBuilder().callTimeout(90, TimeUnit.SECONDS).addInterceptor(chain -> {
            attempts.add(chain.request().url().host());
            if (attempts.size() == 1) {
                if (!corrupt) throw new IOException("Injected primary outage");
                return response(chain.request(), ResponseBody.create(MediaType.parse("application/octet-stream"), "corrupt bytes"));
            }
            return publicDownload ? chain.proceed(chain.request()) : response(chain.request(), apkBody());
        }).build();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicInteger successes = new AtomicInteger();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            download = new UpdateDownload(selected, destination, http);
            download.start(new Download.Callback() {
                @Override public void progress(int value) {}
                @Override public void error(String message) { failure.set(message); done.countDown(); }
                @Override public void success(File result) { successes.incrementAndGet(); done.countDown(); }
            });
        });
        assertTrue("Transfer timed out", done.await(publicDownload ? 100 : 30, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertEquals(1, successes.get());
        assertEquals(giteeFirst ? Arrays.asList("gitee.com", "github.com") : Arrays.asList("github.com", "gitee.com"), attempts);
        assertEquals(attempts.get(1), Prefers.getString("update_mirror_host"));
        assertEquals(fixture.length(), destination.length());
    }

    @Test(timeout = 45000) public void giteeDownloadFailureFallsBackToGithub() throws Exception { transfer(true, false); }
    @Test(timeout = 45000) public void githubDownloadFailureFallsBackToGitee() throws Exception { transfer(false, false); }
    @Test(timeout = 45000) public void corruptPrimaryFallsBackWithoutSkippingApkVerification() throws Exception { transfer(true, true); }

    @Test(timeout = 110000) public void realGithubDownloadAfterGiteeFailure() throws Exception { transfer(true, false, true); }
    @Test(timeout = 110000) public void realGiteeDownloadAfterGithubFailure() throws Exception { transfer(false, false, true); }

    @Test(timeout = 30000) public void cancelStopsFallbackAndSuppressesDelivery() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OkHttpClient http = UpdateClient.apkHttp.newBuilder().addInterceptor(chain -> {
            attempts.incrementAndGet(); entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); throw new IOException("Injected error after cancel"); }
            catch (InterruptedException error) { throw new IOException(error); }
            finally { stopped.countDown(); }
        }).build();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            download = new UpdateDownload(artifact(true), destination, http);
            download.start(new Download.Callback() {
                @Override public void progress(int value) {}
                @Override public void error(String message) { callbacks.incrementAndGet(); }
                @Override public void success(File result) { callbacks.incrementAndGet(); }
            });
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(download::cancel);
        release.countDown();
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(1, attempts.get());
        assertEquals(0, callbacks.get());
        assertFalse(destination.exists());
    }

    @Test(timeout = 20000) public void stalledFeedCannotHoldTheSuccessfulPeerIndefinitely() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        OkHttpClient http = UpdateClient.feedHttp.newBuilder().addInterceptor(chain -> {
            if (chain.request().url().host().equals("gitee.com")) {
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { throw new IOException("Canceled stalled feed", expected); }
                finally { stopped.countDown(); }
            }
            return response(chain.request(), ResponseBody.create(MediaType.parse("application/json"), manifest));
        }).build();
        long start = System.nanoTime();
        assertNotNull(check(http));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 12);
        assertTrue("Stalled worker must be canceled", stopped.await(2, TimeUnit.SECONDS));
    }

    @Test(timeout = 20000) public void livePublishedFeedsOfferBothMirrors() throws Exception {
        UpdateArtifact selected = check(UpdateClient.feedHttp);
        assertNotNull(selected);
        assertEquals(artifact(true).getCode(), selected.getCode());
        assertEquals(2, selected.getUrls().size());
    }
}
