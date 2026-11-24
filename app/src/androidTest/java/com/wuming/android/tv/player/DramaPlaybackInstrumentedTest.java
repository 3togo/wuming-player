package com.wuming.android.tv.player;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewGroup;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.wuming.android.tv.player.exo.MediaSourceFactory;
import com.wuming.android.tv.ui.activity.MemoryTestActivity;
import com.wuming.tv.core.playback.DramaVideoResolver;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in service/decoder test. No provider JAR, saved accounts, or cloud login is used. */
@RunWith(AndroidJUnit4.class)
public class DramaPlaybackInstrumentedTest {
    @Test public void nativeEpisodeDecodesAdvancesAndSeeks() throws Exception {
        String episode = InstrumentationRegistry.getArguments().getString("drama_episode");
        Assume.assumeTrue("Requires explicit live episode", episode != null);
        String site = InstrumentationRegistry.getArguments().getString("drama_site_key");
        com.wuming.android.tv.bean.Result playback;
        if (site != null) {
            int original = com.wuming.android.tv.setting.AppSettings.getLinkRoute();
            try {
                com.wuming.android.tv.api.config.VodConfig.get().ensureLoaded();
                com.wuming.android.tv.setting.AppSettings.putLinkRoute(com.wuming.android.tv.setting.AppSettings.LINK_ROUTE_BUILTIN);
                playback = com.wuming.android.tv.api.SiteApi.playerContent(site, "AI", episode);
            } finally { com.wuming.android.tv.setting.AppSettings.putLinkRoute(original); }
        } else {
            playback = new com.wuming.android.tv.bean.Result();
            playback.setUrl(new DramaVideoResolver().resolve(episode));
            playback.setFormat(MimeTypes.VIDEO_MP4);
        }
        assertTrue("Native route was not selected", com.wuming.tv.core.playback.CencPlaybackSessions.matches(playback.getRealUrl()));
        androidx.media3.common.MediaItem item = com.wuming.android.tv.player.exo.ExoUtil.getMediaItem(
            com.wuming.android.tv.player.engine.PlaySpec.from(playback, "native-drama-smoke", androidx.media3.common.MediaMetadata.EMPTY));
        AtomicReference<ExoPlayer> player = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        boolean[] frame = {false};
        long[] position = {0};
        try (ActivityScenario<MemoryTestActivity> scenario = ActivityScenario.launch(MemoryTestActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    PlayerView view = new PlayerView(activity);
                    ((ViewGroup) activity.findViewById(android.R.id.custom)).addView(view);
                    ExoPlayer engine = new ExoPlayer.Builder(activity).setMediaSourceFactory(new MediaSourceFactory()).build();
                    player.set(engine); view.setPlayer(engine);
                    engine.addListener(new Player.Listener() {
                        @Override public void onPlaybackStateChanged(int state) { android.util.Log.d("NativeCenc", "Test playback state=" + state); }
                        @Override public void onRenderedFirstFrame() { frame[0] = true; }
                        @Override public void onPlayerError(PlaybackException error) { failure.set(error.getErrorCodeName()); }
                    });
                    engine.setMediaItem(item);
                    engine.prepare(); engine.play();
                });
                long deadline = SystemClock.elapsedRealtime() + 45000;
                while (SystemClock.elapsedRealtime() < deadline && failure.get() == null) {
                    scenario.onActivity(activity -> position[0] = frame[0] ? player.get().getCurrentPosition() : 0);
                    if (position[0] >= 3000) break;
                    SystemClock.sleep(200);
                }
                assertNull("Native decoder error", failure.get());
                assertTrue("No decoded playback progress", position[0] >= 3000);
                scenario.onActivity(activity -> player.get().seekTo(15000));
                deadline = SystemClock.elapsedRealtime() + 20000;
                while (SystemClock.elapsedRealtime() < deadline && failure.get() == null) {
                    scenario.onActivity(activity -> position[0] = player.get().getCurrentPosition());
                    if (position[0] >= 16000 && player.get() != null) break;
                    SystemClock.sleep(200);
                }
                assertNull("Native seek error", failure.get());
                assertTrue("Seek did not resume", position[0] >= 16000);
                Bundle result = new Bundle(); result.putLong("nativeDramaPositionMs", position[0]);
                InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
            } finally { scenario.onActivity(activity -> { if (player.get() != null) player.get().release(); }); }
        }
    }
}
