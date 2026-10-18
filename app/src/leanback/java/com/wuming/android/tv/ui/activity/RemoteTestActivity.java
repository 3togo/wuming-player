package com.wuming.android.tv.ui.activity;

import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import androidx.viewbinding.ViewBinding;
import com.wuming.android.tv.R;
import com.wuming.android.tv.databinding.ActivityRemoteTestBinding;
import com.wuming.android.tv.ui.base.BaseActivity;
import com.wuming.android.tv.utils.TvRemoteMappings;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;

/** Captures raw events before any playback mapping. Back always escapes capture. */
public class RemoteTestActivity extends BaseActivity {
    private ActivityRemoteTestBinding binding;
    private TvRemoteMappings mappings;
    private boolean listening;
    private int captured = KeyEvent.KEYCODE_UNKNOWN;
    private int pending = KeyEvent.KEYCODE_UNKNOWN;
    private int ignoredRelease = KeyEvent.KEYCODE_UNKNOWN;
    private final StringBuilder events = new StringBuilder();
    private final Runnable captureTimeout = () -> {
        if (!listening) return;
        listening = false;
        ignoredRelease = pending;
        binding.remoteEvent.setText(R.string.remote_timeout);
        binding.remoteTest.requestFocus();
    };

    @Override protected ViewBinding getBinding() {
        return binding = ActivityRemoteTestBinding.inflate(getLayoutInflater());
    }

    @Override protected void initView(Bundle state) {
        mappings = new TvRemoteMappings(this);
        if (state != null) {
            captured = state.getInt("captured", KeyEvent.KEYCODE_UNKNOWN);
            binding.remoteEvent.setText(state.getString("event", getString(R.string.remote_no_key)));
        }
        updateMapping();
        binding.remoteTest.requestFocus();
    }

    @Override protected void initEvent() {
        binding.remoteTest.setOnClickListener(v -> {
            listening = true;
            pending = KeyEvent.KEYCODE_UNKNOWN;
            events.setLength(0);
            binding.remoteEvent.setText(R.string.remote_listening);
            binding.remoteEvent.removeCallbacks(captureTimeout);
            binding.remoteEvent.postDelayed(captureTimeout, 10000);
        });
        binding.remoteAssign.setOnClickListener(v -> chooseAction());
        binding.remoteReset.setOnClickListener(v -> new TvAlertDialogBuilder(this)
                .setTitle(R.string.remote_reset)
                .setMessage(R.string.remote_reset_prompt)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    mappings.clear();
                    updateMapping();
                }).show());
        binding.remoteClose.setOnClickListener(v -> finish());
    }

    private void updateMapping() {
        boolean assignable = TvRemoteMappings.canAssign(captured);
        // Keep the action focusable even before capture; explain what is needed on OK.
        binding.remoteMapping.setText(captured == KeyEvent.KEYCODE_UNKNOWN
                ? getString(R.string.remote_no_key)
                : getString(assignable ? R.string.remote_mapping : R.string.remote_reserved,
                        KeyEvent.keyCodeToString(captured), actionName(mappings.target(captured))));
    }

    private String actionName(int key) {
        int[] targets = TvRemoteMappings.targets();
        String[] labels = getResources().getStringArray(R.array.remote_actions);
        for (int i = 0; i < targets.length; i++) if (targets[i] == key) return labels[i];
        return getString(R.string.remote_default);
    }

    private void chooseAction() {
        if (!TvRemoteMappings.canAssign(captured)) {
            new TvAlertDialogBuilder(this).setMessage(captured == KeyEvent.KEYCODE_UNKNOWN
                    ? R.string.remote_test_first : R.string.remote_reserved_hint)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        int key = captured;
        String[] actions = getResources().getStringArray(R.array.remote_actions);
        String[] choices = new String[actions.length + 1];
        choices[0] = getString(R.string.remote_default);
        System.arraycopy(actions, 0, choices, 1, actions.length);
        new TvAlertDialogBuilder(this)
                .setTitle(getString(R.string.remote_assign_key, KeyEvent.keyCodeToString(key)))
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) mappings.remove(key);
                    else mappings.assign(key, TvRemoteMappings.targets()[which - 1]);
                    updateMapping();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (ignoredRelease != KeyEvent.KEYCODE_UNKNOWN && event.getKeyCode() == ignoredRelease) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                ignoredRelease = KeyEvent.KEYCODE_UNKNOWN;
            else {
                if (event.getAction() == KeyEvent.ACTION_UP) ignoredRelease = KeyEvent.KEYCODE_UNKNOWN;
                return true;
            }
        }
        if (!listening) return super.dispatchKeyEvent(event);
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                binding.remoteEvent.removeCallbacks(captureTimeout);
                listening = false;
                binding.remoteEvent.setText(R.string.remote_cancelled);
                binding.remoteTest.requestFocus();
            }
            return true;
        }
        // Only consume the button being tested. Its repeats and release stay in capture mode.
        if (pending == KeyEvent.KEYCODE_UNKNOWN && event.getAction() == KeyEvent.ACTION_DOWN)
            pending = event.getKeyCode();
        if (pending != event.getKeyCode()) return true;
        InputDevice device = event.getDevice();
        String name = device == null ? getString(R.string.remote_unknown_device) : device.getName();
        if (events.length() > 700) events.setLength(0);
        events.append(event.getAction() == KeyEvent.ACTION_DOWN ? "ACTION_DOWN" : event.getAction() == KeyEvent.ACTION_UP ? "ACTION_UP" : "ACTION_MULTIPLE").append(" · ")
                .append(getString(R.string.remote_repeat, event.getRepeatCount())).append('\n');
        binding.remoteEvent.setText(getString(R.string.remote_event,
                KeyEvent.keyCodeToString(event.getKeyCode()), event.getKeyCode(), name,
                Integer.toHexString(event.getSource()), events.toString()));
        if (event.getAction() == KeyEvent.ACTION_UP) {
            binding.remoteEvent.removeCallbacks(captureTimeout);
            listening = false;
            if (!event.isCanceled()) captured = event.getKeyCode();
            else binding.remoteEvent.setText(R.string.remote_cancelled);
            updateMapping();
            binding.remoteAssign.requestFocus();
        }
        return true;
    }

    @Override protected void onBackInvoked() {
        if (!listening) {
            super.onBackInvoked();
            return;
        }
        binding.remoteEvent.removeCallbacks(captureTimeout);
        listening = false;
        binding.remoteEvent.setText(R.string.remote_cancelled);
        binding.remoteTest.requestFocus();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("captured", captured);
        out.putString("event", listening ? getString(R.string.remote_cancelled) : binding.remoteEvent.getText().toString());
        super.onSaveInstanceState(out);
    }

    @Override protected void onStop() {
        super.onStop();
        binding.remoteEvent.removeCallbacks(captureTimeout);
        if (listening) binding.remoteEvent.setText(R.string.remote_cancelled);
        listening = false;
    }

    @Override protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
