package android.webkit;

import android.graphics.Bitmap;

/**
 * Base WebViewClient. Providers subclass this and override the callbacks; the desktop engine invokes
 * them through {@code WebView}'s {@code WebViewClientBridge} implementation.
 */
public class WebViewClient {
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return false;
    }

    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return shouldOverrideUrlLoading(view, request.getUrl() == null ? null : request.getUrl().toString());
    }

    public void onPageStarted(WebView view, String url, Bitmap favicon) {
    }

    public void onPageFinished(WebView view, String url) {
    }

    public void onLoadResource(WebView view, String url) {
    }

    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        return null;
    }

    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        return shouldInterceptRequest(view, request.getUrl() == null ? null : request.getUrl().toString());
    }

    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
    }

    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
    }
}
