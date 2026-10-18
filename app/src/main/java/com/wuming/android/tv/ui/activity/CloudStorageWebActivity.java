package com.wuming.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.cloud.CloudStorageProvider;
import com.wuming.android.tv.utils.UrlUtil;
import com.wuming.tv.core.cloud.OpenDriveAccountSnapshot;
import com.wuming.android.tv.cloud.opendrive.OpenDriveTokenManager;
import com.wuming.tv.core.cloud.OpenDriveTokenState;
import com.wuming.tv.core.cloud.OpenDriveLoginChallenge;
import com.wuming.tv.core.cloud.OpenDriveLoginState;
import com.wuming.android.tv.cloud.opendrive.OpenDriveTokenStore;
import com.wuming.android.tv.databinding.ActivityCloudStorageWebBinding;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.OpenDriveQrHandoff;
import com.wuming.android.tv.utils.ProviderAppLauncher;
import com.wuming.android.tv.utils.QRCode;
import com.wuming.android.tv.utils.QrSizing;
import com.wuming.android.tv.utils.Task;
import com.github.catvod.net.PersistentCookieJar;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Future;

public class CloudStorageWebActivity extends BaseActivity {

    private static final String EXTRA_PROVIDER = "provider";
    private static final String EXTRA_ASSISTED_LOGIN = "assisted_login";
    private static final String EXTRA_MANAGEMENT_ONLY = "management_only";
    private static final String QUARK_LOGIN_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String FIT_WIDE_LOGIN_PAGE_SCRIPT =
            "(function(){"
                    + "var root=document.documentElement,body=document.body;"
                    + "if(!root||!body)return;"
                    + "var nodes=document.querySelectorAll('body *'),marker=null,markerArea=Infinity;"
                    + "for(var i=0;i<nodes.length;i++){"
                    + "var text=(nodes[i].textContent||'').trim();"
                    + "if(text.indexOf('\u626b\u7801\u767b\u5f55')<0)continue;"
                    + "var candidate=nodes[i].getBoundingClientRect(),area=candidate.width*candidate.height;"
                    + "if(area>0&&area<markerArea){marker=nodes[i];markerArea=area;}"
                    + "}"
                    + "root.style.setProperty('zoom','1','important');"
                    + "var viewport=Math.max(root.clientWidth,window.innerWidth||0);"
                    + "var content=Math.max(root.scrollWidth,body.scrollWidth,viewport);"
                    + "var scale=Math.min(1,viewport/content);"
                    + "if(scale<0.98)root.style.setProperty('zoom',String(Math.max(0.62,scale)),'important');"
                    + "if(marker){"
                    + "var target=marker;"
                    + "for(var j=0;j<6&&target.parentElement;j++){"
                    + "var parent=target.parentElement,rect=parent.getBoundingClientRect();"
                    + "target=parent;if(rect.width>=180&&rect.height>=220)break;"
                    + "}"
                    + "var box=target.getBoundingClientRect(),visible=window.innerWidth||root.clientWidth;"
                    + "if(box.left>=0&&box.right<=visible)return;"
                    + "}"
                    + "var scrollers=document.querySelectorAll('html,body,body *');"
                    + "for(var k=0;k<scrollers.length;k++){"
                    + "var overflow=scrollers[k].scrollWidth-scrollers[k].clientWidth;"
                    + "if(overflow>8)scrollers[k].scrollLeft=overflow;"
                    + "}"
                    + "})();";
    private ActivityCloudStorageWebBinding binding;
    private CloudStorageProvider provider;
    private OpenDriveQrHandoff qrHandoff;
    private boolean fitWideLogin;
    private boolean assistedLoginPending;
    private boolean loginConfirmed;
    private boolean quarkLoginProfile;
    private boolean providerHandoffActive;
    private boolean resettingLogin;
    private boolean resumed;
    private boolean directQrLogin;
    private boolean managementOnly;
    private OpenDriveTokenManager tokens;
    private boolean tokenVerificationBusy;
    private Future<?> ucLoginTask;
    private String ucLoginSessionId;
    private String ucLoginContent;
    private boolean showingUcChallenge;
    private boolean ucAutoOpenPending;
    private boolean ucPollBusy;
    private int ucPollFailures;
    private int ucLoginGeneration;
    private final Runnable refreshLoginState = this::refreshLoginState;
    private final Runnable pollUcLoginState = this::pollUcLoginState;
    // Named so they can be pulled off the WebView queue on teardown. Anonymous lambdas
    // would keep this activity reachable from the WebView's Handler until they fire.
    private final Runnable resetLoginRunnable = this::completeResetLogin;
    private final Runnable fitLoginPageRunnable = this::fitLoginPage;
    private final Runnable autoLoginRunnable = this::autoLogin;

    public static void start(Activity activity, CloudStorageProvider provider) {
        start(activity, provider, false);
    }

    public static void start(Activity activity, CloudStorageProvider provider,
                             boolean assistedLogin) {
        Intent intent = new Intent(activity, CloudStorageWebActivity.class);
        intent.putExtra(EXTRA_PROVIDER, provider.getId());
        intent.putExtra(EXTRA_ASSISTED_LOGIN, assistedLogin);
        activity.startActivity(intent);
    }

    public static void startManagement(Activity activity, CloudStorageProvider provider) {
        Intent intent = new Intent(activity, CloudStorageWebActivity.class);
        intent.putExtra(EXTRA_PROVIDER, provider.getId());
        intent.putExtra(EXTRA_MANAGEMENT_ONLY, true);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityCloudStorageWebBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void initView(Bundle savedInstanceState) {
        String providerId = getIntent().getStringExtra(EXTRA_PROVIDER);
        provider = CloudStorageProvider.fromId(providerId != null ? providerId : "");
        managementOnly = getIntent().getBooleanExtra(EXTRA_MANAGEMENT_ONLY, false);
        tokens = OpenDriveTokenManager.get();
        OpenDriveAccountSnapshot account = accountSnapshot();
        try {
            directQrLogin = account.supportsQrLogin();
        } catch (IllegalArgumentException ignored) {
            directQrLogin = false;
        }
        OpenDriveQrHandoff.cleanupExpired(this);
        loginConfirmed = account.verified();
        assistedLoginPending = savedInstanceState == null
                && getIntent().getBooleanExtra(EXTRA_ASSISTED_LOGIN, false)
                && account.canStartLogin();
        fitWideLogin = usesWideLoginPage() && !account.tokenPresent();
        binding.title.setText(provider.getDisplayName());
        binding.back.setOnClickListener(view -> finish());
        binding.reset.setOnClickListener(view -> resetLogin());
        binding.external.setOnClickListener(view -> openExternal());
        binding.reset.setVisibility(managementOnly ? View.GONE : View.VISIBLE);
        boolean supportsQrHandoff = directQrLogin && OpenDriveQrHandoff.supports(provider.getId());
        binding.auto.setVisibility(!managementOnly && supportsQrHandoff && account.canStartLogin()
                ? View.VISIBLE : View.GONE);
        if (supportsQrHandoff) {
            qrHandoff = new OpenDriveQrHandoff(this, binding.web, provider.getId(),
                    () -> providerHandoffActive = true);
            binding.auto.setOnClickListener(view -> autoLogin());
        }

        WebSettings settings = binding.web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        quarkLoginProfile = usesQuarkLoginProfile(provider, account.tokenPresent())
                || usesQuarkManagementProfile(provider, managementOnly);
        applyBrowserProfile(settings);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(binding.web, true);
        PersistentCookieJar.get().syncToWebView(provider.getManagementUrl());
        binding.web.setWebViewClient(new StorageWebViewClient());
        renderTokenState(account);
        if (!managementOnly && directQrLogin && account.canStartLogin()) {
            startUcLogin(assistedLoginPending);
            assistedLoginPending = false;
        } else {
            binding.web.loadUrl(provider.getManagementUrl());
            if (!managementOnly && account.tokenPresent() && !account.verified()) {
                verifyTokensAsync();
            }
        }
    }

    private void autoLogin() {
        if (directQrLogin) {
            if (ucLoginContent == null) startUcLogin(true);
            else qrHandoff.start();
        } else if (qrHandoff != null) {
            qrHandoff.start();
        }
    }

    private void openExternal() {
        ProviderAppLauncher.open(this, provider);
    }

    private void resetLogin() {
        if (binding == null || provider == null || resettingLogin) return;
        resettingLogin = true;
        providerHandoffActive = false;
        loginConfirmed = false;
        fitWideLogin = usesWideLoginPage();
        quarkLoginProfile = usesQuarkLoginProfile(provider, false);
        applyBrowserProfile(binding.web.getSettings());
        binding.reset.setEnabled(false);
        binding.web.removeCallbacks(refreshLoginState);
        binding.web.removeCallbacks(resetLoginRunnable);
        if (qrHandoff != null) qrHandoff.complete();
        binding.web.stopLoading();
        binding.web.loadUrl("about:blank");

        clearProviderSession();
        updateAutoVisibility(accountSnapshot());
        Notify.show(getString(R.string.cloud_storage_login_reset, provider.getDisplayName()));
        binding.web.postDelayed(resetLoginRunnable, 250);
    }

    private void completeResetLogin() {
        if (binding == null || isFinishing() || isDestroyed()) return;
        resettingLogin = false;
        binding.reset.setEnabled(true);
        binding.web.clearHistory();
        if (directQrLogin) startUcLogin(false);
        else binding.web.loadUrl(provider.getManagementUrl());
    }

    private void clearProviderSession() {
        cancelUcLogin();
        try {
            tokens.reset(provider.getId());
        } catch (Exception ignored) {
            // 115 has no built-in provider adapter yet. Its first-party page
            // still needs the same origin-scoped WebView reset below.
        }
        PersistentCookieJar jar = PersistentCookieJar.get();
        String domain = providerCookieDomain();
        if (domain != null) jar.clear(domain);
        jar.clearWebView(provider.getManagementUrl());
        WebStorage.getInstance().deleteOrigin(origin(provider.getManagementUrl()));
        if (provider == CloudStorageProvider.UC) {
            jar.clearWebView("https://broccoli.uc.cn/");
            WebStorage.getInstance().deleteOrigin("https://broccoli.uc.cn");
        }
        CookieManager.getInstance().flush();
    }

    private String providerCookieDomain() {
        return switch (provider) {
            case ALIPAN -> "alipan.com";
            case QUARK -> "quark.cn";
            case UC -> "uc.cn";
            case BAIDU -> "baidu.com";
            case PAN123 -> "123pan.com";
            case TIANYI -> "189.cn";
            case DRIVE_115 -> "115.com";
        };
    }

    private static String origin(String url) {
        Uri uri = Uri.parse(url);
        return uri.getScheme() + "://" + uri.getHost();
    }

    private boolean openCustomScheme(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        } catch (ActivityNotFoundException error) {
            Notify.show(R.string.cloud_storage_browser_missing);
            return true;
        }
    }

    @Override
    protected void onBackInvoked() {
        if (com.wuming.android.tv.utils.Util.isLeanback() && binding != null && binding.web.hasFocus()) {
            binding.back.requestFocus();
            return;
        }
        if (managementOnly) finish();
        else if (binding != null && binding.web.canGoBack()) binding.web.goBack();
        else finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding == null) return;
        resumed = true;
        binding.web.onResume();
        if (showingUcChallenge && ucLoginSessionId != null) {
            binding.web.removeCallbacks(pollUcLoginState);
            binding.web.post(pollUcLoginState);
            return;
        }
        binding.web.removeCallbacks(refreshLoginState);
        binding.web.postDelayed(refreshLoginState, 700);
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (binding != null) {
            if (!keepQuarkLoginAlive()) binding.web.removeCallbacks(refreshLoginState);
            if (!showingUcChallenge) binding.web.removeCallbacks(pollUcLoginState);
            if (!managementOnly && !resettingLogin) {
                syncCookies(binding.web.getUrl());
                updateLoginState();
            }
            if (!keepQuarkLoginAlive()) binding.web.onPause();
        }
        super.onPause();
    }

    private void refreshLoginState() {
        if (managementOnly || binding == null || provider == null || resettingLogin || showingUcChallenge
                || isFinishing() || isDestroyed()) return;
        syncCookies(binding.web.getUrl());
        boolean authenticated = updateLoginState();
        // QR confirmation is commonly completed by an XHR without a page
        // navigation, so onPageFinished alone is not a reliable signal.
        if (!authenticated && (resumed || keepQuarkLoginAlive())) {
            binding.web.postDelayed(refreshLoginState, 1500);
        }
    }

    private boolean updateLoginState() {
        OpenDriveAccountSnapshot account = accountSnapshot();
        renderTokenState(account);
        updateAutoVisibility(account);
        if (account.tokenPresent() && !account.verified()
                && account.state() != OpenDriveTokenState.CHECKING) {
            verifyTokensAsync();
        }
        if (!account.verified()) return false;
        if (qrHandoff != null) qrHandoff.complete();
        boolean switchQuarkToMobile = quarkLoginProfile;
        if (switchQuarkToMobile) {
            providerHandoffActive = false;
            quarkLoginProfile = false;
            applyBrowserProfile(binding.web.getSettings());
        }
        if (!loginConfirmed) {
            loginConfirmed = true;
            Notify.show(getString(R.string.cloud_storage_login_confirmed,
                    provider.getDisplayName()));
            // A detail page loaded before authentication retains its legacy
            // provider payload. Reload it now so SiteApi can route the episode
            // through the newly authenticated built-in OpenDrive adapter.
            RefreshEvent.detail();
        }
        if (switchQuarkToMobile) {
            // Quark redirects mobile browsers away from its QR login page. Once the
            // desktop-only QR handshake has supplied real cookies, return to the
            // normal mobile profile for file management.
            binding.web.clearHistory();
            binding.web.loadUrl(provider.getManagementUrl());
            returnToOwnTask();
        }
        return true;
    }

    private void updateAutoVisibility(OpenDriveAccountSnapshot account) {
        if (binding == null) return;
        binding.auto.setVisibility(qrHandoff != null && account.canStartLogin()
                ? View.VISIBLE : View.GONE);
    }

    private void renderTokenState(OpenDriveAccountSnapshot account) {
        if (binding == null) return;
        int status = switch (account.state()) {
            case UNAVAILABLE -> R.string.open_drive_status_unavailable;
            case SIGNED_OUT -> R.string.open_drive_status_not_logged_in;
            case PRESENT, CHECKING -> R.string.open_drive_status_checking;
            case VERIFIED -> R.string.open_drive_status_logged_in;
            case INVALID -> R.string.open_drive_status_invalid;
            case ERROR -> R.string.open_drive_status_verify_failed;
        };
        binding.credentialStatus.setText(status);
        int color = switch (account.state()) {
            case VERIFIED -> R.color.cloud_login_connected;
            case SIGNED_OUT, INVALID, ERROR -> R.color.cloud_login_disconnected;
            case PRESENT, CHECKING -> R.color.yellow_500;
            case UNAVAILABLE -> R.color.grey_500;
        };
        binding.credentialStatus.setTextColor(getColor(color));
    }

    private void verifyTokensAsync() {
        if (binding == null || tokens == null || tokenVerificationBusy
                || provider == CloudStorageProvider.DRIVE_115) return;
        tokenVerificationBusy = true;
        OpenDriveTokenManager manager = tokens;
        renderTokenState(new OpenDriveAccountSnapshot(provider.getId(),
                provider.getDisplayName(), true, directQrLogin, true,
                OpenDriveTokenState.CHECKING));
        Task.submitLarge(() -> {
            OpenDriveAccountSnapshot result = manager.verify(provider.getId());
            runOnUiThread(() -> {
                if (binding == null || isFinishing() || isDestroyed()) return;
                tokenVerificationBusy = false;
                renderTokenState(result);
                updateAutoVisibility(result);
                if (result.verified()) {
                    updateLoginState();
                } else if (result.state() == OpenDriveTokenState.INVALID
                        && directQrLogin && !showingUcChallenge) {
                    startUcLogin(false);
                }
            });
        });
    }

    private void applyBrowserProfile(WebSettings settings) {
        settings.setUserAgentString(quarkLoginProfile
                ? QUARK_LOGIN_USER_AGENT : WebSettings.getDefaultUserAgent(this));
        settings.setUseWideViewPort(quarkLoginProfile);
        settings.setLoadWithOverviewMode(quarkLoginProfile);
    }

    static boolean usesQuarkLoginProfile(CloudStorageProvider provider,
                                          boolean authenticated) {
        return provider == CloudStorageProvider.QUARK && !authenticated;
    }

    static boolean usesQuarkManagementProfile(CloudStorageProvider provider,
                                               boolean managementOnly) {
        return provider == CloudStorageProvider.QUARK && managementOnly;
    }

    private boolean keepQuarkLoginAlive() {
        return provider == CloudStorageProvider.QUARK && quarkLoginProfile
                && providerHandoffActive;
    }

    @Override
    protected void onDestroy() {
        resumed = false;
        cancelUcLogin();
        if (qrHandoff != null) {
            qrHandoff.destroy();
            qrHandoff = null;
        }
        if (binding != null) {
            binding.web.removeCallbacks(refreshLoginState);
            binding.web.removeCallbacks(pollUcLoginState);
            binding.web.removeCallbacks(resetLoginRunnable);
            binding.web.removeCallbacks(fitLoginPageRunnable);
            binding.web.removeCallbacks(autoLoginRunnable);
            if (!managementOnly && !resettingLogin) syncCookies(binding.web.getUrl());
            binding.web.stopLoading();
            binding.web.setWebViewClient(null);
            binding.web.destroy();
            binding = null;
        }
        super.onDestroy();
    }

    private void syncCookies(String url) {
        PersistentCookieJar.get().syncFromWebView(this,
                url == null || url.isBlank() ? provider.getManagementUrl() : url);
        captureProviderToken(url);
        if (url != null && !url.equals(provider.getManagementUrl())) {
            PersistentCookieJar.get().syncFromWebView(this, provider.getManagementUrl());
        }
    }

    private void captureProviderToken(String url) {
        if (provider == CloudStorageProvider.ALIPAN) {
            OpenDriveTokenStore.capture(OpenDriveTokenStore.CloudTokenProvider.ALIPAN,
                    binding.web, url);
        } else if (provider == CloudStorageProvider.PAN123) {
            OpenDriveTokenStore.capture(OpenDriveTokenStore.CloudTokenProvider.PAN123,
                    binding.web, url);
        }
    }

    private void fitProviderContent(WebView view, String url) {
        if (!fitWideLogin) return;
        if (accountSnapshot().tokenPresent()) {
            fitWideLogin = false;
            return;
        }
        String host = url == null ? null : Uri.parse(url).getHost();
        if (!isProviderHost(host)) return;
        fitLoginPage();
        binding.web.postDelayed(fitLoginPageRunnable, 400);
        binding.web.postDelayed(fitLoginPageRunnable, 1200);
    }

    private boolean usesWideLoginPage() {
        return provider == CloudStorageProvider.TIANYI
                || provider == CloudStorageProvider.DRIVE_115;
    }

    private boolean isProviderHost(String host) {
        if (host == null) return false;
        if (provider == CloudStorageProvider.TIANYI) {
            return host.equals("189.cn") || host.endsWith(".189.cn");
        }
        return provider == CloudStorageProvider.DRIVE_115
                && (host.equals("115.com") || host.endsWith(".115.com"));
    }

    private boolean hasProviderLoginCookies() {
        return accountSnapshot().tokenPresent();
    }

    private OpenDriveAccountSnapshot accountSnapshot() {
        if (provider != CloudStorageProvider.DRIVE_115) {
            if (tokens == null) tokens = OpenDriveTokenManager.get();
            return tokens.snapshot(provider.getId());
        }
        boolean present;
        try {
            present = PersistentCookieJar.get().hasLoginCookies(provider.getManagementUrl());
        } catch (IllegalStateException ignored) {
            present = false;
        }
        return new OpenDriveAccountSnapshot(provider.getId(), provider.getDisplayName(), true,
                false, present, present ? OpenDriveTokenState.ERROR
                : OpenDriveTokenState.SIGNED_OUT);
    }

    private void fitLoginPage() {
        if (binding == null) return;
        binding.web.evaluateJavascript(FIT_WIDE_LOGIN_PAGE_SCRIPT, null);
    }

    private void startUcLogin(boolean autoOpen) {
        if (binding == null || !directQrLogin || resettingLogin
                || isFinishing() || isDestroyed()) return;
        cancelUcLogin();
        showingUcChallenge = true;
        ucAutoOpenPending = autoOpen;
        ucPollFailures = 0;
        int generation = ++ucLoginGeneration;
        binding.web.removeCallbacks(refreshLoginState);
        binding.web.stopLoading();
        showUcLoginPage(null, getString(R.string.cloud_provider_qr_connecting,
                provider.getDisplayName()));
        Notify.show(getString(R.string.cloud_provider_qr_connecting,
                provider.getDisplayName()));
        ucLoginTask = Task.submitLarge(() -> {
            try {
                OpenDriveLoginChallenge challenge = tokens.beginLogin(provider.getId());
                String encodedQr = encodeQr(challenge.content());
                binding.web.post(() -> onUcChallengeReady(generation, challenge, encodedQr));
            } catch (Exception error) {
                postUcLoginFailure(generation, R.string.cloud_provider_qr_failed);
            }
        });
    }

    private void onUcChallengeReady(int generation, OpenDriveLoginChallenge challenge,
                                    String encodedQr) {
        if (!ucLoginAlive(generation)) return;
        if (encodedQr == null) {
            onUcLoginStopped(R.string.cloud_provider_qr_failed);
            return;
        }
        ucLoginSessionId = challenge.id();
        ucLoginContent = challenge.content();
        showUcLoginPage(encodedQr, getString(R.string.cloud_provider_qr_instruction,
                provider.getDisplayName()));
        binding.web.removeCallbacks(pollUcLoginState);
        binding.web.postDelayed(pollUcLoginState, 900);
    }

    private void pollUcLoginState() {
        if (binding == null || !showingUcChallenge
                || ucLoginSessionId == null || ucPollBusy) return;
        ucPollBusy = true;
        int generation = ucLoginGeneration;
        String sessionId = ucLoginSessionId;
        ucLoginTask = Task.submitLarge(() -> {
            try {
                OpenDriveLoginState state = tokens.pollLogin(provider.getId(), sessionId);
                binding.web.post(() -> onUcLoginState(generation, state));
            } catch (Exception error) {
                binding.web.post(() -> onUcPollFailure(generation));
            }
        });
    }

    private void onUcLoginState(int generation, OpenDriveLoginState state) {
        if (!ucLoginAlive(generation)) return;
        ucPollBusy = false;
        ucPollFailures = 0;
        if (state == OpenDriveLoginState.WAITING || state == OpenDriveLoginState.SCANNED) {
            binding.web.postDelayed(pollUcLoginState, 1500);
            return;
        }
        if (state == OpenDriveLoginState.CONFIRMED) {
            providerHandoffActive = false;
            showingUcChallenge = false;
            ucLoginSessionId = null;
            ucLoginContent = null;
            ucAutoOpenPending = false;
            if (qrHandoff != null) qrHandoff.complete();
            PersistentCookieJar.get().syncToWebView(provider.getManagementUrl());
            loginConfirmed = false;
            updateLoginState();
            returnToOwnTask();
            finish();
            return;
        }
        onUcLoginStopped(state == OpenDriveLoginState.EXPIRED
                ? R.string.cloud_provider_qr_expired : R.string.cloud_provider_qr_failed);
    }

    private void onUcPollFailure(int generation) {
        if (!ucLoginAlive(generation)) return;
        ucPollBusy = false;
        if (++ucPollFailures < 3) {
            binding.web.postDelayed(pollUcLoginState, 1800);
        } else {
            onUcLoginStopped(R.string.cloud_provider_qr_failed);
        }
    }

    private void returnToOwnTask() {
        try {
            ActivityManager manager = getSystemService(ActivityManager.class);
            if (manager != null) {
                manager.moveTaskToFront(getTaskId(), 0);
            }
        } catch (SecurityException ignored) {
            // The confirmation remains valid; system Back can still resume TVBox.
        }
    }

    private void onUcLoginStopped(int message) {
        if (binding == null) return;
        ucLoginSessionId = null;
        ucLoginContent = null;
        ucAutoOpenPending = false;
        ucPollBusy = false;
        Notify.show(getString(message, provider.getDisplayName()));
    }

    private void postUcLoginFailure(int generation, int message) {
        if (binding == null) return;
        binding.web.post(() -> {
            if (ucLoginAlive(generation)) onUcLoginStopped(message);
        });
    }

    private boolean ucLoginAlive(int generation) {
        return binding != null && generation == ucLoginGeneration && showingUcChallenge
                && !isFinishing() && !isDestroyed();
    }

    private void cancelUcLogin() {
        ucLoginGeneration++;
        showingUcChallenge = false;
        ucLoginSessionId = null;
        ucLoginContent = null;
        ucAutoOpenPending = false;
        ucPollBusy = false;
        providerHandoffActive = false;
        if (binding != null) binding.web.removeCallbacks(pollUcLoginState);
        if (ucLoginTask != null) {
            ucLoginTask.cancel(true);
            ucLoginTask = null;
        }
    }

    private void showUcLoginPage(String encodedQr, String message) {
        String content = encodedQr == null ? "<div class=spinner></div>"
                : "<img alt=\"Provider login QR code\" src=\"data:image/png;base64," + encodedQr + "\">";
        String html = "<!doctype html><html><head><meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{height:100%;margin:0;background:#17191d;color:#fff;font-family:sans-serif}"
                + ".wrap{height:100%;box-sizing:border-box;padding:18px;overflow:hidden;display:flex;"
                + "flex-direction:column;align-items:center;justify-content:center;gap:18px}"
                + "img{box-sizing:border-box;width:min(68vw,calc(100vh - 40px),560px);"
                + "max-height:calc(100vh - 40px);object-fit:contain;flex:0 1 auto;"
                + "background:#fff;padding:14px;border-radius:16px}"
                + "p{flex:0 0 auto;font-size:22px;line-height:1.25;text-align:center;max-width:720px;"
                + "margin:0 24px;color:#e7e7e7}"
                + ".spinner{width:52px;height:52px;border:6px solid #555;border-top-color:#24b47e;border-radius:50%}"
                + "</style></head><body><div class=wrap>" + content + "<p>"
                + TextUtils.htmlEncode(message) + "</p></div></body></html>";
        binding.web.loadDataWithBaseURL("https://local.opendrive.invalid/qr-login/" + provider.getId(), html,
                "text/html", "UTF-8", null);
    }

    private static String encodeQr(String payload) {
        Bitmap bitmap = QRCode.getBitmapPx(payload, QrSizing.encodePx(), 4);
        if (bitmap == null) return null;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null;
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    private final class StorageWebViewClient extends WebViewClient {

        @Override
        public void onPageFinished(WebView view, String url) {
            if (resettingLogin) return;
            if (managementOnly) return;
            if (showingUcChallenge) {
                if (ucAutoOpenPending && ucLoginContent != null && qrHandoff != null) {
                    ucAutoOpenPending = false;
                    // Auto intentionally captures the rendered first-party QR,
                    // rebuilds a clean QR-only image, saves it, then opens the
                    // allowlisted official provider app.
                    qrHandoff.start();
                }
                return;
            }
            refreshLoginState();
            fitProviderContent(view, url);
            if (assistedLoginPending && qrHandoff != null && !hasProviderLoginCookies()) {
                assistedLoginPending = false;
                view.postDelayed(autoLoginRunnable, 250);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if (UrlUtil.isHttpScheme(scheme)) return false;
            return openCustomScheme(uri);
        }
    }
}
