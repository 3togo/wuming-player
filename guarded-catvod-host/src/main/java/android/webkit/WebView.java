package android.webkit;

import android.content.Context;

import com.wuming.tv.guardhost.webview.WebViewClientBridge;
import com.wuming.tv.guardhost.webview.WebViewEngine;
import com.wuming.tv.guardhost.webview.WebViewEngineFactory;

import java.util.Map;

/**
 * Desktop WebView. Mirrors the Android API surface the providers call and delegates rendering to a
 * {@link WebViewEngine} selected by {@link WebViewEngineFactory}. When no desktop renderer is
 * available the engine throws, signalling the caller to fall back to an Android emulator.
 */
public class WebView implements WebViewClientBridge {
    private final WebViewEngine engine;
    private WebViewClient client;
    private final WebSettings settings = new WebSettings();

    public WebView(Context context) {
        this.engine = WebViewEngineFactory.get();
    }

    public void setWebViewClient(WebViewClient client) {
        this.client = client;
    }

    public WebViewClient getWebViewClient() {
        return client;
    }

    public WebSettings getSettings() {
        return settings;
    }

    public void loadUrl(String url) {
        loadUrl(url, null);
    }

    public void loadUrl(String url, Map<String, String> extraHeaders) {
        engine.loadUrl(url, extraHeaders, this);
    }

    public void destroy() {
        engine.destroy();
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebResourceRequest request) {
        return client != null && client.shouldOverrideUrlLoading(this, request);
    }

    @Override
    public void onPageStarted(String url) {
        if (client != null) client.onPageStarted(this, url, null);
    }

    @Override
    public void onPageFinished(String url) {
        if (client != null) client.onPageFinished(this, url);
    }

    @Override
    public void onLoadResource(String url) {
        if (client != null) client.onLoadResource(this, url);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
        return client != null ? client.shouldInterceptRequest(this, request) : null;
    }
}
