package com.wuming.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.databinding.ActivityScanBinding;
import com.wuming.android.tv.server.RemoteAccess;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.UrlUtil;
import com.wuming.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.CaptureManager;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.util.List;

import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Request;
import okhttp3.RequestBody;

public class ScanActivity extends BaseActivity implements BarcodeCallback {

    private ActivityScanBinding mBinding;
    private CaptureManager mCapture;
    private boolean pairing;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, ScanActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityScanBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Util.hideSystemUI(this);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mCapture = new CaptureManager(this, mBinding.scanner);
        mBinding.scanner.getBarcodeView().setDecoderFactory(new DefaultDecoderFactory(List.of(BarcodeFormat.QR_CODE)));
    }

    @Override
    public void barcodeResult(BarcodeResult result) {
        String text = result.getText();
        if (text == null || text.isEmpty()) return;
        String baseUrl = extractBaseUrl(text);
        if (baseUrl == null || baseUrl.isEmpty()) return;
        String token = android.net.Uri.parse(text).getQueryParameter("token");
        if (token != null && !token.isEmpty()) {
            finishPairing(baseUrl, token);
        } else if (!pairing) {
            String fragment = android.net.Uri.parse(text).getFragment();
            String code = fragment == null ? null : android.net.Uri.parse("http://pair/?" + fragment).getQueryParameter("pair");
            if (code == null || !code.matches("[0-9a-f]{64}")) {
                mBinding.scanner.decodeSingle(this);
                return;
            }
            pairing = true;
            Task.execute(() -> claimPairing(baseUrl, code));
        }
    }

    private void claimPairing(String baseUrl, String code) {
        String token = "";
        Request request = new Request.Builder().url(baseUrl + "/pair")
                .header("X-Pairing-Code", code).post(RequestBody.create(new byte[0], null)).build();
        try (Response response = OkHttp.noRedirect(10000).newCall(request).execute()) {
            ResponseBody body = response.body();
            if (response.isSuccessful() && body != null) token = body.string();
        } catch (Exception ignored) {
        }
        String result = token;
        runOnUiThread(() -> {
            if (!result.isEmpty()) finishPairing(baseUrl, result);
            else {
                pairing = false;
                mBinding.scanner.decodeSingle(this);
            }
        });
    }

    private void finishPairing(String baseUrl, String token) {
        RemoteAccess.rememberPairing(baseUrl, token);
        setResult(RESULT_OK, new Intent().putExtra("address", baseUrl));
        finish();
    }

    private String extractBaseUrl(String url) {
        String scheme = UrlUtil.scheme(url);
        String host = UrlUtil.host(url);
        if (scheme.isEmpty() || host.isEmpty()) return null;
        android.net.Uri uri = android.net.Uri.parse(url);
        int port = uri.getPort();
        if (port == -1) {
            return scheme + "://" + host;
        } else {
            return scheme + "://" + host + ":" + port;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        mCapture.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCapture.onResume();
        mBinding.scanner.decodeSingle(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mCapture.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mCapture.onDestroy();
    }
}
