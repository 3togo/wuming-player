package com.wuming.android.tv.utils;

import android.util.Log;

import com.wuming.android.tv.App;
import com.wuming.android.tv.bean.GitHubProxy;
import com.wuming.tv.core.source.GitHubProxyProbe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitHub proxy speed test utility
 * Tests proxies by downloading actual content and measuring performance. The probe algorithm and
 * outcome classification are shared with the desktop client via {@link GitHubProxyProbe}.
 */
public class GitHubProxySpeedTest {

    private static final String TAG = "GitHubProxySpeedTest";

    // Test configuration mirrors GitHubProxyProbe.PROBE_* so the two ports stay aligned.
    private static final int CONNECT_TIMEOUT = GitHubProxyProbe.PROBE_CONNECT_TIMEOUT_MS;
    private static final int READ_TIMEOUT = GitHubProxyProbe.PROBE_READ_TIMEOUT_MS;
    private static final int MIN_CONTENT_SIZE = GitHubProxyProbe.PROBE_MIN_CONTENT_BYTES;
    private static final int DOWNLOAD_LIMIT = GitHubProxyProbe.PROBE_DOWNLOAD_LIMIT;

    /**
     * Test result for a single proxy
     */
    public static class Result {
        public final GitHubProxy proxy;
        public final int index;
        public final int responseTime;  // milliseconds, -1 = failed
        public final int responseCode;
        public final int contentSize;
        public final String error;

        public Result(GitHubProxy proxy, int index, int responseTime, int responseCode, int contentSize, String error) {
            this.proxy = proxy;
            this.index = index;
            this.responseTime = responseTime;
            this.responseCode = responseCode;
            this.contentSize = contentSize;
            this.error = error;
        }

        public boolean isSuccess() {
            return responseTime >= 0 && contentSize >= MIN_CONTENT_SIZE;
        }

        public String getDisplayText() {
            if (isSuccess()) {
                return proxy.getName() + ": " + responseTime + "ms (" + formatSize(contentSize) + ")";
            } else if (error != null) {
                return proxy.getName() + ": FAIL (" + error + ")";
            } else {
                return proxy.getName() + ": FAIL (HTTP " + responseCode + ")";
            }
        }

        private String formatSize(int bytes) {
            if (bytes < 1024) return bytes + "B";
            return (bytes / 1024) + "KB";
        }
    }

    /**
     * Callback for test results
     */
    public interface Callback {
        void onTestComplete(List<Result> results, int bestIndex);
    }

    /**
     * Run speed test on all proxies
     */
    public static void runTest(List<GitHubProxy> proxies, String testUrl, Callback callback) {
        Task.execute(() -> {
            List<Result> results = new ArrayList<>();
            int bestIndex = -1;
            int bestTime = Integer.MAX_VALUE;

            for (int i = 0; i < proxies.size(); i++) {
                GitHubProxy proxy = proxies.get(i);
                Result result = testProxy(proxy, i, testUrl);
                results.add(result);

                if (result.isSuccess() && result.responseTime < bestTime) {
                    bestTime = result.responseTime;
                    bestIndex = i;
                }
            }

            final int finalBestIndex = bestIndex;
            App.post(() -> callback.onTestComplete(results, finalBestIndex));
        });
    }

    /**
     * Run speed test in parallel for faster results
     */
    public static void runTestParallel(List<GitHubProxy> proxies, String testUrl, Callback callback) {
        if (proxies == null || proxies.isEmpty()) {
            App.post(() -> callback.onTestComplete(new ArrayList<>(), -1));
            return;
        }
        Task.execute(() -> {
            int count = proxies.size();
            List<Result> results = new ArrayList<>(count);
            for (int i = 0; i < count; i++) results.add(null);

            CountDownLatch latch = new CountDownLatch(count);
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(count, 5));

            for (int i = 0; i < count; i++) {
                final int index = i;
                final GitHubProxy proxy = proxies.get(i);

                executor.submit(() -> {
                    try {
                        Result result = testProxy(proxy, index, testUrl);
                        results.set(index, result);
                    } catch (Exception e) {
                        results.set(index, new Result(proxy, index, -1, 0, 0, e.getMessage()));
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "Test interrupted", e);
            } finally {
                executor.shutdownNow();
            }

            // Find best
            int bestIndex = -1;
            int bestTime = Integer.MAX_VALUE;
            for (int i = 0; i < results.size(); i++) {
                Result r = results.get(i);
                if (r != null && r.isSuccess() && r.responseTime < bestTime) {
                    bestTime = r.responseTime;
                    bestIndex = i;
                }
            }

            final int finalBestIndex = bestIndex;
            final List<Result> finalResults = new ArrayList<>(results);
            App.post(() -> callback.onTestComplete(finalResults, finalBestIndex));
        });
    }

    /**
     * Test a single proxy
     */
    private static Result testProxy(GitHubProxy proxy, int index, String testUrl) {
        String proxyUrl = proxy.buildProxyUrl(testUrl);
        Log.d(TAG, "Testing proxy: " + proxy.getName() + " -> " + proxyUrl);

        GitHubProxyProbe.Result core = GitHubProxyProbe.probeOne(
                (url, withRange) -> httpUrlConnectionProbe(url, withRange), proxyUrl);
        return new Result(proxy, index, core.getElapsedMs(), core.getStatus(),
                core.getContentBytes(), core.getError());
    }

    /**
     * Performs one ranged/direct GET through {@link HttpURLConnection} for the shared probe.
     */
    private static GitHubProxyProbe.Outcome httpUrlConnectionProbe(String url, boolean withRange)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        if (withRange) {
            conn.setRequestProperty("Range", "bytes=0-" + (DOWNLOAD_LIMIT - 1));
        }

        int code = conn.getResponseCode();
        int bytes = 0;
        if (code == 200 || code == 206) {
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int totalRead = 0;
                int read;
                while (totalRead < DOWNLOAD_LIMIT && (read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    totalRead += read;
                }
                bytes = totalRead;
            }
        }
        conn.disconnect();
        return new GitHubProxyProbe.Outcome(code, bytes);
    }
}
