package com.wuming.tv.guardhost.webview;

import android.content.Context;
import android.webkit.SimpleWebResourceRequest;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WebViewShimTest {
    @After
    public void tearDown() {
        WebViewEngineFactory.reset();
    }

    @Test
    public void fallbackEngineSignalsEmulatorWhenNoRenderer() {
        WebViewEngineFactory.reset(); // default: JCEF absent on the test classpath -> fallback
        WebView view = new WebView(new Context());
        assertNotNull(view.getSettings());
        view.setWebViewClient(new WebViewClient());
        WebViewRequiresEmulatorException thrown = assertThrows(WebViewRequiresEmulatorException.class,
                () -> view.loadUrl("https://example.test/video"));
        assertTrue(thrown.getMessage().contains(WebViewRequiresEmulatorException.MARKER));
        assertTrue(WebViewRequiresEmulatorException.isEmulatorRequired(thrown));
    }

    @Test
    public void engineBridgeDrivesProviderWebViewClient() {
        final String[] captured = {null};
        WebViewEngineFactory.set(new WebViewEngine() {
            @Override
            public void loadUrl(String url, Map<String, String> extraHeaders, WebViewClientBridge bridge) {
                bridge.onPageStarted(url);
                WebResourceRequest request = new SimpleWebResourceRequest(url, Collections.emptyMap());
                if (bridge.shouldOverrideUrlLoading(request)) return;
                bridge.onPageFinished(url);
            }

            @Override
            public void destroy() {
            }
        });
        WebView view = new WebView(new Context());
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                captured[0] = url;
                return true; // provider captured the media url and stops navigation
            }
        });
        view.loadUrl("https://example.test/play");
        assertEquals("https://example.test/play", captured[0]);
    }
}
