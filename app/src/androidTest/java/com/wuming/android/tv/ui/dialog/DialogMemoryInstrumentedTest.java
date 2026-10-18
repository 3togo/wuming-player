package com.wuming.android.tv.ui.dialog;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.viewbinding.ViewBinding;
import com.wuming.android.tv.ui.base.BaseFragment;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.impl.ConfigCallback;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.MediaMetadata;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.BuildConfig;
import com.wuming.android.tv.api.DanmakuApi;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.setting.DanmakuSetting;
import com.wuming.android.tv.ui.adapter.DanmakuAdapter;
import com.wuming.android.tv.cloud.CloudStorageProvider;
import com.wuming.tv.core.cloud.OpenDriveLoginChallenge;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

@RunWith(AndroidJUnit4.class)
public class DialogMemoryInstrumentedTest {
    @Test public void subtitleDialogReleasesPlayerViewAndRejectsRestorationWithoutIt() throws Exception {
        try (ActivityScenario<Activity> scenario = launch()) {
            AtomicReference<SubtitleDialog> reference = new AtomicReference<>();
            scenario.onActivity(activity -> {
                FragmentActivity host = (FragmentActivity) activity;
                SubtitleDialog dialog = SubtitleDialog.create().view(new androidx.media3.ui.SubtitleView(host));
                reference.set(dialog);
                dialog.showNow(host.getSupportFragmentManager(), "subtitle-memory");
                dialog.dismissNow();
                assertNull(get(dialog, "subtitleView"));
                assertNull(get(dialog, "binding"));
                new SubtitleDialog().showNow(host.getSupportFragmentManager(), "restored-subtitle");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertNull(((FragmentActivity) activity)
                    .getSupportFragmentManager().findFragmentByTag("restored-subtitle")));
        }
    }

    @Test public void filterDialogRestoresArgumentsAndReconnectsToRestoredParent() {
        try (ActivityScenario<Activity> scenario = launch()) {
            scenario.onActivity(activity -> {
                FragmentActivity host = (FragmentActivity) activity;
                FilterOwner owner = new FilterOwner();
                host.getSupportFragmentManager().beginTransaction()
                        .add(android.R.id.custom, owner, "filter-owner").commitNow();
                FilterDialog.create().filter(java.util.List.of(new com.wuming.android.tv.bean.Filter()))
                        .showNow(owner.getChildFragmentManager(), "filter-restore");
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                FragmentActivity host = (FragmentActivity) activity;
                FilterOwner owner = (FilterOwner) host.getSupportFragmentManager().findFragmentByTag("filter-owner");
                FilterDialog dialog = (FilterDialog) owner.getChildFragmentManager().findFragmentByTag("filter-restore");
                assertNotNull(dialog);
                assertSame(owner, get(dialog, "callback"));
                com.wuming.android.tv.databinding.DialogFilterBinding binding =
                        (com.wuming.android.tv.databinding.DialogFilterBinding) get(dialog, "binding");
                assertEquals(1, binding.recycler.getAdapter().getItemCount());
                dialog.dismissNow();
                assertNull(get(dialog, "callback"));
                assertNull(get(dialog, "filter"));
                assertNull(get(dialog, "binding"));
                new FilterDialog().showNow(host.getSupportFragmentManager(), "missing-filter");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> assertNull(((FragmentActivity) activity)
                    .getSupportFragmentManager().findFragmentByTag("missing-filter")));
        }
    }

    public static class FilterOwner extends BaseFragment implements com.wuming.android.tv.impl.FilterCallback {
        @Override public void setFilter(String key, com.wuming.android.tv.bean.Value value) {}
        @Override protected ViewBinding getBinding(LayoutInflater inflater, ViewGroup container) {
            View view = new View(inflater.getContext());
            return () -> view;
        }
        @Override protected void releaseBinding() {}
    }

    @Test public void bilibiliHostRecreationCancelsHttpAndDisposesLateQr() throws Exception {
        try (Fixture server = new Fixture(); ActivityScenario<Activity> scenario = launch()) {
            AtomicReference<BilibiliLoginDialog> controller = new AtomicReference<>();
            AtomicReference<Call> pending = new AtomicReference<>();
            AtomicInteger callbacks = new AtomicInteger();
            scenario.onActivity(activity -> {
                BilibiliLoginDialog dialog = new BilibiliLoginDialog(activity,
                        callbacks::incrementAndGet, callbacks::incrementAndGet,
                        server.url(), server.url());
                controller.set(dialog);
                dialog.show();
                // Also starts generation if isolated test cookies already indicate a login.
                if (get(dialog, "call") == null) invoke(dialog, "generate");
                pending.set((Call) get(dialog, "call"));
            });
            assertTrue(server.entered.await(20, TimeUnit.SECONDS));
            scenario.recreate();
            assertTrue(pending.get().isCanceled());
            scenario.onActivity(activity -> {
                BilibiliLoginDialog dialog = controller.get();
                assertNull(get(dialog, "dialog"));
                assertNull(get(dialog, "qrView"));
                assertNull(get(dialog, "onSuccess"));
                assertNull(get(dialog, "onCancel"));
                assertNull(get(dialog, "task"));
                Bitmap late = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
                invoke(dialog, "onGenerated", new Class<?>[]{int.class, String.class, String.class, Bitmap.class},
                        -1, "late", "http://localhost/qr", late);
                assertTrue(late.isRecycled());
            });
            assertEquals(0, callbacks.get());
        }
    }

    @Test public void currentBilibiliQrIsDisplayedAndRecycledOnDismiss() throws Exception {
        try (Fixture server = new Fixture(); ActivityScenario<Activity> scenario = launch()) {
            server.release.countDown();
            AtomicReference<BilibiliLoginDialog> controller = new AtomicReference<>();
            scenario.onActivity(activity -> {
                BilibiliLoginDialog dialog = new BilibiliLoginDialog(activity, null, null,
                        server.url(), server.url());
                controller.set(dialog);
                dialog.show();
                if (get(dialog, "call") == null) invoke(dialog, "generate");
            });
            AtomicReference<Bitmap> bitmap = new AtomicReference<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            do {
                scenario.onActivity(activity -> bitmap.set((Bitmap) get(controller.get(), "qrBitmap")));
                if (bitmap.get() != null) break;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            assertNotNull(bitmap.get());
            assertFalse(bitmap.get().isRecycled());
            scenario.onActivity(activity -> controller.get().dismiss());
            assertTrue(bitmap.get().isRecycled());
        }
    }

    @Test public void bilibiliRefreshAndDismissCancelEveryGeneration() throws Exception {
        try (Fixture server = new Fixture(); ActivityScenario<Activity> scenario = launch()) {
            AtomicReference<BilibiliLoginDialog> controller = new AtomicReference<>();
            scenario.onActivity(activity -> {
                BilibiliLoginDialog dialog = new BilibiliLoginDialog(activity, null, null,
                        server.url(), server.url());
                controller.set(dialog);
                dialog.show();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                BilibiliLoginDialog dialog = controller.get();
                AlertDialog window = (AlertDialog) get(dialog, "dialog");
                for (int i = 0; i < 10; i++) {
                    Call previous = (Call) get(dialog, "call");
                    window.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    if (previous != null) assertTrue(previous.isCanceled());
                }
                Call last = (Call) get(dialog, "call");
                dialog.dismiss();
                dialog.dismiss();
                assertTrue(last.isCanceled());
                assertNull(get(dialog, "lifecycleCleanup"));
            });
        }
    }

    @Test public void queuedShowEventAfterImmediateDismissDoesNotRestartLogin() throws Exception {
        try (Fixture server = new Fixture(); ActivityScenario<Activity> scenario = launch()) {
            AtomicReference<BilibiliLoginDialog> controller = new AtomicReference<>();
            scenario.onActivity(activity -> {
                BilibiliLoginDialog dialog = new BilibiliLoginDialog(activity, null, null,
                        server.url(), server.url());
                controller.set(dialog);
                dialog.show();
                dialog.dismiss();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertNull(get(controller.get(), "call"));
            assertNull(get(controller.get(), "dialog"));
            assertEquals(1, server.entered.getCount());
        }
    }

    @Test public void danmakuReplacementAndTeardownRejectQueuedAndLateResponses() throws Exception {
        String previousUrl = DanmakuSetting.getApiUrl();
        try (Fixture server = new Fixture(); ActivityScenario<Activity> scenario = launch()) {
            DanmakuSetting.putApiUrl(server.url()); // POST path previously escaped global cancellation.
            AtomicReference<DanmakuSearchDialog> controller = new AtomicReference<>();
            scenario.onActivity(activity -> {
                PlayerManager player = new PlayerManager(null) {
                    @Override public MediaMetadata getMetadata() {
                        return new MediaMetadata.Builder().setTitle("fixture").setArtist("episode").build();
                    }
                };
                try {
                    FragmentActivity host = (FragmentActivity) activity;
                    DanmakuSearchDialog dialog = DanmakuSearchDialog.create().player(player);
                    dialog.showNow(host.getSupportFragmentManager(), "memory-search");
                    controller.set(dialog);
                    for (int i = 0; i < 10; i++) {
                        invoke(dialog, "search");
                        Object old = get(dialog, "request");
                        Call oldCall = (Call) get(old, "call");
                        TrackingBody queued = new TrackingBody();
                        respond(old, oldCall, queued); // Delivery queued on main.
                        invoke(dialog, "search");
                        assertTrue(oldCall.isCanceled());
                        assertTrue(queued.closed);
                        TrackingBody late = new TrackingBody();
                        respond(old, oldCall, late); // Response arriving after cancellation must close too.
                        assertTrue(late.closed);
                    }
                    Object current = get(dialog, "request");
                    Call currentCall = (Call) get(current, "call");
                    TrackingBody body = new TrackingBody();
                    respond(current, currentCall, body);
                    dialog.dismissNow();
                    assertTrue(currentCall.isCanceled());
                    assertTrue(body.closed);
                    assertNull(get(dialog, "binding"));
                    assertNull(get(dialog, "player"));
                    assertNull(get(dialog, "request"));
                } finally {
                    player.release();
                }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(0, ((DanmakuAdapter) get(controller.get(), "adapter")).getItemCount());
        } finally {
            DanmakuSetting.putApiUrl(previousUrl);
        }
    }

    @Test public void openDriveDestroyReleasesUiAndRecyclesLateChallenge() {
        try (ActivityScenario<Activity> scenario = launch()) {
            scenario.onActivity(activity -> {
                try {
                    Constructor<OpenDriveLoginDialog> constructor = OpenDriveLoginDialog.class
                            .getDeclaredConstructor(Activity.class, CloudStorageProvider.class, boolean.class, Runnable.class);
                    constructor.setAccessible(true);
                    OpenDriveLoginDialog dialog = constructor.newInstance(activity, CloudStorageProvider.ALIPAN, false, (Runnable) () -> {});
                    // Teardown moved from OpenDriveLoginDialog.destroy() to the inherited
                    // BaseQrLoginDialog.dismiss(); the QR page is built in code, so there is
                    // no binding to release any more.
                    dialog.dismiss();
                    dialog.dismiss();
                    assertNull(get(dialog, "qrView"));
                    assertNull(get(dialog, "statusView"));
                    assertNull(get(dialog, "onChanged"));
                    assertNull(get(dialog, "onChanged"));
                    Bitmap late = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
                    invoke(dialog, "challengeReady", new Class<?>[]{int.class, OpenDriveLoginChallenge.class, Bitmap.class},
                            0, null, late);
                    assertTrue(late.isRecycled());
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    @Test public void historyLoadCannotShowWindowAfterFragmentViewDestruction() {
        try (ActivityScenario<Activity> scenario = launch()) {
            AtomicReference<HistoryDialog> controller = new AtomicReference<>();
            scenario.onActivity(activity -> {
                FragmentActivity host = (FragmentActivity) activity;
                HistoryOwner fragment = new HistoryOwner();
                host.getSupportFragmentManager().beginTransaction()
                        .add(android.R.id.custom, fragment).commitNow();
                HistoryDialog dialog = HistoryDialog.create(fragment);
                controller.set(dialog);
                dialog.show();
                host.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
                assertNull(get(dialog, "dialog"));
                assertNull(get(dialog, "binding"));
                assertNull(get(dialog, "loadTask"));
                assertNull(get(dialog, "lifecycleCleanup"));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertNull(get(controller.get(), "dialog"));
        }
    }

    public static class HistoryOwner extends BaseFragment implements ConfigCallback {
        @Override public void setConfig(Config config) {}
        @Override protected ViewBinding getBinding(LayoutInflater inflater, ViewGroup container) {
            View view = new View(inflater.getContext());
            return () -> view;
        }
        @Override protected void releaseBinding() {}
    }

    @Test public void legacyDanmakuCancellationIncludesPostButLeavesOwnedRequestAlone() throws Exception {
        String previousUrl = DanmakuSetting.getApiUrl();
        try (Fixture server = new Fixture()) {
            DanmakuSetting.putApiUrl(server.url());
            Call legacy = DanmakuApi.newCall("fixture", "episode");
            Call owned = DanmakuApi.createCall("fixture", "episode");
            Callback ignore = new Callback() {
                @Override public void onFailure(Call call, java.io.IOException error) {}
                @Override public void onResponse(Call call, Response response) { response.close(); }
            };
            try {
                legacy.enqueue(ignore);
                owned.enqueue(ignore);
                assertTrue(server.entered.await(20, TimeUnit.SECONDS));
                DanmakuApi.cancel();
                assertTrue(legacy.isCanceled());
                assertFalse(owned.isCanceled());
            } finally {
                legacy.cancel();
                owned.cancel();
            }
        } finally {
            DanmakuSetting.putApiUrl(previousUrl);
        }
    }

    private static void respond(Object callback, Call call, TrackingBody body) {
        try {
            ((Callback) callback).onResponse(call, new Response.Builder().request(call.request())
                    .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build());
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    private static final class TrackingBody extends ResponseBody {
        private final Buffer source = new Buffer().writeUtf8("[{\"name\":\"fixture\",\"url\":\"http://localhost/danmaku\"}]");
        boolean closed;
        @Override public okhttp3.MediaType contentType() { return null; }
        @Override public long contentLength() { return -1; }
        @Override public BufferedSource source() { return source; }
        @Override public void close() { closed = true; super.close(); }
    }

    // Dialog state now lives in BaseQrLoginDialog, so look the whole hierarchy up.
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Keep walking up: the QR page state is owned by the base class.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object get(Object owner, String name) {
        try {
            Field found = field(owner.getClass(), name);
            found.setAccessible(true);
            return found.get(owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void invoke(Object owner, String name) { invoke(owner, name, new Class<?>[0]); }
    private static void invoke(Object owner, String name, Class<?>[] types, Object... values) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(owner, values);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static ActivityScenario<Activity> launch() {
        assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".verification"));
        return ActivityScenario.launch(new Intent().setClassName(ApplicationProvider.getApplicationContext(),
                "com.wuming.android.tv.ui.activity.MemoryTestActivity"));
    }

    private static final class Fixture extends NanoHTTPD implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Fixture() throws java.io.IOException { super(0); start(NanoHTTPD.SOCKET_READ_TIMEOUT, false); }
        String url() { return "http://127.0.0.1:" + getListeningPort() + "/fixture"; }
        @Override public Response serve(IHTTPSession session) {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return newFixedLengthResponse("{\"code\":0,\"data\":{\"qrcode_key\":\"fixture\",\"url\":\"http://localhost/qr\"}}");
        }
        @Override public void close() { release.countDown(); stop(); }
    }
}
