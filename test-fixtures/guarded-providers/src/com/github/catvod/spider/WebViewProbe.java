package com.github.catvod.spider;

import android.content.Context;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.github.catvod.crawler.Spider;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Project-authored fixture for the guarded Android WebView bridge. */
public final class WebViewProbe extends Spider {
    private static final String PAGE = "data:text/html,%3Chtml%3E%3Ctitle%3EBridge%3C/title%3E%3C/html%3E";
    private Context context;
    private volatile String loadedUrl = "";

    @Override public void init(Context value) { context = value; }
    @Override public String homeContent(boolean filter) { return "{\"code\":1,\"type\":\"list\",\"list\":[]}"; }
    @Override public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        loadedUrl = "";
        final CountDownLatch done = new CountDownLatch(1);
        WebView view = new WebView(context);
        view.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (!PAGE.equals(url)) return;
                // A successful callback resolves a fixed test target; no media is downloaded.
                loadedUrl = "https://www.example.com/";
                done.countDown();
            }
        });
        // Keep the hardware bridge check independent of public DNS, TLS and site availability.
        view.loadUrl(PAGE);
        try {
            if (!done.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Device WebView callback timed out");
        }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw error; }
        finally { view.destroy(); }
        return "{\"url\":\"" + loadedUrl + "\"}";
    }
}
