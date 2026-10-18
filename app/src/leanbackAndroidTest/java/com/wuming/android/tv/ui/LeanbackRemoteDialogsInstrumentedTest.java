package com.wuming.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.R;
import com.wuming.android.tv.ui.activity.SettingActivity;
import com.wuming.android.tv.utils.TvDialogFocus;
import com.wuming.android.tv.utils.TvAlertDialogBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/** Compile locally; run on a TV only when a device is available and authorized. */
@RunWith(AndroidJUnit4.class)
public class LeanbackRemoteDialogsInstrumentedTest {
    @Test
    public void remoteCanLeaveEditorsAndReachConfirmation() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        try (ActivityScenario<SettingActivity> scenario = TestActivityLauncher.launch(SettingActivity.class)) {
            AtomicReference<AlertDialog> dialogRef = new AtomicReference<>();
            AtomicReference<EditText> pathRef = new AtomicReference<>();
            AtomicReference<EditText> passwordRef = new AtomicReference<>();
            scenario.onActivity(activity -> {
                LinearLayout fields = new LinearLayout(activity);
                fields.setOrientation(LinearLayout.VERTICAL);
                EditText path = new EditText(activity);
                path.setId(R.id.name);
                path.setSingleLine(true);
                path.setText("/sdcard/example.json");
                EditText password = new EditText(activity);
                password.setId(R.id.password);
                password.setSingleLine(true);
                fields.addView(path);
                fields.addView(password);
                AlertDialog dialog = new TvAlertDialogBuilder(activity)
                        .setView(fields)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
                dialog.show();
                dialogRef.set(dialog);
                pathRef.set(path);
                passwordRef.set(password);
            });
            // show() returns before the window is laid out. Zero-sized controls can neither take
            // focus nor be found by FocusFinder, so let the dialog settle before driving the
            // remote; otherwise every navigation assertion sees an unfocusable hierarchy.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                AlertDialog dialog = dialogRef.get();
                EditText path = pathRef.get();
                EditText password = passwordRef.get();
                try {
                    TvDialogFocus.form(dialog, path, password);
                    assertEquals(R.id.name, path.getId());
                    assertEquals(R.id.password, password.getId());
                    key(dialog, KeyEvent.KEYCODE_DPAD_UP);
                    assertTrue(password.hasFocus());
                    key(dialog, KeyEvent.KEYCODE_DPAD_UP);
                    assertTrue(path.hasFocus());
                    key(dialog, KeyEvent.KEYCODE_DPAD_DOWN);
                    assertTrue(password.hasFocus());
                    key(dialog, KeyEvent.KEYCODE_DPAD_DOWN);
                    assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).hasFocus());
                } finally {
                    dialog.dismiss();
                }
            });
        }
    }

    @Test
    public void dynamicDialogActionsReceiveFocusAndRemainReachable() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        try (ActivityScenario<SettingActivity> scenario = TestActivityLauncher.launch(SettingActivity.class)) {
            AtomicReference<AlertDialog> dialogRef = new AtomicReference<>();
            AtomicReference<android.widget.TextView> actionRef = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean clicked = new java.util.concurrent.atomic.AtomicBoolean();
            scenario.onActivity(activity -> {
                LinearLayout content = new LinearLayout(activity);
                content.setOrientation(LinearLayout.VERTICAL);
                android.widget.TextView action = new android.widget.TextView(activity);
                action.setText("Dynamic action");
                action.setFocusable(false);
                action.setOnClickListener(view -> clicked.set(true));
                content.addView(action);
                AlertDialog dialog = new TvAlertDialogBuilder(activity)
                        .setView(content)
                        .setNeutralButton("Auto", null)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Refresh", null).create();
                dialog.show();
                dialogRef.set(dialog);
                actionRef.set(action);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            try {
                scenario.onActivity(activity -> {
                    View action = actionRef.get();
                    assertTrue(action.isFocusable());
                    assertTrue(action.getForeground().isStateful());
                    assertTrue(action.requestFocus());
                });
                // Focus navigation is performed by ViewRootImpl, which direct
                // Dialog.dispatchKeyEvent calls bypass. Inject from the test thread.
                remoteKey(KeyEvent.KEYCODE_DPAD_CENTER);
                assertTrue(clicked.get());
                remoteKey(KeyEvent.KEYCODE_DPAD_DOWN);
                java.util.Set<View> reached = new java.util.HashSet<>();
                scenario.onActivity(activity -> reached.add(dialogRef.get().getCurrentFocus()));
                for (int code : new int[]{KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT}) {
                    for (int i = 0; i < 3; i++) {
                        remoteKey(code);
                        scenario.onActivity(activity -> reached.add(dialogRef.get().getCurrentFocus()));
                    }
                }
                scenario.onActivity(activity -> {
                    AlertDialog dialog = dialogRef.get();
                    for (int which : new int[]{AlertDialog.BUTTON_POSITIVE,
                            AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                        assertTrue("Remote could not reach button " + which,
                                reached.contains(dialog.getButton(which)));
                        assertTrue(dialog.getButton(which).getForeground().isStateful());
                    }
                });
                remoteKey(KeyEvent.KEYCODE_BACK);
                scenario.onActivity(activity -> org.junit.Assert.assertFalse(dialogRef.get().isShowing()));
            } finally {
                scenario.onActivity(activity -> dialogRef.get().dismiss());
            }
        }
    }

    @Test
    public void secondaryActionsAreFocusableInTheTvResources() {
        try (ActivityScenario<SettingActivity> scenario = TestActivityLauncher.launch(SettingActivity.class)) {
            scenario.onActivity(activity -> {
                int[][] layouts = {
                        {R.layout.activity_push, R.id.code, R.id.clip},
                        {R.layout.dialog_device, R.id.disconnect, R.id.mode, R.id.scan, R.id.refresh},
                        {R.layout.dialog_timer, R.id.time1, R.id.time2, R.id.time3, R.id.time4},
                        {R.layout.dialog_info, R.id.header, R.id.url},
                        {R.layout.dialog_receive, R.id.frame},
                        {R.layout.adapter_value, R.id.text}
                };
                for (int[] layout : layouts) {
                    View root = activity.getLayoutInflater().inflate(layout[0], null, false);
                    for (int i = 1; i < layout.length; i++) {
                        View action = root.findViewById(layout[i]);
                        assertTrue(activity.getResources().getResourceName(layout[i]), action.isFocusable());
                        assertTrue("TV action needs a focus outline", action.getForeground() != null);
                    }
                }
            });
        }
    }

    private static void remoteKey(int code) {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.sendKeyDownUpSync(code);
        instrumentation.waitForIdleSync();
    }

    private static void key(AlertDialog dialog, int code) {
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }
}
