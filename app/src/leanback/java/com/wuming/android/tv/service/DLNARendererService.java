package com.wuming.android.tv.service;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.media3.common.Player;

import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.wuming.android.tv.dlna.CastAction;
import com.wuming.android.tv.dlna.DLNAAvTransportImpl;
import com.wuming.android.tv.dlna.DLNARenderingControlImpl;
import com.wuming.android.tv.dlna.DLNAServiceConfiguration;
import com.wuming.android.tv.dlna.RenderState;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.utils.Notify;

import org.fourthline.cling.UpnpServiceConfiguration;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder;
import org.fourthline.cling.model.meta.DeviceDetails;
import org.fourthline.cling.model.meta.DeviceIdentity;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.ManufacturerDetails;
import org.fourthline.cling.model.meta.ModelDetails;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDN;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.fourthline.cling.support.connectionmanager.ConnectionManagerService;
import org.fourthline.cling.support.lastchange.LastChangeAwareServiceManager;
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlLastChangeParser;

import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import java.util.UUID;

public class DLNARendererService extends AndroidUpnpServiceImpl implements ServiceConnection {

    private final LocalBinder binder = new LocalBinder(this);

    private volatile PlayerManager player;
    private volatile boolean isDlnaActive;

    private DLNARenderingControlImpl renderingControlImpl;
    private DLNAAvTransportImpl avTransportImpl;
    private PlaybackService playbackService;
    private Player currentListenerPlayer;
    private boolean bound;

    public static void start(Context context) {
        context.startService(new Intent(context, DLNARendererService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, DLNARendererService.class));
    }

    @Override
    protected UpnpServiceConfiguration createConfiguration() {
        return new DLNAServiceConfiguration();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notification notification = new NotificationCompat.Builder(this, Notify.DEFAULT).setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.app_name)).setSilent(true).build();
        startForeground(Notify.ID + 1, notification);
        registerLocalDevice();
    }

    private void registerLocalDevice() {
        LocalService<DLNAAvTransportImpl> avTransport = createAvTransport();
        LocalService<ConnectionManagerService> connManager = createConnectionManager();
        LocalService<DLNARenderingControlImpl> renderControl = createRenderingControl();
        DeviceIdentity identity = new DeviceIdentity(new UDN(UUID.nameUUIDFromBytes((Build.MANUFACTURER + Build.MODEL + "-MediaRenderer").getBytes(StandardCharsets.UTF_8))));
        UDADeviceType type = new UDADeviceType("MediaRenderer", 1);
        DeviceDetails details = new DeviceDetails(com.wuming.android.tv.setting.DlnaSettings.getName(), new ManufacturerDetails(Build.MANUFACTURER), new ModelDetails(Build.MODEL, com.wuming.tv.core.playback.renderer.RendererPairingHint.MODEL_DESCRIPTION, "1.0"));
        try {
            LocalDevice device = new LocalDevice(identity, type, details, new LocalService[]{avTransport, connManager, renderControl});
            upnpService.getRegistry().addDevice(device);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private LocalService<DLNAAvTransportImpl> createAvTransport() {
        avTransportImpl = new DLNAAvTransportImpl(this);
        LocalService<DLNAAvTransportImpl> service = new AnnotationLocalServiceBinder().read(DLNAAvTransportImpl.class);
        service.setManager(new LastChangeAwareServiceManager<>(service, new AVTransportLastChangeParser()) {
            @Override
            protected DLNAAvTransportImpl createServiceInstance() {
                return avTransportImpl;
            }
        });
        return service;
    }

    private LocalService<ConnectionManagerService> createConnectionManager() {
        return com.wuming.android.tv.dlna.RendererConnectionManager.create();
    }

    @SuppressWarnings("unchecked")
    private LocalService<DLNARenderingControlImpl> createRenderingControl() {
        renderingControlImpl = new DLNARenderingControlImpl(this);
        LocalService<DLNARenderingControlImpl> service = new AnnotationLocalServiceBinder().read(DLNARenderingControlImpl.class);
        service.setManager(new LastChangeAwareServiceManager<>(service, new RenderingControlLastChangeParser()) {
            @Override
            protected DLNARenderingControlImpl createServiceInstance() {
                return renderingControlImpl;
            }
        });
        return service;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        com.wuming.android.tv.server.DlnaPairing.SESSION.revoke();
        binder.clear();
        isDlnaActive = false;
        unbindPlaybackService();
        super.onDestroy();
    }

    private void bindPlaybackService() {
        if (bound) return;
        bound = bindService(new Intent(this, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND_ACTION), this, BIND_AUTO_CREATE);
    }

    private void cleanupPlaybackRefs() {
        App.removeCallbacks(positionUpdater);
        if (currentListenerPlayer != null) {
            currentListenerPlayer.removeListener(listener);
            currentListenerPlayer = null;
        }
        if (playbackService != null) {
            playbackService.removePlayerCallback(playerCallback);
            playbackService = null;
        }
        player = null;
        if (avTransportImpl != null) avTransportImpl.setPlayerManager(null);
    }

    private void unbindPlaybackService() {
        if (!bound) return;
        bound = false;
        cleanupPlaybackRefs();
        try {
            unbindService(this);
        } catch (IllegalArgumentException ignored) {
            // The system may have already dropped the connection after the
            // remote service process exited.
        }
    }

    public void setDlnaActive(boolean active) {
        isDlnaActive = active;
        if (avTransportImpl != null) avTransportImpl.setDlnaActive(active);
        if (active) bindPlaybackService();
        else {
            if (avTransportImpl != null) avTransportImpl.reset();
            unbindPlaybackService();
        }
    }

    public long consumePendingSeekMs() {
        return avTransportImpl != null ? avTransportImpl.consumePendingSeekMs() : -1;
    }

    public CastAction consumeNext() {
        return avTransportImpl != null ? avTransportImpl.popNext() : null;
    }

    public void notifyError() {
        if (avTransportImpl != null) avTransportImpl.fireStateChange(RenderState.STOPPED);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (!isDlnaActive) {
            unbindPlaybackService();
            return;
        }
        playbackService = ((PlaybackService.LocalBinder) binder).getService();
        if (playbackService == null) {
            unbindPlaybackService();
            return;
        }
        playbackService.addPlayerCallback(playerCallback);
        player = playbackService.player();
        avTransportImpl.setPlayerManager(player);
        currentListenerPlayer = player.getPlayer();
        currentListenerPlayer.addListener(listener);
        App.post(positionUpdater, 1000);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        cleanupPlaybackRefs();
    }

    private void notifyState() {
        if (avTransportImpl == null || player == null || !isDlnaActive) return;
        int state = player.getPlaybackState();
        if (state == Player.STATE_IDLE) return;
        long duration = player.getDuration();
        avTransportImpl.updatePositionCache(player.getPosition(), duration > 0 ? duration : -1);
        RenderState renderState = switch (state) {
            case Player.STATE_BUFFERING -> RenderState.PREPARING;
            case Player.STATE_READY -> player.isPlaying() ? RenderState.PLAYING : RenderState.PAUSED;
            case Player.STATE_ENDED -> avTransportImpl.hasNext() ? RenderState.PREPARING : RenderState.STOPPED;
            default -> null;
        };
        if (renderState != null) avTransportImpl.fireStateChange(renderState);
    }

    private static class PositionUpdaterRunnable implements Runnable {
        private final WeakReference<DLNARendererService> serviceRef;
        PositionUpdaterRunnable(DLNARendererService service) {
            this.serviceRef = new WeakReference<>(service);
        }
        @Override
        public void run() {
            DLNARendererService service = serviceRef.get();
            if (service == null) return;
            PlayerManager player = service.player;
            DLNAAvTransportImpl avTransport = service.avTransportImpl;
            if (player != null && avTransport != null && player.isPlaying()) {
                avTransport.updatePositionCache(player.getPosition(), player.getDuration());
            }
            if (service.player != null) App.post(this, 1000);
        }
    }

    private static class ExoPlayerListener implements Player.Listener {
        private final WeakReference<DLNARendererService> serviceRef;
        ExoPlayerListener(DLNARendererService service) {
            this.serviceRef = new WeakReference<>(service);
        }
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            DLNARendererService service = serviceRef.get();
            if (service != null) service.notifyState();
        }
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            DLNARendererService service = serviceRef.get();
            if (service != null) service.notifyState();
        }
    }

    private static class ServicePlayerCallback implements PlaybackService.PlayerCallback {
        private final WeakReference<DLNARendererService> serviceRef;
        ServicePlayerCallback(DLNARendererService service) {
            this.serviceRef = new WeakReference<>(service);
        }
        @Override
        public void onPlayerRebuild(Player newPlayer) {
            DLNARendererService service = serviceRef.get();
            if (service == null) return;
            if (service.currentListenerPlayer != null) {
                service.currentListenerPlayer.removeListener(service.listener);
            }
            service.currentListenerPlayer = newPlayer;
            newPlayer.addListener(service.listener);
        }
    }

    private final PositionUpdaterRunnable positionUpdater;
    private final ExoPlayerListener listener;
    private final ServicePlayerCallback playerCallback;

    public DLNARendererService() {
        positionUpdater = new PositionUpdaterRunnable(this);
        listener = new ExoPlayerListener(this);
        playerCallback = new ServicePlayerCallback(this);
    }

    public static final class LocalBinder extends android.os.Binder {
        private final WeakReference<DLNARendererService> service;

        LocalBinder(DLNARendererService service) {
            this.service = new WeakReference<>(service);
        }

        public DLNARendererService getService() {
            return service.get();
        }

        private void clear() {
            service.clear();
        }
    }
}
