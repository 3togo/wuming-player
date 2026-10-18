package android.webkit;

import java.io.InputStream;
import java.util.Map;

/** Linkage-only WebResourceResponse returned from WebViewClient.shouldInterceptRequest. */
public class WebResourceResponse {
    private final int statusCode;
    private final String reasonPhrase;
    private final Map<String, String> responseHeaders;
    private String mimeType;
    private String encoding;
    private InputStream data;

    public WebResourceResponse(String mimeType, String encoding, InputStream data) {
        this(200, "OK", null, mimeType, encoding, data);
    }

    public WebResourceResponse(int statusCode, String reasonPhrase, Map<String, String> responseHeaders,
                               String mimeType, String encoding, InputStream data) {
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.responseHeaders = responseHeaders;
        this.mimeType = mimeType;
        this.encoding = encoding;
        this.data = data;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public InputStream getData() {
        return data;
    }

    public void setData(InputStream data) {
        this.data = data;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getReasonPhrase() {
        return reasonPhrase;
    }

    public Map<String, String> getResponseHeaders() {
        return responseHeaders;
    }
}
