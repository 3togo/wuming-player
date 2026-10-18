package android.webkit;

import android.net.Uri;

import java.util.Collections;
import java.util.Map;

/** Concrete WebResourceRequest the desktop engine hands to a provider's WebViewClient. */
public class SimpleWebResourceRequest implements WebResourceRequest {
    private final String url;
    private final Map<String, String> headers;

    public SimpleWebResourceRequest(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers == null ? Collections.emptyMap() : headers;
    }

    @Override
    public Uri getUrl() {
        return Uri.parse(url);
    }

    @Override
    public Map<String, String> getRequestHeaders() {
        return headers;
    }

    @Override
    public boolean isForMainFrame() {
        return true;
    }

    @Override
    public boolean hasGesture() {
        return false;
    }

    @Override
    public boolean isRedirect() {
        return false;
    }
}
