package com.wuming.android.tv.service;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import java.lang.ref.WeakReference;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.CommandButton;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionError;

import com.wuming.android.tv.App;
import com.wuming.android.tv.BuildConfig;
import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.browse.BrowseTree;
import com.wuming.android.tv.event.ActionEvent;
import com.wuming.android.tv.event.ConfigEvent;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.player.engine.PlaySpec;
import com.wuming.android.tv.server.Server;
import com.wuming.android.tv.utils.Task;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class PlaybackService extends MediaLibraryService implements MediaLibrarySession.Callback, PlayerManager.Callback {

    public static final String LOCAL_BIND_ACTION = BuildConfig.APPLICATION_ID.concat(".LOCAL_BIND");

    private static volatile boolean running;

    private final List<PlayerCallback> playerCallbacks = new CopyOnWriteArrayList<>();
    private final LocalBinder binder = new LocalBinder(this);

    private NavigationCallback navigationCallback;
    private MediaLibrarySession session;
    private Runnable onNewBinding;
    private boolean externalBound;
    private volatile PlayerManager player;
    private String navigationKey;
    private volatile Player exoPlayer;

    public static boolean isRunning() {
        return running;
    }

    public void replaceBinding(Runnable callback) {
        if (onNewBinding != null) onNewBinding.run();
        onNewBinding = callback;
    }

    public PlayerManager player() {
        return player;
    }

    private boolean hasNavigationCallback() {
        return navigationCallback != null;
    }

    /**
     * Remote control arrives on the NanoHTTPD thread and is posted to the main thread, so it can
     * land while onDestroy() is releasing the player. Every entry point reachable from a request
     * has to re-check before touching the player.
     */
    private boolean usable() {
        return player != null && exoPlayer != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        player = new PlayerManager(this);
        exoPlayer = player.getPlayer();
        exoPlayer.addListener(listener);
        session = new MediaLibrarySession.Builder(this, wrap(exoPlayer), this).setBitmapLoader(PlaybackArtworkLoader.create(this)).build();
        session.setSessionActivity(buildDefaultIntent());
        EventBus.getDefault().register(this);
        Server.get().setService(this);
        setupNotification();
    }

    private PendingIntent buildDefaultIntent() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent == null) intent = new Intent();
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void setupNotification() {
        DefaultMediaNotificationProvider provider = new DefaultMediaNotificationProvider.Builder(this).build();
        session.setMediaButtonPreferences(ImmutableList.of(buildStopButton()));
        provider.setSmallIcon(R.drawable.ic_notification);
        setMediaNotificationProvider(provider);
    }

    private CommandButton buildStopButton() {
        return new CommandButton.Builder(CommandButton.ICON_STOP).setPlayerCommand(Player.COMMAND_STOP).setDisplayName(getString(androidx.media3.ui.R.string.exo_controls_stop_description)).build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) handleAction(intent.getAction());
        return super.onStartCommand(intent, flags, startId);
    }

    private void handleAction(String action) {
        // Notification actions can arrive while onDestroy() is releasing the manager.
        PlayerManager manager = player;
        if (manager == null) return;
        if (ActionEvent.PLAY.equals(action)) manager.play();
        else if (ActionEvent.PAUSE.equals(action)) manager.pause();
        else if (ActionEvent.PREV.equals(action)) dispatchPrev();
        else if (ActionEvent.NEXT.equals(action)) dispatchNext();
        else if (ActionEvent.STOP.equals(action)) dispatchStop();
        else if (ActionEvent.LOOP.equals(action)) dispatchLoop();
        else if (ActionEvent.AUDIO.equals(action)) dispatchAudio();
        else if (ActionEvent.REPLAY.equals(action)) dispatchReplay();
    }

    private boolean isLocalBind(Intent intent) {
        return LOCAL_BIND_ACTION.equals(intent != null ? intent.getAction() : null);
    }

    private boolean isExternalBind(Intent intent) {
        return "android.media.browse.MediaBrowserService".equals(intent != null ? intent.getAction() : null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (isLocalBind(intent)) return binder;
        if (isExternalBind(intent)) externalBound = true;
        return super.onBind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (isExternalBind(intent)) releaseExternal();
        if (isLocalBind(intent)) tryShutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        tryShutdown();
    }

    @Override
    public void onDisconnected(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller) {
        if (controller.getPackageName().equals(getPackageName())) return;
        tryShutdown();
    }

    @Override
    public void onDestroy() {
        // Detach first: a remote request in flight already holds a reference to this service, and
        // clearing the server ref early keeps new requests from starting work on a dying player.
        Server.get().setService(null);
        binder.clear();
        running = false;
        playerCallbacks.clear();
        navigationCallback = null;
        navigationKey = null;
        onNewBinding = null;
        externalBound = false;
        if (exoPlayer != null) exoPlayer.removeListener(listener);
        releaseSession();
        if (player != null) player.release();
        player = null;
        exoPlayer = null;
        removeForeground();
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    private void stopAndClear() {
        PlayerManager manager = player;
        Player current = exoPlayer;
        if (manager == null || current == null) return;
        manager.stop();
        current.clearMediaItems();
    }

    public void suspend() {
        stopAndClear();
        removeForeground();
    }

    public void shutdown() {
        if (!running) return;
        running = false;
        stopAndClear();
        stopSelf();
    }

    private void tryShutdown() {
        if (!hasNavigationCallback() && !hasExternalClient()) shutdown();
    }

    private void releaseExternal() {
        externalBound = false;
        saveProgress();
        BrowseTree.clear();
        tryShutdown();
    }

    private void releaseSession() {
        if (session == null) return;
        session.release();
        session = null;
    }

    private void removeForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void saveProgress() {
        MediaLibrarySession current = session;
        Player currentPlayer = exoPlayer;
        if (hasNavigationCallback() || current == null || currentPlayer == null) return;
        if (BrowseTree.saveProgress(currentPlayer.getCurrentPosition(), currentPlayer.getDuration())) {
            current.notifyChildrenChanged("VOD", 0, null);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (session == null) return;
        if (event.isVod()) {
            BrowseTree.clearVod();
            session.notifyChildrenChanged("VOD", 0, null);
        } else if (event.isLive()) {
            BrowseTree.clearLive();
            session.notifyChildrenChanged("LIVE", 0, null);
        }
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @NonNull
    @Override
    public MediaSession.ConnectionResult onConnect(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller) {
        return new MediaLibrarySession.ConnectionResult.AcceptedResultBuilder(session).build();
    }

    public boolean hasExternalClient() {
        return externalBound;
    }

    public void setSessionActivity(PendingIntent pendingIntent) {
        if (session != null) session.setSessionActivity(pendingIntent);
    }

    public void resetSessionActivity() {
        setSessionActivity(buildDefaultIntent());
    }

    public void setNavigationCallback(NavigationCallback navigationCallback, String key) {
        this.navigationCallback = navigationCallback;
        this.navigationKey = key;
    }

    private boolean isNavigationOwner() {
        return navigationKey == null || navigationKey.equals(player.getKey());
    }

    public void addPlayerCallback(PlayerCallback callback) {
        playerCallbacks.add(callback);
    }

    public void removePlayerCallback(PlayerCallback callback) {
        playerCallbacks.remove(callback);
    }

    public boolean hasPlayerCallback() {
        return !playerCallbacks.isEmpty();
    }

    public void dispatchPrev() {
        dispatchNavigate(NavigationCallback::onPrev, -1);
    }

    public void dispatchNext() {
        dispatchNavigate(NavigationCallback::onNext, 1);
    }

    private void dispatchNavigate(Consumer<NavigationCallback> action, int delta) {
        if (!usable()) return;
        if (hasNavigationCallback() && isNavigationOwner()) dispatch(action);
        else navigateItem(delta);
    }

    public void dispatchStop() {
        if (!usable()) return;
        if (exoPlayer.getPlaybackState() == Player.STATE_IDLE) return;
        if (hasNavigationCallback() && isNavigationOwner()) dispatch(NavigationCallback::onStop);
        else stopAndClear();
    }

    public void dispatchLoop() {
        dispatch(NavigationCallback::onLoop);
    }

    public void dispatchReplay() {
        if (hasNavigationCallback() && isNavigationOwner()) dispatch(NavigationCallback::onReplay);
        else if (usable()) {
            exoPlayer.seekTo(0);
            exoPlayer.play();
        }
    }

    public void dispatchAudio() {
        dispatch(NavigationCallback::onAudio);
    }

    private void dispatch(Consumer<NavigationCallback> action) {
        NavigationCallback callback = navigationCallback;
        if (callback != null) App.post(() -> action.accept(callback));
    }

    private void navigateItem(int delta) {
        if (!usable()) return;
        Player current = exoPlayer;
        MediaItem item = current == null ? null : current.getCurrentMediaItem();
        if (item == null) return;
        Task.submit(() -> {
            try {
                MediaItem next = BrowseTree.navigate(item.mediaId, delta);
                if (next == null || next.localConfiguration == null) return;
                Result result = BrowseTree.consumeBrowseResult(next.mediaId);
                PlayerManager manager = player;
                if (result == null || manager == null || !isRunning()) return;
                App.post(() -> {
                    // The posted runnable can outlive teardown; onDestroy() nulls the field.
                    if (player != manager || !isRunning()) return;
                    startBrowse(manager, next, result, 0);
                });
            } catch (Exception ignored) {
            }
        });
    }

    private boolean isSameItem(MediaItem item) {
        if (item == null || item.localConfiguration == null || !usable()) return false;
        return item.localConfiguration.uri.toString().equals(player.getUrl());
    }

    private void interceptItem(@NonNull MediaItem item, long startPositionMs) {
        if (isSameItem(item)) return;
        playViaManager(item, startPositionMs);
    }

    private void interceptItems(@NonNull List<MediaItem> items, int startIndex, long startPositionMs) {
        if (items.isEmpty()) return;
        int idx = (startIndex >= 0 && startIndex < items.size()) ? startIndex : 0;
        interceptItem(items.get(idx), startPositionMs > 0 ? startPositionMs : 0);
    }

    private ForwardingPlayer wrap(Player base) {
        return new ForwardingPlayer(base) {
            @Override
            public void setMediaItem(@NonNull MediaItem item) {
                interceptItem(item, 0);
            }

            @Override
            public void setMediaItem(@NonNull MediaItem item, boolean resetPosition) {
                interceptItem(item, 0);
            }

            @Override
            public void setMediaItem(@NonNull MediaItem item, long startPositionMs) {
                interceptItem(item, startPositionMs);
            }

            @Override
            public void setMediaItems(@NonNull List<MediaItem> items) {
                interceptItems(items, 0, 0);
            }

            @Override
            public void setMediaItems(@NonNull List<MediaItem> items, boolean resetPosition) {
                interceptItems(items, 0, 0);
            }

            @Override
            public void setMediaItems(@NonNull List<MediaItem> items, int startIndex, long startPositionMs) {
                interceptItems(items, startIndex, startPositionMs);
            }

            @Override
            public void seekToPrevious() {
                dispatchPrev();
            }

            @Override
            public void seekToPreviousMediaItem() {
                dispatchPrev();
            }

            @Override
            public void seekToNext() {
                dispatchNext();
            }

            @Override
            public void seekToNextMediaItem() {
                dispatchNext();
            }

            @Override
            public void stop() {
                dispatchStop();
            }

            @NonNull
            @Override
            public Commands getAvailableCommands() {
                return super.getAvailableCommands().buildUpon().add(COMMAND_SEEK_TO_PREVIOUS).add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(COMMAND_SEEK_BACK).add(COMMAND_SEEK_FORWARD).add(COMMAND_STOP).build();
            }
        };
    }

    private void playViaManager(MediaItem item, long startPositionMs) {
        if (item == null || item.localConfiguration == null) return;
        PlayerManager manager = player;
        if (manager == null) return;
        Result result = BrowseTree.consumeBrowseResult(item.mediaId);
        if (result != null) startBrowse(manager, item, result, startPositionMs);
    }

    private void startBrowse(PlayerManager manager, MediaItem item, Result result, long startPositionMs) {
        manager.startBrowse(PlaySpec.from(result, item.mediaId, item.mediaMetadata));
        if (startPositionMs > 0) manager.seekTo(startPositionMs);
    }

    @Override
    public void onPrepare() {
        playerCallbacks.forEach(PlayerCallback::onPrepare);
    }

    @Override
    public void onTracksChanged() {
        playerCallbacks.forEach(PlayerCallback::onTracksChanged);
    }

    @Override
    public void onTitlesChanged() {
        playerCallbacks.forEach(PlayerCallback::onTitlesChanged);
    }

    @Override
    public void onError(String msg) {
        playerCallbacks.forEach(callback -> callback.onError(msg));
    }

    @Override
    public void onPlayerRebuild(Player newPlayer) {
        if (newPlayer == null) return;
        Player previous = exoPlayer;
        if (previous != null) previous.removeListener(listener);
        exoPlayer = newPlayer;
        newPlayer.addListener(listener);
        MediaLibrarySession current = session;
        if (current != null) current.setPlayer(wrap(newPlayer));
        playerCallbacks.forEach(callback -> callback.onPlayerRebuild(newPlayer));
    }

    private final Player.Listener listener = new ServicePlayerListener(this);

    private static class ServicePlayerListener implements Player.Listener {
        private final WeakReference<PlaybackService> serviceRef;

        ServicePlayerListener(PlaybackService service) {
            this.serviceRef = new WeakReference<>(service);
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            PlaybackService service = serviceRef.get();
            if (service == null) return;
            if (state == Player.STATE_ENDED && !(service.hasNavigationCallback() && service.isNavigationOwner())) service.navigateItem(1);
        }
    }

    @NonNull
    @Override
    public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @Nullable MediaLibraryService.LibraryParams params) {
        return Futures.immediateFuture(LibraryResult.ofItem(BrowseTree.getRootItem(), params));
    }

    @NonNull
    @Override
    public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @NonNull String parentId, int page, int pageSize, @Nullable MediaLibraryService.LibraryParams params) {
        return Task.executor().submit(() -> LibraryResult.ofItemList(BrowseTree.getChildren(parentId), params));
    }

    @NonNull
    @Override
    public ListenableFuture<LibraryResult<Void>> onSearch(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @NonNull String query, @Nullable MediaLibraryService.LibraryParams params) {
        Task.execute(() -> {
            ImmutableList<MediaItem> results = BrowseTree.search(query);
            // Read the owning session, not the callback parameter: teardown clears
            // the field and a released session must not be notified.
            MediaLibrarySession current = this.session;
            if (current == null) return;
            App.post(() -> {
                if (this.session != current) return;
                current.notifySearchResultChanged(browser, query, results.size(), params);
            });
        });
        return Futures.immediateFuture(LibraryResult.ofVoid(params));
    }

    @NonNull
    @Override
    public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @NonNull String query, int page, int pageSize, @Nullable MediaLibraryService.LibraryParams params) {
        return Futures.immediateFuture(LibraryResult.ofItemList(BrowseTree.getSearchResult(), params));
    }

    @NonNull
    @Override
    public ListenableFuture<LibraryResult<MediaItem>> onGetItem(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @NonNull String mediaId) {
        MediaItem item = BrowseTree.getItem(mediaId);
        return Futures.immediateFuture(item != null ? LibraryResult.ofItem(item, null) : LibraryResult.ofError(SessionError.ERROR_BAD_VALUE));
    }

    @NonNull
    @Override
    public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller, @NonNull List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        saveProgress();
        return Task.executor().submit(() -> {
            List<MediaItem> resolved = mediaItems.stream().map(BrowseTree::resolveOrKeep).toList();
            int index = Math.max(startIndex, 0);
            long position = BrowseTree.consumeResumePosition();
            return new MediaSession.MediaItemsWithStartPosition(resolved, index, position);
        });
    }

    public interface PlayerCallback {

        default void onPrepare() {
        }

        default void onTracksChanged() {
        }

        default void onTitlesChanged() {
        }

        default void onError(String msg) {
        }

        default void onPlayerRebuild(Player player) {
        }
    }

    public interface NavigationCallback {

        default void onPrev() {
        }

        default void onNext() {
        }

        default void onStop() {
        }

        default void onLoop() {
        }

        default void onReplay() {
        }

        default void onAudio() {
        }
    }

    public static final class LocalBinder extends Binder {
        private final WeakReference<PlaybackService> service;

        LocalBinder(PlaybackService service) {
            this.service = new WeakReference<>(service);
        }

        public PlaybackService getService() {
            return service.get();
        }

        private void clear() {
            service.clear();
        }
    }
}
