package android.webkit;

import android.net.Uri;

import java.util.Map;

/** Linkage-only WebResourceRequest interface used by WebViewClient callbacks. */
public interface WebResourceRequest {
    Uri getUrl();

    Map<String, String> getRequestHeaders();

    boolean isForMainFrame();

    boolean hasGesture();

    boolean isRedirect();
}
