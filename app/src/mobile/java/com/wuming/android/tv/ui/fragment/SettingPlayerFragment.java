package com.wuming.android.tv.ui.fragment;
import com.wuming.android.tv.setting.DisplaySettings;
import com.wuming.android.tv.setting.PlayerSettings;
import com.wuming.android.tv.setting.AppSettings;

import android.content.Intent;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.wuming.android.tv.R;
import com.wuming.android.tv.databinding.FragmentSettingPlayerBinding;
import com.wuming.android.tv.impl.BufferCallback;
import com.wuming.android.tv.impl.SpeedCallback;
import com.wuming.android.tv.impl.UaCallback;
import com.wuming.android.tv.ui.SettingsOperations;
import com.wuming.android.tv.ui.base.BaseFragment;
import com.wuming.android.tv.ui.dialog.BufferDialog;
import com.wuming.android.tv.ui.dialog.SpeedDialog;
import com.wuming.android.tv.ui.dialog.UaDialog;
import com.wuming.android.tv.utils.ResUtil;
import androidx.appcompat.app.AlertDialog;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;

import java.text.DecimalFormat;

public class SettingPlayerFragment extends BaseFragment implements UaCallback, BufferCallback, SpeedCallback {

    private FragmentSettingPlayerBinding mBinding;
    private AlertDialog mChoiceDialog;
    private String[] background;
    private String[] caption;
    private String[] render;
    private String[] scale;
    private final DecimalFormat format = new DecimalFormat("0.#");

    public static SettingPlayerFragment newInstance() {
        return new SettingPlayerFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingPlayerBinding.inflate(inflater, container, false);
    }

    @Override
    protected void releaseBinding() {
        mBinding = null;
    }

    @Override
    protected void initView() {
        mBinding.uaText.setText(PlayerSettings.getUa());
        SettingsOperations.setToggleState(mBinding.aacText, PlayerSettings.isPreferAAC());
        SettingsOperations.setToggleState(mBinding.tunnelText, PlayerSettings.isTunnel());
        SettingsOperations.setToggleState(mBinding.adblockText, AppSettings.isAdblock());
        mBinding.speedText.setText(format.format(PlayerSettings.getSpeed()));
        mBinding.bufferText.setText(String.valueOf(PlayerSettings.getBuffer()));
        SettingsOperations.setToggleState(mBinding.audioDecodeText, PlayerSettings.isAudioPrefer());
        SettingsOperations.setToggleState(mBinding.videoDecodeText, PlayerSettings.isVideoPrefer());
        SettingsOperations.setToggleState(mBinding.danmakuLoadText, PlayerSettings.isDanmakuLoad());
        mBinding.caption.setVisibility(AppSettings.hasCaption() ? View.VISIBLE : View.GONE);
        mBinding.scaleText.setText((scale = ResUtil.getStringArray(R.array.select_scale))[DisplaySettings.getScale()]);
        mBinding.renderText.setText((render = ResUtil.getStringArray(R.array.select_render))[PlayerSettings.getRender()]);
        mBinding.captionText.setText((caption = ResUtil.getStringArray(R.array.select_caption))[PlayerSettings.isCaption() ? 1 : 0]);
        mBinding.backgroundText.setText((background = ResUtil.getStringArray(
                R.array.select_background))[PlayerSettings.getBackground()]);
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
        SettingsOperations.bindVideoNetworkSettings(
                mBinding.mobileVideo, mBinding.mobileVideoText,
                mBinding.roamingVideo, mBinding.roamingVideoText);
        SettingsOperations.bindLegacyTlsSetting(
                requireActivity(), mBinding.legacyTls, mBinding.legacyTlsText);
        mBinding.networkIpv6.setOnClickListener(this::onNetworkIPv6);
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
        mChoiceDialog = new TvAlertDialogBuilder(requireActivity()).setTitle(R.string.player_scale).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(scale, DisplaySettings.getScale(), (dialog, which) -> {
            mBinding.scaleText.setText(scale[which]);
            DisplaySettings.putScale(which);
            dialog.dismiss();
        }).show();
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

    private boolean onCaption(View view) {
        if (PlayerSettings.isCaption()) startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
        return PlayerSettings.isCaption();
    }

    private void setAdblock(View view) {
        AppSettings.putAdblock(!AppSettings.isAdblock());
        SettingsOperations.setToggleState(mBinding.adblockText, AppSettings.isAdblock());
    }

    private void onBackground(View view) {
        mChoiceDialog = new TvAlertDialogBuilder(requireActivity()).setTitle(R.string.player_background).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(background, PlayerSettings.getBackground(), (dialog, which) -> {
            mBinding.backgroundText.setText(background[which]);
            PlayerSettings.putBackground(which);
            dialog.dismiss();
        }).show();
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

    private void onNetworkIPv6(View view) {
        SettingsOperations.testIPv6(requireActivity(), getViewLifecycleOwner(), view, mBinding.networkIpv6Text);
    }

    @Override
    public void onDestroyView() {
        if (mChoiceDialog != null && mChoiceDialog.isShowing()) mChoiceDialog.dismiss();
        mChoiceDialog = null;
        super.onDestroyView();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) initView();
    }
}
