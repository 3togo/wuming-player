package com.wuming.android.tv.dlna;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.wuming.android.tv.App;
import com.wuming.android.tv.bean.Device;
import com.wuming.android.tv.service.DLNACastService;
import com.wuming.android.tv.utils.AppLog;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ControlPoint;
import org.fourthline.cling.model.message.header.STAllHeader;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.RemoteService;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.stream.Collectors;

public class DLNACastManager extends DefaultRegistryListener implements ServiceConnection {

    private static final String TAG = "DLNACastManager";
    private static final UDADeviceType RENDERER_TYPE = new UDADeviceType("MediaRenderer", 1);
    private static final UDAServiceType AVT_TYPE = new UDAServiceType("AVTransport", 1);

    private AndroidUpnpService upnpService;
    private WeakReference<DeviceListener> deviceListenerRef;
    private boolean bindRegistered = false;
    private boolean serviceBound = false;
    private boolean pendingSearch = false;

    public static DLNACastManager get() {
        return Loader.INSTANCE;
    }

    @Override
    public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
        AppLog.i(TAG, "remoteDeviceAdded: " + device.getDetails().getFriendlyName()
                + " type=" + device.getType().getType()
                + " implementsRenderer=" + device.getType().implementsVersion(RENDERER_TYPE));
        if (device.getType().implementsVersion(RENDERER_TYPE)) notifyAdded(Device.get(device));
    }

    @Override
    public void remoteDeviceUpdated(Registry registry, RemoteDevice device) {
    }

    @Override
    public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
        AppLog.i(TAG, "remoteDeviceRemoved: " + device.getDetails().getFriendlyName());
        if (device.getType().implementsVersion(RENDERER_TYPE)) notifyRemoved(Device.get(device));
    }

    @Override
    public synchronized void onServiceConnected(ComponentName name, IBinder binder) {
        if (!bindRegistered) return;
        AppLog.i(TAG, "onServiceConnected: DLNA service bound");
        upnpService = (AndroidUpnpService) binder;
        upnpService.getRegistry().addListener(this);
        serviceBound = true;

        // If search() was called before service was ready, execute it now
        if (pendingSearch) {
            AppLog.i(TAG, "Executing pending search");
            pendingSearch = false;
            search();
        }
    }

    @Override
    public synchronized void onServiceDisconnected(ComponentName name) {
        AppLog.w(TAG, "onServiceDisconnected: DLNA service disconnected");
        upnpService = null;
        serviceBound = false;
    }

    public void setDeviceListener(DeviceListener listener) {
        deviceListenerRef = listener == null ? null : new WeakReference<>(listener);
    }

    private DeviceListener getDeviceListener() {
        return deviceListenerRef == null ? null : deviceListenerRef.get();
    }

    private void notifyAdded(Device bean) {
        DeviceListener listener = getDeviceListener();
        AppLog.i(TAG, "notifyAdded: device=" + bean.getName() + " listener=" + (listener != null ? "present" : "NULL"));
        if (listener != null) App.post(() -> {
            DeviceListener current = getDeviceListener();
            if (current != null) current.onDeviceAdded(bean);
        });
    }

    private void notifyRemoved(Device bean) {
        DeviceListener listener = getDeviceListener();
        AppLog.i(TAG, "notifyRemoved: device=" + bean.getName() + " listener=" + (listener != null ? "present" : "NULL"));
        if (listener != null) App.post(() -> {
            DeviceListener current = getDeviceListener();
            if (current != null) current.onDeviceRemoved(bean);
        });
    }

    public synchronized void init(Context context) {
        if (bindRegistered) {
            AppLog.i(TAG, "init: already bound, skipping");
            return;
        }
        AppLog.i(TAG, "init: binding DLNA service");
        try {
            Context appContext = context.getApplicationContext();
            bindRegistered = appContext.bindService(new Intent(appContext, DLNACastService.class), this, Context.BIND_AUTO_CREATE);
            if (!bindRegistered) AppLog.w(TAG, "init: service binding was rejected");
        } catch (Exception e) {
            bindRegistered = false;
            AppLog.e(TAG, "init: failed to bind service", e);
        }
    }

    public void search() {
        if (upnpService != null) {
            AppLog.i(TAG, "search: sending M-SEARCH");
            upnpService.getControlPoint().search(new STAllHeader());
        } else if (!serviceBound) {
            // Service not yet bound — queue the search for when it connects
            AppLog.i(TAG, "search: service not bound yet, queuing pending search");
            pendingSearch = true;
        } else {
            AppLog.w(TAG, "search: service bound but upnpService is null");
        }
    }

    public boolean isReady() {
        return upnpService != null;
    }

    public List<Device> getRegistered() {
        if (upnpService == null) return List.of();
        return upnpService.getRegistry().getRemoteDevices().stream()
                .filter(d -> d.getType().implementsVersion(RENDERER_TYPE))
                .map(Device::get)
                .collect(Collectors.toList());
    }

    public RemoteDevice findDevice(Device bean) {
        if (upnpService == null) return null;
        return upnpService.getRegistry().getRemoteDevices().stream()
                .filter(d -> d.getType().implementsVersion(RENDERER_TYPE))
                .filter(d -> d.getIdentity().getUdn().getIdentifierString().equals(bean.getUuid()))
                .findFirst()
                .orElse(null);
    }

    public RemoteService findAVTransport(Device bean) {
        RemoteDevice rd = findDevice(bean);
        return rd != null ? rd.findService(AVT_TYPE) : null;
    }

    public ControlPoint getControlPoint() {
        return upnpService != null ? upnpService.getControlPoint() : null;
    }

    public synchronized void release(Context context) {
        if (upnpService != null) {
            upnpService.getRegistry().removeListener(this);
        }
        if (bindRegistered) {
            try {
                context.getApplicationContext().unbindService(this);
            } catch (Exception e) {
                AppLog.e(TAG, "release: failed to unbind service", e);
            }
        }
        upnpService = null;
        bindRegistered = false;
        serviceBound = false;
        pendingSearch = false;
        deviceListenerRef = null;
    }

    public interface DeviceListener {

        void onDeviceAdded(Device device);

        void onDeviceRemoved(Device device);
    }

    private static class Loader {
        static final DLNACastManager INSTANCE = new DLNACastManager();
    }
}
