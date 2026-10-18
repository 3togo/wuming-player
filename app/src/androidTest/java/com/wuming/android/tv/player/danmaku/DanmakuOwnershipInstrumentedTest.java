package com.wuming.android.tv.player.danmaku;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.wuming.android.tv.bean.Danmaku;
import com.wuming.android.tv.player.PlayerManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import fi.iki.elonen.NanoHTTPD;
import master.flame.danmaku.ui.widget.DanmakuView;
import master.flame.danmaku.danmaku.model.DanmakuTimer;
import master.flame.danmaku.danmaku.model.android.AndroidDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@RunWith(AndroidJUnit4.class)
public class DanmakuOwnershipInstrumentedTest {
    @Test public void malformedFeedIsRejectedWithoutCreatingADataSource() throws Exception {
        try (Loader loader = new Loader()) {
            try {
                loader.load(Danmaku.from("not-a-uri"));
                fail("Malformed feed was accepted");
            } catch (IOException expected) {
                assertNull(loader.getDataSource());
            }
        }
    }

    @Test public void parserCanReadAfterHttpResponseCloses() throws Exception {
        try (Fixture server = new Fixture(false); Loader loader = new Loader()) {
            loader.load(Danmaku.from(server.url()));
            assertEquals(Fixture.XML, new String(loader.getDataSource().data().readAllBytes(), StandardCharsets.UTF_8));
            loader.close();
            assertNull(loader.getDataSource());
        }
    }

    @Test public void completedFeedProducesDanmakuAndParserReleasesItsSource() throws Exception {
        try (Fixture server = new Fixture(false); Loader loader = new Loader()) {
            loader.load(Danmaku.from(server.url()));
            AndroidDisplayer display = new AndroidDisplayer();
            display.setSize(1920, 1080);
            display.setDensities(1f, 160, 1f);
            Parser parser = new Parser();
            parser.setConfig(DanmakuContext.create()).setDisplayer(display)
                    .setTimer(new DanmakuTimer()).load(loader.getDataSource());
            assertEquals(1, parser.getDanmakus().size());
            assertNull(loader.getDataSource().data());
            parser.release();
        }
    }

    @Test public void cancelingOneFeedDoesNotCancelAnotherFeed() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Fixture first = new Fixture(true); Fixture second = new Fixture(true);
             Loader canceled = new Loader(); Loader active = new Loader()) {
            Future<?> old = workers.submit(() -> {
                try { canceled.load(Danmaku.from(first.url())); fail("Canceled request completed"); }
                catch (IOException expected) { }
            });
            Future<?> replacement = workers.submit(() -> {
                try { active.load(Danmaku.from(second.url())); }
                catch (IOException e) { throw new AssertionError(e); }
            });
            assertTrue(first.entered.await(20, TimeUnit.SECONDS));
            assertTrue(second.entered.await(20, TimeUnit.SECONDS));
            canceled.close();
            first.proceed.countDown();
            second.proceed.countDown();
            old.get(20, TimeUnit.SECONDS);
            replacement.get(20, TimeUnit.SECONDS);
            assertNull(canceled.getDataSource());
            assertNotNull(active.getDataSource());
        } finally { workers.shutdownNow(); }
    }

    @Test public void releasedOverlayCancelsRequestAndDropsViewAndSync() throws Exception {
        try (Fixture server = new Fixture(true); ActivityScenario<Activity> scenario = launch()) {
            AtomicReference<DanPlayer> overlay = new AtomicReference<>();
            AtomicReference<ExoPlayer> player = new AtomicReference<>();
            scenario.onActivity(activity -> {
                ExoPlayer engine = new ExoPlayer.Builder(activity).build();
                player.set(engine);
                DanPlayer current = new DanPlayer(new DanmakuView(activity));
                current.attachPlayer(engine);
                current.setDanmaku(Danmaku.from(server.url()));
                overlay.set(current);
            });
            try {
                assertTrue(server.entered.await(20, TimeUnit.SECONDS));
                scenario.onActivity(activity -> {
                    overlay.get().release();
                    overlay.get().release();
                    assertNull(field(overlay.get(), "view"));
                    assertNull(field(overlay.get(), "player"));
                    assertNull(field(overlay.get(), "sync"));
                    assertNull(field(overlay.get(), "job"));
                });
                server.proceed.countDown();
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            } finally {
                scenario.onActivity(activity -> player.get().release());
            }
        }
    }

    @Test public void oldScreenCannotClearReplacementOverlayAndSyncReadsOffMain() throws Exception {
        try (ActivityScenario<Activity> scenario = launch()) {
            AtomicReference<PlayerManager> manager = new AtomicReference<>();
            AtomicReference<Sync> sync = new AtomicReference<>();
            scenario.onActivity(activity -> {
                PlayerManager current = new PlayerManager(null);
                manager.set(current);
                DanmakuView old = new DanmakuView(activity);
                DanmakuView replacement = new DanmakuView(activity);
                current.setDanmakuView(old);
                current.setDanmakuView(replacement);
                current.clearDanmakuView(old);
                DanPlayer overlay = (DanPlayer) field(current, "danPlayer");
                assertTrue(overlay.ownsView(replacement));
                sync.set((Sync) field(overlay, "sync"));
                current.clearDanmakuView(replacement);
                assertNull(field(current, "danPlayer"));
                assertNotNull(current.getPlayer());
                assertEquals(Player.STATE_IDLE, current.getPlaybackState());
            });
            try {
                // ExoPlayer getters would throw here on the instrumentation/render thread.
                assertTrue(sync.get().getUptimeMillis() >= 0);
                assertNotNull(sync.get());
            } finally {
                scenario.onActivity(activity -> manager.get().release());
            }
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static ActivityScenario<Activity> launch() {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return ActivityScenario.launch(new Intent().setClassName(context,
                "com.wuming.android.tv.ui.activity.MemoryTestActivity"));
    }

    private static final class Fixture extends NanoHTTPD implements AutoCloseable {
        static final String XML = "<i><d p=\"1,1,25,16777215,0,0,user,0\">visible</d></i>";
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final boolean delayed;
        Fixture(boolean delayed) throws Exception {
            super("127.0.0.1", 0);
            this.delayed = delayed;
            start(SOCKET_READ_TIMEOUT, false);
        }
        String url() { return "http://127.0.0.1:" + getListeningPort() + "/danmaku.xml"; }
        @Override public Response serve(IHTTPSession session) {
            entered.countDown();
            if (delayed) try { proceed.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return newFixedLengthResponse(Response.Status.OK, "text/xml", XML);
        }
        @Override public void close() { proceed.countDown(); stop(); }
    }
}
