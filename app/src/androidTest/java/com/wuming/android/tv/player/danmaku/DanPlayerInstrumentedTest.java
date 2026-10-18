package com.wuming.android.tv.player.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Player;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.App;
import com.wuming.android.tv.bean.Danmaku;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser;
import master.flame.danmaku.ui.widget.DanmakuView;

@RunWith(AndroidJUnit4.class)
public class DanPlayerInstrumentedTest {

    @Test
    public void releasedOverlayIgnoresLatePreparationAndSelection() {
        RecordingView[] view = new RecordingView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view[0] = new RecordingView();
            DanPlayer player = new DanPlayer(view[0]);
            player.release();
            player.prepared();
            player.setDanmaku(Danmaku.empty());
            player.attachPlayer(null);
            player.release();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(0, view[0].prepareCount);
        assertEquals(1, view[0].releaseCount);
    }

    @Test
    public void rebuildingDetachesTheListenerFromTheOldPlayer() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            DanPlayer player = new DanPlayer(new RecordingView());
            Set<Player.Listener> first = new HashSet<>();
            Set<Player.Listener> second = new HashSet<>();
            try {
                player.attachPlayer(fakePlayer(first));
                player.attachPlayer(fakePlayer(second));
                assertTrue(first.isEmpty());
                assertEquals(2, second.size());
            } finally {
                player.release();
            }
            assertTrue(second.isEmpty());
        });
    }

    private static Player fakePlayer(Set<Player.Listener> listeners) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "addListener": listeners.add((Player.Listener) args[0]); break;
                        case "removeListener": listeners.remove(args[0]); break;
                        case "getCurrentPosition": return 0L;
                        case "isPlaying": return false;
                        case "getPlaybackParameters": return androidx.media3.common.PlaybackParameters.DEFAULT;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                    return null;
                });
    }

    private static final class RecordingView extends DanmakuView {
        int prepareCount;
        int releaseCount;

        RecordingView() { super(App.get()); }
        @Override public void prepare(BaseDanmakuParser parser, DanmakuContext context) { prepareCount++; }
        @Override public void stop() {}
        @Override public void release() { releaseCount++; }
    }

}
