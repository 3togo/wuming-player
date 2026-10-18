package com.wuming.android.tv.ui.activity;
import com.wuming.android.tv.setting.DisplaySettings;
import com.wuming.android.tv.setting.PlayerSettings;
import com.wuming.android.tv.setting.AppSettings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.databinding.ActivitySettingPlayerBinding;
import com.wuming.android.tv.impl.BufferCallback;
import com.wuming.android.tv.impl.SpeedCallback;
import com.wuming.android.tv.impl.UaCallback;
import com.wuming.android.tv.ui.SettingsOperations;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.ui.dialog.BufferDialog;
import com.wuming.android.tv.ui.dialog.SpeedDialog;
import com.wuming.android.tv.ui.dialog.UaDialog;
import com.wuming.android.tv.utils.ResUtil;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;

import java.text.DecimalFormat;

public class SettingPlayerActivity extends BaseActivity implements UaCallback, BufferCallback, SpeedCallback {

    private ActivitySettingPlayerBinding mBinding;
    private DecimalFormat format;
    private String[] background;
    private String[] caption;
    private String[] render;
    private String[] scale;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingPlayerActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    private String getVideoInfoMode() {
        int mode = PlayerSettings.getVideoInfo();
        String[] modes = {getString(R.string.video_info_mode_0), getString(R.string.video_info_mode_1), getString(R.string.video_info_mode_2), getString(R.string.video_info_mode_3)};
        return modes[mode];
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingPlayerBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setVisible();
        format = new DecimalFormat("0.#");
        mBinding.render.requestFocus();
        mBinding.uaText.setText(PlayerSettings.getUa());
        SettingsOperations.setToggleState(mBinding.aacText, PlayerSettings.isPreferAAC());
        SettingsOperations.setToggleState(mBinding.tunnelText, PlayerSettings.isTunnel());
        SettingsOperations.setToggleState(mBinding.adblockText, AppSettings.isAdblock());
        mBinding.speedText.setText(format.format(PlayerSettings.getSpeed()));
        mBinding.bufferText.setText(String.valueOf(PlayerSettings.getBuffer()));
        SettingsOperations.setToggleState(mBinding.audioDecodeText, PlayerSettings.isAudioPrefer());
        SettingsOperations.setToggleState(mBinding.videoDecodeText, PlayerSettings.isVideoPrefer());
        SettingsOperations.setToggleState(mBinding.danmakuLoadText, PlayerSettings.isDanmakuLoad());
        SettingsOperations.setToggleState(mBinding.danmakuShowText, PlayerSettings.isDanmakuShow());
        SettingsOperations.setToggleState(mBinding.acrossText, DisplaySettings.isAcross());
        SettingsOperations.setToggleState(mBinding.changeText, DisplaySettings.isChange());
        SettingsOperations.setToggleState(mBinding.invertText, DisplaySettings.isInvert());
        mBinding.videoInfoText.setText(getVideoInfoMode());
        SettingsOperations.setToggleState(mBinding.bootLiveText, AppSettings.isBootLive());
        SettingsOperations.setToggleState(mBinding.zhuyinText, DisplaySettings.isZhuyin());
        mBinding.scaleText.setText((scale = ResUtil.getStringArray(R.array.select_scale))[DisplaySettings.getScale()]);
        mBinding.renderText.setText((render = ResUtil.getStringArray(R.array.select_render))[PlayerSettings.getRender()]);
        mBinding.captionText.setText((caption = ResUtil.getStringArray(R.array.select_caption))[PlayerSettings.isCaption() ? 1 : 0]);
        background = new String[]{getSwitch(false), getSwitch(true)};
        SettingsOperations.setToggleState(mBinding.backgroundText, PlayerSettings.getBackground() == 1);
        mBinding.networkIpv6Text.setText(R.string.ipv6_test_ready);
    }

    @Override
    protected void initEvent() {
        mBinding.ua.setOnClickListener(this::onUa);
        mBinding.aac.setOnClickListener(this::setAAC);
        mBinding.scale.setOnClickListener(this::onScale);
        mBinding.speed.setOnClickListener(this::onSpeed);
        mBinding.buffer.setOnClickListener(this::onBuffer);
        mBinding.render.setOnClickListener(this::setRender);
        mBinding.tunnel.setOnClickListener(this::setTunnel);
        mBinding.caption.setOnClickListener(this::setCaption);
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.caption.setOnLongClickListener(this::onCaption);
        mBinding.background.setOnClickListener(this::onBackground);
        mBinding.audioDecode.setOnClickListener(this::setAudioDecode);
        mBinding.videoDecode.setOnClickListener(this::setVideoDecode);
        mBinding.danmakuLoad.setOnClickListener(this::setDanmakuLoad);
        mBinding.danmakuShow.setOnClickListener(this::setDanmakuShow);
        mBinding.across.setOnClickListener(this::setAcross);
        mBinding.change.setOnClickListener(this::setChange);
        mBinding.invert.setOnClickListener(this::setInvert);
        mBinding.videoInfo.setOnClickListener(this::setVideoInfo);
        mBinding.bootLive.setOnClickListener(this::setBootLive);
        mBinding.zhuyin.setOnClickListener(this::setZhuyin);
        SettingsOperations.bindVideoNetworkSettings(
                mBinding.mobileVideo, mBinding.mobileVideoText,
                mBinding.roamingVideo, mBinding.roamingVideoText);
        SettingsOperations.bindLegacyTlsSetting(this, mBinding.legacyTls, mBinding.legacyTlsText);
        mBinding.networkIpv6.setOnClickListener(this::onNetworkIPv6);
    }

    private void setVisible() {
        if (PlayerSettings.getBackground() == 2) PlayerSettings.putBackground(1);
        mBinding.caption.setVisibility(AppSettings.hasCaption() ? View.VISIBLE : View.GONE);
    }

    private void onUa(View view) {
        UaDialog.create(this).show();
    }

    @Override
    public void setUa(String ua) {
        mBinding.uaText.setText(ua);
        PlayerSettings.putUa(ua);
    }

    private void setAAC(View view) {
        PlayerSettings.putPreferAAC(!PlayerSettings.isPreferAAC());
        SettingsOperations.setToggleState(mBinding.aacText, PlayerSettings.isPreferAAC());
    }

    private void onScale(View view) {
        showChoice(R.string.player_scale, scale, DisplaySettings.getScale(), which -> {
            mBinding.scaleText.setText(scale[which]);
            DisplaySettings.putScale(which);
        });
    }

    private void onSpeed(View view) {
        SpeedDialog.create(this).show();
    }

    @Override
    public void setSpeed(float speed) {
        mBinding.speedText.setText(format.format(speed));
        PlayerSettings.putSpeed(speed);
    }

    private void onBuffer(View view) {
        BufferDialog.create(this).show();
    }

    @Override
    public void setBuffer(int times) {
        mBinding.bufferText.setText(String.valueOf(times));
        PlayerSettings.putBuffer(times);
    }

    private void setRender(View view) {
        if (PlayerSettings.isTunnel() && PlayerSettings.getRender() == 0) setTunnel(view);
        int index = (PlayerSettings.getRender() + 1) % render.length;
        mBinding.renderText.setText(render[index]);
        PlayerSettings.putRender(index);
    }

    private void setTunnel(View view) {
        PlayerSettings.putTunnel(!PlayerSettings.isTunnel());
        SettingsOperations.setToggleState(mBinding.tunnelText, PlayerSettings.isTunnel());
        if (PlayerSettings.isTunnel() && PlayerSettings.getRender() == 1) setRender(view);
    }

    private void setCaption(View view) {
        PlayerSettings.putCaption(!PlayerSettings.isCaption());
        mBinding.captionText.setText(caption[PlayerSettings.isCaption() ? 1 : 0]);
    }

    private void setAdblock(View view) {
        AppSettings.putAdblock(!AppSettings.isAdblock());
        SettingsOperations.setToggleState(mBinding.adblockText, AppSettings.isAdblock());
    }

    private boolean onCaption(View view) {
        if (PlayerSettings.isCaption()) startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
        return PlayerSettings.isCaption();
    }

    private void setAudioDecode(View view) {
        PlayerSettings.putAudioPrefer(!PlayerSettings.isAudioPrefer());
        SettingsOperations.setToggleState(mBinding.audioDecodeText, PlayerSettings.isAudioPrefer());
    }

    private void setVideoDecode(View view) {
        PlayerSettings.putVideoPrefer(!PlayerSettings.isVideoPrefer());
        SettingsOperations.setToggleState(mBinding.videoDecodeText, PlayerSettings.isVideoPrefer());
    }

    private void setDanmakuLoad(View view) {
        PlayerSettings.putDanmakuLoad(!PlayerSettings.isDanmakuLoad());
        SettingsOperations.setToggleState(mBinding.danmakuLoadText, PlayerSettings.isDanmakuLoad());
    }

    private void setDanmakuShow(View view) {
        PlayerSettings.putDanmakuShow(!PlayerSettings.isDanmakuShow());
        SettingsOperations.setToggleState(mBinding.danmakuShowText, PlayerSettings.isDanmakuShow());
    }

    private void setAcross(View view) {
        DisplaySettings.putAcross(!DisplaySettings.isAcross());
        SettingsOperations.setToggleState(mBinding.acrossText, DisplaySettings.isAcross());
    }

    private void setChange(View view) {
        DisplaySettings.putChange(!DisplaySettings.isChange());
        SettingsOperations.setToggleState(mBinding.changeText, DisplaySettings.isChange());
    }

    private void setInvert(View view) {
        DisplaySettings.putInvert(!DisplaySettings.isInvert());
        SettingsOperations.setToggleState(mBinding.invertText, DisplaySettings.isInvert());
    }

    private void setVideoInfo(View view) {
        int currentMode = PlayerSettings.getVideoInfo();
        int newMode = (currentMode + 1) % 4; // Cycle through 0, 1, 2, 3
        PlayerSettings.putVideoInfo(newMode);
        mBinding.videoInfoText.setText(getVideoInfoMode());
    }

    private void setBootLive(View view) {
        AppSettings.putBootLive(!AppSettings.isBootLive());
        SettingsOperations.setToggleState(mBinding.bootLiveText, AppSettings.isBootLive());
    }

    private void setZhuyin(View view) {
        DisplaySettings.putZhuyin(!DisplaySettings.isZhuyin());
        SettingsOperations.setToggleState(mBinding.zhuyinText, DisplaySettings.isZhuyin());
    }

    private void onNetworkIPv6(View view) {
        SettingsOperations.testIPv6(this, this, view, mBinding.networkIpv6Text);
    }

    private void onBackground(View view) {
        showChoice(R.string.player_background, background, PlayerSettings.getBackground(), which -> {
            SettingsOperations.setToggleState(mBinding.backgroundText, which == 1);
            PlayerSettings.putBackground(which);
        });
    }

    private void showChoice(int title, String[] items, int checked, ChoiceCallback callback) {
        androidx.appcompat.app.AlertDialog choice = new TvAlertDialogBuilder(this).setTitle(title).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(items, checked, (dialog, which) -> {
            callback.onChoice(which);
            dialog.dismiss();
        }).show();
        com.wuming.android.tv.utils.TvDialogFocus.list(choice);
    }

    private interface ChoiceCallback {

        void onChoice(int which);
    }

    @Override
    protected void onDestroy() {
        mBinding = null;
        super.onDestroy();
    }
}
