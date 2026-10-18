package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.R;
import com.wuming.android.tv.ui.activity.CloudStorageActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NotifyPopupInstrumentedTest {

    private static final String MESSAGE = "Consistent notification banner";
    private static final String PROVIDER_LOGIN_MESSAGE = "网盘源需使用UC浏览器APP扫码登录";

    @Test
    public void popupMatchesSemanticLabelsWithoutCumulativeScaling() throws Exception {
        try (ActivityScenario<CloudStorageActivity> scenario = ActivityScenario.launch(CloudStorageActivity.class)) {
            scenario.onActivity(activity -> {
                PopupToast.get().dismiss();
                Notify.show("播放提示：请检查网络后重试。 Playback notice: please retry.");
                TextView popup = activity.getWindow().getDecorView().findViewById(R.id.notify_popup);
                assertNotNull(popup);
                float expected = TextScale.pixels(popup, com.wuming.tv.core.design.TextRole.LABEL);
                assertEquals(expected, popup.getTextSize(), 0.01f);
                assertEquals("Source picker columns must not change text size", FontScale.siteDialogText(1), FontScale.siteDialogText(3));
                com.google.android.material.textview.MaterialTextView runtime = new com.google.android.material.textview.MaterialTextView(activity);
                TextScale.setRole(runtime, com.wuming.tv.core.design.TextRole.LABEL);
                TextScale.setRole(runtime, com.wuming.tv.core.design.TextRole.LABEL);
                assertEquals(expected, runtime.getTextSize(), 0.01f);
                TextScale.resetAndApply(popup);
                TextScale.apply(popup);
                assertEquals(expected, popup.getTextSize(), 0.01f);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            saveTypographyScreenshot("typography-popup.png");
            scenario.onActivity(activity -> PopupToast.get().dismiss());
        }
    }

    @Test
    public void dialogUsesTitleBodyAndLabelRolesAndPreservesShowListener() throws Exception {
        androidx.appcompat.app.AlertDialog[] dialog = new androidx.appcompat.app.AlertDialog[1];
        java.util.concurrent.atomic.AtomicBoolean shown = new java.util.concurrent.atomic.AtomicBoolean();
        try (ActivityScenario<CloudStorageActivity> scenario = ActivityScenario.launch(CloudStorageActivity.class)) {
            scenario.onActivity(activity -> {
                dialog[0] = new TvAlertDialogBuilder(activity)
                        .setTitle("播放提示 / Playback notice")
                        .setMessage("请检查网络连接后重试。\nPlease check your connection and retry playback.")
                        .setPositiveButton("确定 / OK", null)
                        .setNegativeButton("取消 / Cancel", null).create();
                dialog[0].setOnShowListener(ignored -> shown.set(true));
                dialog[0].show();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertTrue("Caller show listener must be preserved", shown.get());
                TextView title = dialog[0].findViewById(androidx.appcompat.R.id.alertTitle);
                TextView message = dialog[0].findViewById(android.R.id.message);
                TextView button = dialog[0].getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
                assertEquals(TextScale.pixels(title, com.wuming.tv.core.design.TextRole.TITLE), title.getTextSize(), 0.01f);
                assertEquals(TextScale.pixels(message, com.wuming.tv.core.design.TextRole.BODY), message.getTextSize(), 0.01f);
                assertEquals(TextScale.pixels(button, com.wuming.tv.core.design.TextRole.LABEL), button.getTextSize(), 0.01f);
                float before = message.getTextSize();
                TextScale.resetAndApply(dialog[0].getWindow().getDecorView());
                assertEquals(before, message.getTextSize(), 0.01f);
            });
            saveTypographyScreenshot("typography-dialog.png");
            scenario.onActivity(activity -> dialog[0].dismiss());
        }
    }

    private static void saveTypographyScreenshot(String name) throws Exception {
        // Main-queue idleness does not finish the banner's 160ms entrance animation.
        android.os.SystemClock.sleep(250);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        java.io.File file = new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), name);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            screenshot.recycle();
        }
    }

    @Test
    public void popupUsesSharedThemeAndBottomPlacementAndClearsOnRecreate() {
        try (ActivityScenario<CloudStorageActivity> scenario =
                     ActivityScenario.launch(CloudStorageActivity.class)) {
            scenario.onActivity(activity -> {
                PopupToast.get().dismiss();
                Notify.show(MESSAGE);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            scenario.onActivity(activity -> {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                TextView popup = decor.findViewById(R.id.notify_popup);
                assertNotNull(popup);
                assertEquals(MESSAGE, popup.getText().toString());
                assertEquals(ContextCompat.getColor(activity, R.color.dialog_on_surface),
                        popup.getCurrentTextColor());
                assertNotNull(popup.getBackground());

                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) popup.getLayoutParams();
                assertEquals(Gravity.BOTTOM, params.gravity & Gravity.VERTICAL_GRAVITY_MASK);
                // END is an absolute-layout bit outside HORIZONTAL_GRAVITY_MASK;
                // assert against the raw gravity and reject the old center anchor.
                assertEquals(Gravity.END, params.gravity & Gravity.END);
                assertNotEquals(Gravity.CENTER_HORIZONTAL,
                        params.gravity & Gravity.HORIZONTAL_GRAVITY_MASK);
                assertTrue(popup.getY() + popup.getHeight() / 2f > decor.getHeight() / 2f);
            });

            scenario.recreate();
            // The toast interceptor may surface a stray device/service toast during
            // recreation as a new popup; dismiss any of those before asserting that
            // the original popup did not leak across the recreate.
            scenario.onActivity(activity -> PopupToast.get().dismiss());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View popup = activity.getWindow().getDecorView().findViewById(R.id.notify_popup);
                assertNull(popup);
            });
        }
    }

    @Test
    public void providerProcessNoticeIsRelayedToBottomPopup() {
        try (ActivityScenario<CloudStorageActivity> scenario =
                     ActivityScenario.launch(CloudStorageActivity.class)) {
            scenario.onActivity(activity -> {
                PopupToast.get().dismiss();
                activity.sendBroadcast(SystemToastInterceptor.providerNoticeIntent(MESSAGE));
            });
            // Broadcast delivery crosses Binder; main-looper idleness alone
            // does not establish that the receiver has run.
            java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
            long deadline = android.os.SystemClock.uptimeMillis() + 2000;
            do {
                scenario.onActivity(activity -> {
                    TextView popup = activity.getWindow().getDecorView().findViewById(R.id.notify_popup);
                    delivered.set(popup != null && MESSAGE.contentEquals(popup.getText()));
                });
                if (!delivered.get()) android.os.SystemClock.sleep(25);
            } while (!delivered.get() && android.os.SystemClock.uptimeMillis() < deadline);
            assertTrue("Provider broadcast was not displayed", delivered.get());
            scenario.onActivity(activity -> {
                TextView popup = activity.getWindow().getDecorView().findViewById(R.id.notify_popup);
                assertNotNull(popup);
                assertEquals(MESSAGE, popup.getText().toString());
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) popup.getLayoutParams();
                assertEquals(Gravity.BOTTOM, params.gravity & Gravity.VERTICAL_GRAVITY_MASK);
            });
        }
    }

    @Test
    public void consecutiveNoticesRemainReadableInOrder() {
        try (ActivityScenario<CloudStorageActivity> scenario =
                     ActivityScenario.launch(CloudStorageActivity.class)) {
            scenario.onActivity(activity -> {
                PopupToast.get().dismiss();
                Notify.show("First site notice");
                Notify.show("Second site notice");
                TextView popup = activity.getWindow().getDecorView().findViewById(R.id.notify_popup);
                assertEquals("First site notice", popup.getText().toString());
            });
            java.util.concurrent.atomic.AtomicBoolean secondShown = new java.util.concurrent.atomic.AtomicBoolean();
            long deadline = android.os.SystemClock.uptimeMillis() + 6000;
            do {
                scenario.onActivity(activity -> {
                    TextView popup = activity.getWindow().getDecorView().findViewById(R.id.notify_popup);
                    secondShown.set(popup != null && "Second site notice".contentEquals(popup.getText()));
                });
                if (!secondShown.get()) android.os.SystemClock.sleep(50);
            } while (!secondShown.get() && android.os.SystemClock.uptimeMillis() < deadline);
            assertTrue("Second notice must appear after the first finishes", secondShown.get());
            scenario.onActivity(activity -> PopupToast.get().dismiss());
        }
    }

    @Test
    public void providerNoticeDestinationUsesApplicationIdentity() {
        android.content.Intent notice = SystemToastInterceptor.providerNoticeIntent(MESSAGE);
        assertEquals(com.wuming.android.tv.BuildConfig.APPLICATION_ID, notice.getPackage());
        assertEquals(com.wuming.android.tv.BuildConfig.APPLICATION_ID + ".PROVIDER_NOTICE", notice.getAction());
        assertEquals(MESSAGE, notice.getStringExtra("text"));
    }

    @Test
    public void mDriveUcLoginToastExplainsOpenDriveFallback() {
        try (ActivityScenario<CloudStorageActivity> scenario =
                     ActivityScenario.launch(CloudStorageActivity.class)) {
            scenario.onActivity(activity -> {
                PopupToast.get().dismiss();
                Toast.makeText(activity, PROVIDER_LOGIN_MESSAGE, Toast.LENGTH_LONG).show();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            scenario.onActivity(activity -> {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                TextView popup = decor.findViewById(R.id.notify_popup);
                assertNotNull(popup);
                assertEquals(activity.getString(R.string.opendrive_mdrive_uc_fallback),
                        popup.getText().toString());
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) popup.getLayoutParams();
                assertEquals(Gravity.BOTTOM, params.gravity & Gravity.VERTICAL_GRAVITY_MASK);
                assertTrue(popup.getY() + popup.getHeight() / 2f > decor.getHeight() / 2f);
            });
        }
    }
}
