package com.wuming.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.databinding.ActivityPushBinding;
import com.wuming.android.tv.server.RemoteAccess;
import com.wuming.android.tv.server.Server;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.utils.QRCode;
import com.wuming.android.tv.utils.QrSizing;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.Sniffer;
import com.wuming.android.tv.utils.Util;

public class PushActivity extends BaseActivity {

    private ActivityPushBinding mBinding;

    public static void start(Activity activity) {
        start(activity, 2);
    }

    public static void start(Activity activity, int tab) {
        Intent intent = new Intent(activity, PushActivity.class);
        intent.putExtra("tab", tab);
        activity.startActivity(intent);
    }

    private int getTab() {
        return getIntent().getIntExtra("tab", 2);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityPushBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        RemoteAccess.openPairingWindow();
        updateCode();
        mBinding.code.setContentDescription(getString(R.string.remote_pairing_qr_description));
        mBinding.info.setText(ResUtil.getString(R.string.push_info, Server.get().getAddress()));
    }

    @Override
    protected void initEvent() {
        mBinding.code.setOnClickListener(this::onCode);
        mBinding.code.setOnLongClickListener(this::onResetPairing);
        mBinding.clip.setOnClickListener(this::onClip);
    }

    private void updateCode() {
        mBinding.code.setImageBitmap(QRCode.getBitmapPx(Server.get().getAddress(getTab()), QrSizing.encodePx(), 1));
    }

    private boolean onResetPairing(View view) {
        RemoteAccess.rotateServerToken();
        updateCode();
        com.wuming.android.tv.utils.Notify.show(R.string.remote_pairing_reset);
        return true;
    }

    private void onClip(View view) {
        CharSequence text = Util.getClipText();
        if (!TextUtils.isEmpty(text)) VideoActivity.start(this, Sniffer.getUrl(text.toString()));
    }

    private void onCode(View view) {
        RemoteAccess.openPairingWindow();
        updateCode();
    }

    @Override
    protected void onDestroy() {
        RemoteAccess.closePairingWindow();
        mBinding = null;
        super.onDestroy();
    }
}
