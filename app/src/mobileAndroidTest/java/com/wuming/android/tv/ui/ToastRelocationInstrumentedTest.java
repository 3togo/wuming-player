package com.wuming.android.tv.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.R;
import com.wuming.android.tv.ui.activity.HomeActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ToastRelocationInstrumentedTest {

    private static final String PROVIDER_NOTICE = "正在获取网盘登录信息…";

    @Test
    @SuppressWarnings("deprecation")
    public void customProviderToastIsRedirectedToBottomPopup() throws Exception {
        try (ActivityScenario<HomeActivity> scenario = TestActivityLauncher.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                com.wuming.android.tv.utils.PopupToast.get().dismiss();
                TextView text = new TextView(activity);
                text.setText(PROVIDER_NOTICE);
                Toast toast = new Toast(activity);
                toast.setView(text);
                toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 0);
                toast.setDuration(Toast.LENGTH_LONG);
                toast.show();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(250);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            scenario.onActivity(activity -> {
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                TextView popup = findPopup(decor);
                assertNotNull(popup);
                assertEquals(PROVIDER_NOTICE, popup.getText().toString());
                android.graphics.Rect visible = new android.graphics.Rect();
                assertTrue("Popup must be visible inside the window", popup.getGlobalVisibleRect(visible));
                assertEquals(popup.getHeight(), visible.height());
                assertEquals(popup.getWidth(), visible.width());
                int distanceFromBottom = Math.round(
                        decor.getHeight() - (popup.getY() + popup.getHeight()));
                assertTrue(distanceFromBottom >= 0);
                assertTrue(distanceFromBottom < decor.getHeight() / 4);
            });
            android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            java.io.File output = new java.io.File(InstrumentationRegistry.getInstrumentation()
                    .getTargetContext().getExternalFilesDir(null), "popup-placement.png");
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(output)) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream);
            } finally {
                screenshot.recycle();
            }
        }
    }

    @Test
    public void homeNavigationFitsItsMenuAboveSystemInset() {
        try (ActivityScenario<HomeActivity> scenario = TestActivityLauncher.launch(HomeActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ViewGroup navigation = activity.findViewById(R.id.navigation);
                View menu = navigation.getChildAt(0);
                assertTrue("Navigation menu must not be clipped at the top", menu.getTop() >= 0);
                assertTrue("Navigation menu must fit above its bottom inset",
                        menu.getBottom() <= navigation.getHeight() - navigation.getPaddingBottom());
                androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat
                        .getRootWindowInsets(navigation);
                assertNotNull(insets);
                assertEquals("Use the real system inset without a fixed extra gap",
                        insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom,
                        navigation.getPaddingBottom());
            });
        }
    }

    private static TextView findPopup(ViewGroup decor) {
        // PopupToast attaches its uniquely identified banner directly to the decor.
        for (int index = decor.getChildCount() - 1; index >= 0; index--) {
            View child = decor.getChildAt(index);
            if (child.getId() == R.id.notify_popup && child instanceof TextView) {
                return (TextView) child;
            }
        }
        return null;
    }
}
