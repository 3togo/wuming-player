package com.wuming.android.tv.ui;

import static org.junit.Assert.*;

import android.content.ContextWrapper;
import android.view.KeyEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.ui.custom.CustomKeyDownLive;
import com.wuming.android.tv.utils.TvRemoteKeys;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class TvRemoteKeysInstrumentedTest {
    @Test public void shortcutsConsumeBothEdgesAndIgnoreRepeatsAndCancellation() {
        List<Integer> actions = new ArrayList<>();
        int[] keys = {KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_GREEN,
                KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_BLUE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD};
        for (int key : keys) {
            actions.clear();
            assertTrue(TvRemoteKeys.dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, key), actions::add));
            assertTrue(TvRemoteKeys.dispatch(new KeyEvent(0, 10, KeyEvent.ACTION_DOWN, key, 3), actions::add));
            assertTrue(actions.isEmpty());
            assertTrue(TvRemoteKeys.dispatch(new KeyEvent(KeyEvent.ACTION_UP, key), actions::add));
            assertEquals(1, actions.size());
            assertEquals(TvRemoteKeys.command(key), (int) actions.get(0));
            TvRemoteKeys.dispatch(new KeyEvent(0, 20, KeyEvent.ACTION_UP, key, 0, 0, -1, 0, KeyEvent.FLAG_CANCELED), actions::add);
            assertEquals(1, actions.size());
        }
        assertEquals(KeyEvent.KEYCODE_CAPTIONS, TvRemoteKeys.command(KeyEvent.KEYCODE_PROG_GREEN));
        assertEquals(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, TvRemoteKeys.command(KeyEvent.KEYCODE_PROG_YELLOW));
        assertFalse(TvRemoteKeys.dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP), actions::add));
        assertFalse(TvRemoteKeys.dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1), actions::add));
    }

    @Test public void digitsConfirmCancelAndDoNotLeakIntoVisibleControls() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Listener context = new Listener();
            CustomKeyDownLive keys = CustomKeyDownLive.create(context);
            try {
                press(keys, KeyEvent.KEYCODE_1);
                press(keys, KeyEvent.KEYCODE_NUMPAD_2);
                assertEquals("12", context.shown);
                press(keys, KeyEvent.KEYCODE_DPAD_CENTER);
                assertEquals("12", context.found);
                assertEquals(0, context.centers);
                press(keys, KeyEvent.KEYCODE_9);
                press(keys, KeyEvent.KEYCODE_BACK);
                assertEquals("", context.shown);
                assertFalse(keys.hasEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
                press(keys, KeyEvent.KEYCODE_DPAD_CENTER);
                assertEquals(1, context.centers);
                context.allowed = false;
                assertFalse(keys.onKeyDown(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1)));
                context.allowed = true;
                keys.onKeyDown(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
                keys.onKeyDown(new KeyEvent(0, 1000, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS));
                context.allowed = false;
                assertTrue(keys.onKeyDown(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)));
                assertEquals(1, context.centers);
                assertEquals(1, context.menus);
            } finally { keys.release(); }
        });
    }

    private static void press(CustomKeyDownLive keys, int code) {
        assertTrue(keys.onKeyDown(new KeyEvent(KeyEvent.ACTION_DOWN, code)));
        assertTrue(keys.onKeyDown(new KeyEvent(KeyEvent.ACTION_UP, code)));
    }
    private static final class Listener extends ContextWrapper implements CustomKeyDownLive.Listener {
        String shown = "", found = "";
        int centers, menus;
        boolean allowed = true;
        Listener() { super(InstrumentationRegistry.getInstrumentation().getTargetContext()); }
        public boolean dispatch(boolean check) { return allowed; }
        public void onShow(String number) { shown = number; }
        public void onFind(String number) { found = number; }
        public void onKeyCenter() { centers++; }
        public void onMenu() { menus++; }
        public void onSeeking(long time) {}
        public void onKeyUp() {}
        public void onKeyDown() {}
        public void onKeyLeft(long time) {}
        public void onKeyRight(long time) {}
        public void onSingleTap() {}
        public void onDoubleTap() {}
    }
}
