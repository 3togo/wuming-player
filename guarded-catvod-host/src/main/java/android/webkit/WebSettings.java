package android.webkit;

/** Linkage-only WebSettings. Field-backed so providers can configure and read them. */
public class WebSettings {
    private boolean javaScriptEnabled;
    private boolean javaScriptCanOpenWindowsAutomatically;
    private boolean domStorageEnabled;
    private boolean databaseEnabled;
    private boolean appCacheEnabled;
    private String userAgentString;
    private boolean loadWithOverviewMode;
    private boolean useWideViewPort;
    private int cacheMode = LOAD_DEFAULT;
    private boolean allowFileAccess = true;
    private boolean allowContentAccess = true;
    private boolean mediaPlaybackRequiresUserGesture;
    private boolean builtInZoomControls;
    private boolean displayZoomControls = true;
    private boolean supportZoom = true;
    private boolean loadsImagesAutomatically = true;
    private String defaultTextEncodingName = "UTF-8";
    private String appCachePath;

    public static final int LOAD_DEFAULT = 0;
    public static final int LOAD_NO_CACHE = 2;
    public static final int LOAD_CACHE_ONLY = 3;

    public boolean getJavaScriptEnabled() {
        return javaScriptEnabled;
    }

    public void setJavaScriptEnabled(boolean flag) {
        this.javaScriptEnabled = flag;
    }

    public boolean getJavaScriptCanOpenWindowsAutomatically() {
        return javaScriptCanOpenWindowsAutomatically;
    }

    public void setJavaScriptCanOpenWindowsAutomatically(boolean flag) {
        this.javaScriptCanOpenWindowsAutomatically = flag;
    }

    public boolean getDomStorageEnabled() {
        return domStorageEnabled;
    }

    public void setDomStorageEnabled(boolean flag) {
        this.domStorageEnabled = flag;
    }

    public boolean getDatabaseEnabled() {
        return databaseEnabled;
    }

    public void setDatabaseEnabled(boolean flag) {
        this.databaseEnabled = flag;
    }

    public boolean getAppCacheEnabled() {
        return appCacheEnabled;
    }

    public void setAppCacheEnabled(boolean flag) {
        this.appCacheEnabled = flag;
    }

    public String getAppCachePath() {
        return appCachePath;
    }

    public void setAppCachePath(String path) {
        this.appCachePath = path;
    }

    public String getUserAgentString() {
        return userAgentString;
    }

    public void setUserAgentString(String ua) {
        this.userAgentString = ua;
    }

    public boolean getLoadWithOverviewMode() {
        return loadWithOverviewMode;
    }

    public void setLoadWithOverviewMode(boolean flag) {
        this.loadWithOverviewMode = flag;
    }

    public boolean getUseWideViewPort() {
        return useWideViewPort;
    }

    public void setUseWideViewPort(boolean flag) {
        this.useWideViewPort = flag;
    }

    public int getCacheMode() {
        return cacheMode;
    }

    public void setCacheMode(int mode) {
        this.cacheMode = mode;
    }

    public boolean getAllowFileAccess() {
        return allowFileAccess;
    }

    public void setAllowFileAccess(boolean flag) {
        this.allowFileAccess = flag;
    }

    public boolean getAllowContentAccess() {
        return allowContentAccess;
    }

    public void setAllowContentAccess(boolean flag) {
        this.allowContentAccess = flag;
    }

    public boolean getMediaPlaybackRequiresUserGesture() {
        return mediaPlaybackRequiresUserGesture;
    }

    public void setMediaPlaybackRequiresUserGesture(boolean flag) {
        this.mediaPlaybackRequiresUserGesture = flag;
    }

    public boolean getBuiltInZoomControls() {
        return builtInZoomControls;
    }

    public void setBuiltInZoomControls(boolean flag) {
        this.builtInZoomControls = flag;
    }

    public boolean getDisplayZoomControls() {
        return displayZoomControls;
    }

    public void setDisplayZoomControls(boolean flag) {
        this.displayZoomControls = flag;
    }

    public boolean getSupportZoom() {
        return supportZoom;
    }

    public void setSupportZoom(boolean flag) {
        this.supportZoom = flag;
    }

    public boolean getLoadsImagesAutomatically() {
        return loadsImagesAutomatically;
    }

    public void setLoadsImagesAutomatically(boolean flag) {
        this.loadsImagesAutomatically = flag;
    }

    public String getDefaultTextEncodingName() {
        return defaultTextEncodingName;
    }

    public void setDefaultTextEncodingName(String encoding) {
        this.defaultTextEncodingName = encoding;
    }
}
