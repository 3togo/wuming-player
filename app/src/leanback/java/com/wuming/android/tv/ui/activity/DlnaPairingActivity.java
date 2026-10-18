package com.wuming.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.viewbinding.ViewBinding;
import com.wuming.android.tv.R;
import com.wuming.android.tv.databinding.ActivityDlnaPairingBinding;
import com.wuming.android.tv.server.DlnaPairing;
import com.wuming.android.tv.server.Server;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.utils.QRCode;
import com.wuming.android.tv.utils.QrSizing;
import com.wuming.android.tv.setting.DlnaSettings;
import com.wuming.android.tv.utils.TvDialogFocus;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;
import androidx.appcompat.app.AlertDialog;
import android.view.View;
import android.widget.EditText;
import android.text.InputFilter;

import java.lang.ref.WeakReference;

public class DlnaPairingActivity extends BaseActivity {
    private ActivityDlnaPairingBinding binding;
    private AlertDialog editNameDialog;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final UpdateRunnable update = new UpdateRunnable(this);

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, DlnaPairingActivity.class));
    }

    @Override protected ViewBinding getBinding() {
        return binding = ActivityDlnaPairingBinding.inflate(getLayoutInflater());
    }

    @Override protected void initView(Bundle savedInstanceState) {
        Server.get().start();
    }

    @Override protected void initEvent() {
        binding.refresh.setOnClickListener(v -> refreshCode());
        binding.revoke.setOnClickListener(v -> {
            DlnaPairing.SESSION.revoke();
            refreshCode();
        });
        binding.refresh.requestFocus();
        binding.editName.setOnClickListener(v -> editName());
    }

    private void editName() {
        View content = getLayoutInflater().inflate(R.layout.dialog_dlna_name, null);
        EditText name = content.findViewById(R.id.name);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(DlnaSettings.MAX_CODE_POINTS)});
        name.setText(DlnaSettings.getName());
        name.setSelection(name.length());
        AlertDialog dialog = new TvAlertDialogBuilder(this)
                .setTitle(R.string.dlna_name_title).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.dlna_name_default, null)
                .setPositiveButton(R.string.dlna_name_save, (d, which) -> {
                    DlnaSettings.setName(name.getText().toString());
                    Notify.show(R.string.dlna_name_saved);
                }).create();
        editNameDialog = dialog;
        dialog.show();
        TvDialogFocus.form(dialog, name);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setNextFocusUpId(name.getId());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            name.setText(DlnaSettings.DEFAULT_NAME);
            name.setSelection(name.length());
        });
    }

    private void refreshCode() {
        String address = Server.get().getAddress() + "/dlna-pair.html#code=" + DlnaPairing.SESSION.open();
        binding.code.setImageBitmap(QRCode.getBitmapPx(address, QrSizing.encodePx(), 1));
        handler.removeCallbacks(update);
        update.run();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshCode();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(update);
        DlnaPairing.SESSION.close();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(update);
        if (editNameDialog != null && editNameDialog.isShowing()) editNameDialog.dismiss();
        editNameDialog = null;
        binding = null;
        super.onDestroy();
    }

    private static class UpdateRunnable implements Runnable {
        private final WeakReference<DlnaPairingActivity> ref;

        UpdateRunnable(DlnaPairingActivity activity) {
            this.ref = new WeakReference<>(activity);
        }

        @Override public void run() {
            DlnaPairingActivity activity = ref.get();
            if (activity == null || activity.binding == null) return;
            String peer = DlnaPairing.SESSION.pairedPeer();
            activity.binding.status.setText(peer.isEmpty() ? activity.getString(R.string.dlna_pairing_locked)
                    : activity.getString(R.string.dlna_pairing_paired, peer));
            boolean expired = DlnaPairing.SESSION.code().isEmpty();
            if (expired) activity.binding.code.setImageDrawable(null);
            activity.binding.qrHint.setText(expired ? R.string.dlna_pairing_expired : R.string.dlna_pairing_qr);
            activity.handler.postDelayed(this, 1000);
        }
    }
}
