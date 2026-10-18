package com.wuming.android.tv.ui;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.R;
import com.wuming.android.tv.ui.activity.RemoteTestActivity;
import com.wuming.android.tv.utils.TvRemoteMappings;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TvRemoteMappingsInstrumentedTest {
    @Test public void mappingPersistsAndPreservesEventMetadataWithoutChaining() {
        Context context = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("remote_mapping_test", mode);
            }
        };
        TvRemoteMappings mappings = new TvRemoteMappings(context);
        mappings.clear();
        try {
            mappings.assign(KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_1);
            mappings.assign(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_MENU);
            KeyEvent event = new KeyEvent(100, 150, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F1,
                    2, KeyEvent.META_SHIFT_ON, 4, 31, KeyEvent.FLAG_CANCELED, 0x301);
            KeyEvent result = new TvRemoteMappings(context).translate(event);
            assertEquals(KeyEvent.KEYCODE_1, result.getKeyCode());
            assertEquals(event.getAction(), result.getAction());
            assertEquals(event.getDownTime(), result.getDownTime());
            assertEquals(event.getEventTime(), result.getEventTime());
            assertEquals(event.getDeviceId(), result.getDeviceId());
            assertEquals(event.getScanCode(), result.getScanCode());
            assertEquals(event.getSource(), result.getSource());
            assertEquals(event.getMetaState(), result.getMetaState());
            assertEquals(2, result.getRepeatCount());
            assertTrue(result.isCanceled());
            mappings.remove(KeyEvent.KEYCODE_F1);
            assertSame(event, mappings.translate(event));
            for (int key : new int[]{KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_POWER}) {
                try { mappings.assign(key, KeyEvent.KEYCODE_MENU); fail("Reserved key accepted"); }
                catch (IllegalArgumentException expected) { }
            }
            mappings.clear();
            assertEquals(KeyEvent.KEYCODE_1, new TvRemoteMappings(context).target(KeyEvent.KEYCODE_1));
        } finally { mappings.clear(); }
    }

    @Test public void screenCapturesRawKeysAndBackEscapesCapture() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.setInTouchMode(false);
        try (ActivityScenario<RemoteTestActivity> scenario = TestActivityLauncher.launch(RemoteTestActivity.class)) {
            // launch() returns before the window is laid out and focused.
            settle();
            scenario.onActivity(activity ->
                    assertTrue(activity.findViewById(R.id.remoteTest).hasFocus()));
            // Drive the remote through the real input pipeline. A synthetic
            // Activity.dispatchKeyEvent() skips the D-pad focus navigation that ViewRootImpl
            // performs, so focus only moved by luck and every focus assertion after a key press
            // flaked under load.
            send(instrumentation, KeyEvent.KEYCODE_DPAD_CENTER); // Start capture.
            send(instrumentation, KeyEvent.KEYCODE_F1);
            settle();
            scenario.onActivity(activity -> {
                String report = ((TextView) activity.findViewById(R.id.remoteEvent)).getText().toString();
                assertTrue(report.contains("KEYCODE_F1"));
                assertTrue(report.contains("ACTION_DOWN"));
                assertTrue(report.contains("ACTION_UP"));
                assertTrue(activity.findViewById(R.id.remoteAssign).hasFocus());
            });
            send(instrumentation, KeyEvent.KEYCODE_DPAD_UP);
            settle();
            scenario.onActivity(activity ->
                    assertTrue(activity.findViewById(R.id.remoteTest).hasFocus()));
            send(instrumentation, KeyEvent.KEYCODE_DPAD_CENTER);
            send(instrumentation, KeyEvent.KEYCODE_BACK);
            settle();
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertTrue(activity.findViewById(R.id.remoteTest).hasFocus());
            });
            send(instrumentation, KeyEvent.KEYCODE_DPAD_DOWN);
            send(instrumentation, KeyEvent.KEYCODE_DPAD_DOWN);
            settle();
            scenario.onActivity(activity ->
                    assertTrue(activity.findViewById(R.id.remoteReset).hasFocus()));
            send(instrumentation, KeyEvent.KEYCODE_DPAD_DOWN);
            settle();
            scenario.onActivity(activity ->
                    assertTrue(activity.findViewById(R.id.remoteClose).hasFocus()));
        }
    }

    private static void settle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** Injects a key through the real input pipeline from the test thread. */
    private static void send(Instrumentation instrumentation, int key) {
        instrumentation.sendKeyDownUpSync(key);
    }
}
