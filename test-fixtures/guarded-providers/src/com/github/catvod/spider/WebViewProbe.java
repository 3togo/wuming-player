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
    private Context context;
    private volatile String loadedUrl = "";

    @Override public void init(Context value) { context = value; }
    @Override public String homeContent(boolean filter) { return "{\"code\":1,\"type\":\"list\",\"list\":[]}"; }
    @Override public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        WebView view = new WebView(context);
        view.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                loadedUrl = url;
                done.countDown();
            }
        });
        view.loadUrl("https://www.example.com/");
        try { done.await(15, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw error; }
        finally { view.destroy(); }
        return "{\"url\":\"" + loadedUrl + "\"}";
    }
}
