package com.wuming.android.tv.ui.base;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.util.Base64;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.QrChallenge;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.cloud.opendrive.DriveLoginHelper;
import com.wuming.android.tv.ui.dialog.BilibiliLoginDialog;
import com.wuming.android.tv.utils.QrSizing;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.Future;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Owns the login dialogs a source can demand while a video is being prepared: the
 * provider "scan this QR code" dialog and the Bilibili login dialog.
 *
 * <p>Extracted from {@link BaseVideoActivity}: the dialogs, the pending HTTP call and the
 * background decode are self-contained login state, while the activity keeps every playback
 * decision (retry, skip, error) behind {@link Host}.
 *
 * <p>The async callbacks hold only a weak reference to this flow, so a decode or response that
 * lands after the screen is gone cannot pin the activity.
 */
public final class PlaybackLoginFlow {

    public interface Host {
        boolean isUiActive();

        boolean isBaiduLogin();

        void startFlow();

        void showError(String text);

        void retryPlayback();
    }

    private final Activity activity;
    private final Host host;
    private AlertDialog dialog;
    private BilibiliLoginDialog bilibiliDialog;
    private Call call;
    private Future<?> decodeTask;
    private int requestId;

    public PlaybackLoginFlow(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    // ===== Provider QR =====

    /** Shows the challenge code, replacing any login dialog already on screen. */
    public void show(QrChallenge challenge) {
        release();
        int current = ++requestId;
        if (!challenge.getImageBase64().isEmpty()) {
            String data = challenge.getImageBase64();
            WeakReference<PlaybackLoginFlow> selfRef = new WeakReference<>(this);
            decodeTask = Task.submitLarge(() -> {
                Bitmap bitmap = null;
                try {
                    bitmap = decodeQrBitmap(Base64.decode(data, Base64.DEFAULT));
                } catch (IllegalArgumentException ignored) {
                }
                Bitmap result = bitmap;
                App.post(() -> {
                    PlaybackLoginFlow self = selfRef.get();
                    if (self == null) recycle(result);
                    else self.onBitmapLoaded(current, result);
                });
            });
            return;
        }
        try {
            call = com.github.catvod.net.OkHttp.newCall(challenge.getImageUrl());
            call.enqueue(new BitmapCallback(this, current));
        } catch (IllegalArgumentException e) {
            call = null;
            showQrError();
        }
    }

    // ===== Bilibili =====

    public void showBilibiliLogin() {
        release();
        bilibiliDialog = BilibiliLoginDialog.show(activity, () -> {
            bilibiliDialog = null;
            if (host.isUiActive()) host.retryPlayback();
        }, () -> bilibiliDialog = null);
    }

    public static boolean isBilibiliSite(Site site) {
        return DriveLoginHelper.isBilibiliSite(site);
    }

    public static boolean isBilibiliLoginError(String message, Site site) {
        return DriveLoginHelper.isBilibiliLoginError(message, isBilibiliSite(site));
    }

    // ===== Lifetime =====

    /** Cancels the pending call/decode and dismisses whatever login dialog is open. */
    public void release() {
        requestId++;
        if (call != null) call.cancel();
        if (decodeTask != null) decodeTask.cancel(true);
        call = null;
        decodeTask = null;
        AlertDialog current = dialog;
        dialog = null;
        if (current != null) current.dismiss();
        BilibiliLoginDialog bilibili = bilibiliDialog;
        bilibiliDialog = null;
        if (bilibili != null) bilibili.dismiss();
    }

    private void onBitmapLoaded(int current, Bitmap bitmap) {
        if (current != requestId || !host.isUiActive()) {
            recycle(bitmap);
            return;
        }
        call = null;
        decodeTask = null;
        if (bitmap == null) {
            showQrError();
            return;
        }
        showBitmap(bitmap);
    }

    private void showBitmap(Bitmap bitmap) {
        ImageView view = new ImageView(activity);
        BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), bitmap);
        drawable.setFilterBitmap(false);
        view.setImageDrawable(drawable);
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setBackgroundColor(Color.WHITE);
        int padding = ResUtil.getDimensionPixelSize(R.dimen.vs_16);
        view.setPadding(padding, padding, padding, padding);
        AlertDialog created = new TvAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_scan_qr_title)
                .setMessage(host.isBaiduLogin()
                        ? R.string.dialog_scan_qr_baidu_hint
                        : R.string.dialog_scan_qr_provider_hint)
                .setView(QrSizing.squareHost(activity, view, QrSizing.estimatePx(activity)))
                .setPositiveButton(R.string.dialog_scan_qr_retry, (d, w) -> host.retryPlayback())
                .setNegativeButton(R.string.dialog_scan_qr_skip, (d, w) -> host.startFlow())
                .setOnCancelListener(d -> host.startFlow())
                .create();
        created.setOnDismissListener(d -> {
            view.setImageDrawable(null);
            recycle(bitmap);
            if (dialog == created) dialog = null;
        });
        dialog = created;
        try {
            created.show();
        } catch (RuntimeException e) {
            dialog = null;
            view.setImageDrawable(null);
            recycle(bitmap);
            showQrError();
        }
    }

    private void showQrError() {
        if (!host.isUiActive()) return;
        host.showError(activity.getString(R.string.error_play_url));
        host.startFlow();
    }

    private static byte[] readLimited(ResponseBody body) throws IOException {
        return DriveLoginHelper.readLimited(body);
    }

    private static Bitmap decodeQrBitmap(byte[] data) {
        return DriveLoginHelper.decodeQrBitmap(data);
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static final class BitmapCallback implements Callback {
        private final WeakReference<PlaybackLoginFlow> selfRef;
        private final int current;

        BitmapCallback(PlaybackLoginFlow self, int current) {
            this.selfRef = new WeakReference<>(self);
            this.current = current;
        }

        @Override
        public void onFailure(@NonNull Call call, @NonNull IOException error) {
            post(null);
        }

        @Override
        public void onResponse(@NonNull Call call, @NonNull Response response) {
            Bitmap bitmap = null;
            try (Response current = response) {
                if (current.isSuccessful()) bitmap = decodeQrBitmap(readLimited(current.body()));
            } catch (Exception ignored) {
            }
            post(bitmap);
        }

        private void post(Bitmap bitmap) {
            App.post(() -> {
                PlaybackLoginFlow self = selfRef.get();
                if (self == null) recycle(bitmap);
                else self.onBitmapLoaded(current, bitmap);
            });
        }
    }
}
