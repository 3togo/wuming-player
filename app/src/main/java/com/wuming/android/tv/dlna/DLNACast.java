package com.wuming.android.tv.dlna;

import android.os.Handler;
import android.os.SystemClock;
import com.wuming.android.tv.server.process.CastDebug;
import android.os.Looper;

import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.CastVideo;
import com.wuming.android.tv.bean.Device;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.Notify;

import org.fourthline.cling.controlpoint.ControlPoint;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.RemoteService;
import org.fourthline.cling.support.avtransport.callback.GetPositionInfo;
import org.fourthline.cling.support.avtransport.callback.GetTransportInfo;
import org.fourthline.cling.support.avtransport.callback.Play;
import org.fourthline.cling.support.avtransport.callback.Seek;
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI;
import org.fourthline.cling.support.avtransport.callback.Stop;
import org.fourthline.cling.support.contentdirectory.DIDLParser;
import org.fourthline.cling.support.model.DIDLContent;
import org.fourthline.cling.support.model.DIDLObject;
import org.fourthline.cling.support.model.ProtocolInfo;
import org.fourthline.cling.support.model.Res;
import org.fourthline.cling.support.model.SeekMode;
import org.fourthline.cling.support.model.PositionInfo;
import org.fourthline.cling.support.model.TransportInfo;
import org.fourthline.cling.support.model.item.VideoItem;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class DLNACast {

    private static final String TAG = "DLNACast";
    private static final long POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_FAILURES = 10;
    private static final int STOPPED_THRESHOLD_AFTER_PLAYING = 3;
    private static final long MAX_DURATION_MS = 99 * 3600 * 1000L;

    private static final DIDLParser DIDL_PARSER = new DIDLParser();

    private final long diagnosticId = CastDebug.nextRequest();
    private final long diagnosticStarted = SystemClock.elapsedRealtime();
    private volatile String lastTransportState = "UNKNOWN";
    private long positionChangedAt = SystemClock.elapsedRealtime();
    private boolean positionObserved;
    private final CastVideo video;
    private Runnable onCasted;
    private Runnable onPlaybackEnded;
    private Runnable onPlaybackFailed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final WeakReference<DLNACast> selfRef = new WeakReference<>(this);

    private com.wuming.android.tv.cast.RendererCapabilityProfiles.Session capabilitySession;
    private Device currentDevice;
    private volatile boolean monitoring = false;
    private volatile boolean wasPlaying = false;
    private volatile boolean released = false;
    private volatile int pollFailCount = 0;
    private volatile int consecutiveStopped = 0;
    private volatile long currentPositionMs = 0;

    public DLNACast(CastVideo video, Runnable runnable) {
        this(video, runnable, null, null);
    }

    public DLNACast(CastVideo video, Runnable runnable, Runnable onPlaybackEnded) {
        this(video, runnable, onPlaybackEnded, null);
    }

    public DLNACast(CastVideo video, Runnable runnable, Runnable onPlaybackEnded, Runnable onPlaybackFailed) {
        if (video == null) throw new IllegalArgumentException("video must not be null");
        String url = video.url();
        if (url == null || url.isEmpty()) throw new IllegalArgumentException("video.url() must not be null or empty");
        this.video = video;
        this.onCasted = runnable;
        this.onPlaybackEnded = onPlaybackEnded;
        this.onPlaybackFailed = onPlaybackFailed;
    }

    public CastVideo video() {
        return video;
    }

    public long getCurrentPositionMs() {
        return currentPositionMs;
    }

    public void cast(Device item) {
        if (released) {
            AppLog.w(TAG, "cast: already released, ignoring");
            return;
        }
        this.currentDevice = item;
        capabilitySession = com.wuming.android.tv.cast.RendererCapabilityProfiles.session(item, video.url());
        CastDebug.event("cast=" + diagnosticId, "event=start relay=" + CastDebug.route(video.url()) + " upstream=" + CastDebug.endpoint(video.url()));
        ControlPoint control;
        RemoteService service;
        DLNACastManager mgr = DLNACastManager.get();
        if (mgr == null) {
            AppLog.e(TAG, "cast: DLNACastManager is null");
            App.post(() -> Notify.show(R.string.device_offline));
            return;
        }
        control = mgr.getControlPoint();
        service = mgr.findAVTransport(item);
        if (service != null && control != null) {
            control.execute(new UriAction(selfRef, control, service, video, buildMetaData()));
        } else {
            App.post(() -> Notify.show(R.string.device_offline));
        }
    }

    public void release() {
        released = true;
        stopMonitoring();
        handler.removeCallbacksAndMessages(null);
        onCasted = null;
        onPlaybackEnded = null;
        onPlaybackFailed = null;
        currentDevice = null;
        selfRef.clear();
        AppLog.i(TAG, "release: DLNACast released");
    }

    public void stop() {
        RendererHandle handle = resolveRenderer("stop");
        if (handle == null) return;
        stopMonitoring();
        handle.control.execute(new Stop(handle.service) {
            @Override
            public void success(ActionInvocation invocation) {
                AppLog.i(TAG, "Stop success for " + video.name());
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                logActionFailure(invocation, response);
            }
        });
    }

    public void seek(long positionMs) {
        if (capabilitySession != null) capabilitySession.seeking();
        RendererHandle handle = resolveRenderer("seek");
        if (handle == null) return;
        String target = formatMsStatic(Math.max(0, positionMs));
        AppLog.i(TAG, "Seek to " + target + " (" + positionMs + " ms)");
        handle.control.execute(new Seek(handle.service, SeekMode.REL_TIME, target) {
            @Override
            public void success(ActionInvocation invocation) {
                AppLog.i(TAG, "Seek success to " + target);
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
                logActionFailure(invocation, response);
            }
        });
    }

    private static class RendererHandle {
        final ControlPoint control;
        final RemoteService service;

        RendererHandle(ControlPoint control, RemoteService service) {
            this.control = control;
            this.service = service;
        }
    }

    private RendererHandle resolveRenderer(String action) {
        Device device = currentDevice;
        DLNACastManager mgr = DLNACastManager.get();
        if (device == null || mgr == null) {
            AppLog.w(TAG, action + ": no active renderer");
            return null;
        }
        ControlPoint control = mgr.getControlPoint();
        RemoteService service = mgr.findAVTransport(device);
        if (service == null || control == null) {
            AppLog.w(TAG, action + ": renderer is offline");
            return null;
        }
        return new RendererHandle(control, service);
    }

    private void stopMonitoring() {
        if (monitoring) {
            monitoring = false;
            handler.removeCallbacksAndMessages(null);
            AppLog.d(TAG, "stopMonitoring: monitoring stopped");
        }
    }

    private String buildMetaData() {
        String videoUrl = video.url();
        String videoName = video.name();
        if (videoUrl == null || videoUrl.isEmpty()) {
            AppLog.e(TAG, "buildMetaData: video URL is null or empty");
            return "";
        }
        if (videoName != null && videoName.length() > 80) {
            videoName = videoName.substring(0, 77) + "...";
        }
        try {
            DIDLContent content = new DIDLContent();
            // Keep DASH MIME consistent with the relay's .mpd suffix. The Coocaa test
            // advanced to media requests after both replaced generic video/* metadata.
            String mime = com.wuming.tv.core.playback.DashRelayPolicy.isManifest(videoUrl)
                    ? "application/dash+xml" : videoUrl.endsWith("/stream.mp4") ? "video/mp4" : "video/*";
            // Unknown size must be omitted, not 0: zero advertises an empty resource.
            Res res = new Res(new ProtocolInfo("http-get:*:" + mime + ":*"), (Long) null, videoUrl);
            if (video.duration() > 0) res.setDuration(formatMs(video.duration()));

            VideoItem item = new VideoItem("0", "-1", videoName != null ? videoName : "", "", res);
            Map<String, String> headers = video.headers();
            String audioTrackFormat = video.audioTrackFormat();
            boolean hasHeaders = headers != null && !headers.isEmpty();
            boolean hasAudioTrack = audioTrackFormat != null;
            if (hasHeaders || hasAudioTrack) {
                Map<String, String> metaHeaders = hasHeaders ? new HashMap<>(headers) : new HashMap<>();
                if (hasAudioTrack) metaHeaders.put("X-Audio-Track-Format", audioTrackFormat);
                item.addProperty(new DIDLObject.Property.DC.DESCRIPTION(App.gson().toJson(metaHeaders)));
            }

            content.addItem(item);
            return DIDL_PARSER.generate(content);
        } catch (Exception e) {
            AppLog.e(TAG, "Error building DIDL metadata", e);
            return "";
        }
    }

    private void logActionFailure(ActionInvocation invocation, UpnpResponse response) {
        logActionFailure(this, invocation, response);
    }

    private static void logActionFailure(DLNACast cast, ActionInvocation invocation, UpnpResponse response) {
        String action = invocation == null || invocation.getAction() == null
                ? "unknown" : invocation.getAction().getName();
        int upnpCode = invocation == null || invocation.getFailure() == null
                ? -1 : invocation.getFailure().getErrorCode();
        if (cast != null && !cast.released && cast.capabilitySession != null)
            cast.capabilitySession.failure(action, response == null ? -1 : response.getStatusCode(), upnpCode);
        String context = cast == null ? "cast=unavailable released=true"
                : "cast=" + cast.diagnosticId + " released=" + cast.released
                + " state=" + cast.lastTransportState + " positionMs=" + cast.currentPositionMs
                + " sessionElapsedMs=" + (SystemClock.elapsedRealtime() - cast.diagnosticStarted);
        AppLog.w(TAG, context + " event=action_failure action=" + action
                + " http=" + (response == null ? -1 : response.getStatusCode())
                + " upnp=" + upnpCode + " responsePresent=" + (response != null)
                + " causes=" + com.wuming.tv.core.upnp.UpnpFailureDiagnostic.types(
                        invocation == null ? null : invocation.getFailure()));
    }

    private synchronized void recordPosition(long position) {
        long now = SystemClock.elapsedRealtime();
        if (!positionObserved || position != currentPositionMs) positionChangedAt = now;
        positionObserved = true;
        currentPositionMs = position;
        if (capabilitySession != null) capabilitySession.position(position, "PLAYING".equals(lastTransportState));
        CastDebug.event("cast=" + diagnosticId, "event=position state=" + lastTransportState
                + " positionMs=" + position + " unchangedMs=" + (now - positionChangedAt));
    }

    private void handlePlayFailure(String msg) {
        AppLog.e(TAG, "cast=" + diagnosticId + " event=start_failed (see action_failure)");
        App.post(() -> Notify.show(msg));
        notifyPlaybackFailed();
        if (monitoring) {
            monitoring = false;
            handler.removeCallbacksAndMessages(null);
            AppLog.w(TAG, "Play failed during monitoring, stopping monitoring");
        }
    }

    private void onPlaySuccess(ControlPoint control, RemoteService service) {
        if (released) return;
        AppLog.i(TAG, "Play success");
        invokeRunnable();
        if (onPlaybackEnded != null) startMonitoring(control, service);
    }

    private void startMonitoring(ControlPoint control, RemoteService service) {
        monitoring = true;
        wasPlaying = false;
        pollFailCount = 0;
        consecutiveStopped = 0;
        AppLog.i(TAG, "startMonitoring for cast completion: " + video.name());
        handler.post(new PollingRunnable(selfRef, control, service));
    }

    private void pollTransportState(ControlPoint control, RemoteService service) {
        if (released) {
            AppLog.d(TAG, "pollTransportState: already released, skipping");
            return;
        }

        control.execute(new TransportStateCallback(selfRef, control, service));
        control.execute(new PositionInfoCallback(selfRef, service));
    }

    private void onTransportState(String state, ControlPoint control, RemoteService service) {
        lastTransportState = state;
        CastDebug.event("cast=" + diagnosticId, "event=transport state=" + state);
        pollFailCount = 0;

        if ("PLAYING".equals(state)) {
            wasPlaying = true;
            consecutiveStopped = 0;
        } else if ("STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state)) {
            if (!wasPlaying) {
                AppLog.d(TAG, "Ignoring STOPPED before PLAYING was observed");
                return;
            }
            consecutiveStopped++;
            if (consecutiveStopped >= STOPPED_THRESHOLD_AFTER_PLAYING) {
                monitoring = false;
                AppLog.i(TAG, "Playback ended after " + consecutiveStopped + " consecutive STOPPED (state=" + state + ")");
                notifyPlaybackEnded();
            }
        } else if ("PAUSED_PLAYBACK".equals(state)) {
            consecutiveStopped = 0;
        }
    }

    private void onTransportStateFailure(String msg, ControlPoint control, RemoteService service) {
        pollFailCount++;
        AppLog.w(TAG, "cast=" + diagnosticId + " GetTransportInfo failed count=" + pollFailCount);
        if (pollFailCount >= MAX_POLL_FAILURES) {
            stopMonitoring();
            notifyPlaybackFailed();
            AppLog.w(TAG, "Renderer monitoring unavailable; session marked as an error without advancing the queue");
        }
    }

    private void invokeRunnable() {
        Runnable runnable = onCasted;
        onCasted = null;
        if (runnable != null) {
            App.post(runnable);
        }
    }

    private void notifyPlaybackEnded() {
        if (!ended.compareAndSet(false, true)) return;
        Runnable callback = onPlaybackEnded;
        onPlaybackEnded = null;
        if (callback != null) callback.run();
    }

    private void notifyPlaybackFailed() {
        if (!failed.compareAndSet(false, true)) return;
        Runnable callback = onPlaybackFailed;
        onPlaybackFailed = null;
        if (callback != null) App.post(callback);
    }

    private String formatMs(long ms) {
        return formatMsStatic(ms);
    }

    private static class UriAction extends SetAVTransportURI {
        private final WeakReference<DLNACast> castRef;
        private final ControlPoint control;
        private final RemoteService service;
        private final CastVideo video;

        UriAction(WeakReference<DLNACast> castRef, ControlPoint control, RemoteService service, CastVideo video, String metadata) {
            super(service, video.url(), metadata);
            this.castRef = castRef;
            this.control = control;
            this.service = service;
            this.video = video;
        }

        @Override
        public void success(ActionInvocation i) {
            DLNACast cast = castRef.get();
            if (cast == null || cast.released) return;
            AppLog.i(TAG, "SetAVTransportURI success for " + video.name());
            if (video.position() > 0) {
                control.execute(new SeekBeforePlayAction(castRef, control, service, video));
            } else {
                control.execute(new PlayAction(castRef, control, service, false));
            }
        }

        @Override
        public void failure(ActionInvocation i, UpnpResponse r, String msg) {
            DLNACast diagnosticCast = castRef.get();
            logActionFailure(diagnosticCast, i, r);
            DLNACast cast = castRef.get();
            if (cast != null && !cast.released) cast.handlePlayFailure(msg);

        }
    }

    private static class SeekBeforePlayAction extends Seek {
        private final WeakReference<DLNACast> castRef;
        private final ControlPoint control;
        private final RemoteService service;
        private final CastVideo video;

        SeekBeforePlayAction(WeakReference<DLNACast> castRef, ControlPoint control, RemoteService service, CastVideo video) {
            super(service, SeekMode.REL_TIME, formatMsStatic(video.position()));
            this.castRef = castRef;
            this.control = control;
            this.service = service;
            this.video = video;
        }

        @Override
        public void success(ActionInvocation i) {
            DLNACast cast = castRef.get();
            if (cast == null || cast.released) return;
            AppLog.i(TAG, "Seek-before-Play success to " + formatMsStatic(video.position()) + ", now playing");
            control.execute(new PlayAction(castRef, control, service, false));
        }

        @Override
        public void failure(ActionInvocation i, UpnpResponse r, String msg) {
            DLNACast diagnosticCast = castRef.get();
            logActionFailure(diagnosticCast, i, r);
            DLNACast cast = castRef.get();
            if (cast == null || cast.released) return;
            AppLog.w(TAG, "Seek-before-Play failed; falling back to Play-then-Seek");
            control.execute(new PlayAction(castRef, control, service, true));
        }
    }

    private static class PlayAction extends Play {
        private final WeakReference<DLNACast> castRef;
        private final ControlPoint control;
        private final RemoteService service;
        private final boolean seekAfter;

        PlayAction(WeakReference<DLNACast> castRef, ControlPoint control, RemoteService service, boolean seekAfter) {
            super(service);
            this.castRef = castRef;
            this.control = control;
            this.service = service;
            this.seekAfter = seekAfter;
        }

        @Override
        public void success(ActionInvocation i) {
            DLNACast cast = castRef.get();
            if (cast == null || cast.released) return;
            if (seekAfter) {
                AppLog.i(TAG, "Play success, now seeking to " + formatMsStatic(cast.video.position()));
                control.execute(new SeekAfterPlayAction(castRef, control, service, cast.video));
            } else {
                cast.onPlaySuccess(control, service);
            }
        }

        @Override
        public void failure(ActionInvocation i, UpnpResponse r, String msg) {
            DLNACast diagnosticCast = castRef.get();
            logActionFailure(diagnosticCast, i, r);
            DLNACast cast = castRef.get();
            if (cast != null && !cast.released) cast.handlePlayFailure(msg);

        }
    }

    private static class SeekAfterPlayAction extends Seek {
        private final WeakReference<DLNACast> castRef;
        private final ControlPoint control;
        private final RemoteService service;
        private final CastVideo video;

        SeekAfterPlayAction(WeakReference<DLNACast> castRef, ControlPoint control, RemoteService service, CastVideo video) {
            super(service, SeekMode.REL_TIME, formatMsStatic(video.position()));
            this.castRef = castRef;
            this.control = control;
            this.service = service;
            this.video = video;
        }

        @Override
        public void success(ActionInvocation i) {
            DLNACast cast = castRef.get();
            if (cast == null || cast.released) return;
            AppLog.i(TAG, "Seek-after-Play success to " + formatMsStatic(video.position()));
            cast.onPlaySuccess(control, service);
        }

        @Override
        public void failure(ActionInvocation i, UpnpResponse r, String m) {
            DLNACast diagnosticCast = castRef.get();
            logActionFailure(diagnosticCast, i, r);
            DLNACast cast = castRef.get();
            if (cast == null || cast.released) return;

            cast.onPlaySuccess(control, service);
        }
    }

    private static class TransportStateCallback extends GetTransportInfo {
        private final WeakReference<DLNACast> castRef;
        private final ControlPoint control;
        private final RemoteService service;

        TransportStateCallback(WeakReference<DLNACast> castRef, ControlPoint control, RemoteService service) {
            super(service);
            this.castRef = castRef;
            this.control = control;
            this.service = service;
        }

        @Override
        public void received(ActionInvocation invocation, TransportInfo info) {
            DLNACast cast = castRef.get();
            if (cast == null || cast.released) return;

            try {
                String state = info.getCurrentTransportState().getValue();
                cast.onTransportState(state, control, service);
            } catch (Exception e) {
                AppLog.e(TAG, "Error parsing transport state", e);
            }
        }

        @Override
        public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
            DLNACast diagnosticCast = castRef.get();
            logActionFailure(diagnosticCast, invocation, response);
            DLNACast cast = castRef.get();
            if (cast == null || cast.released) return;
            cast.onTransportStateFailure(msg, control, service);
        }
    }

    private static class PositionInfoCallback extends GetPositionInfo {
        private final WeakReference<DLNACast> castRef;

        PositionInfoCallback(WeakReference<DLNACast> castRef, RemoteService service) {
            super(service);
            this.castRef = castRef;
        }

        @Override
        public void received(ActionInvocation invocation, PositionInfo info) {
            DLNACast cast = castRef.get();
            if (cast == null || cast.released) return;
            try {
                long seconds = info.getTrackElapsedSeconds();
                if (seconds >= 0) cast.recordPosition(seconds * 1000L);
            } catch (Exception e) {
                AppLog.d(TAG, "Error parsing position info: " + e.getMessage());
            }
        }

        @Override
        public void failure(ActionInvocation invocation, UpnpResponse response, String msg) {
            DLNACast diagnosticCast = castRef.get();
            logActionFailure(diagnosticCast, invocation, response);
        }
    }

    private static class PollingRunnable implements Runnable {
        private final WeakReference<DLNACast> castRef;
        private final ControlPoint control;
        private final RemoteService service;

        PollingRunnable(WeakReference<DLNACast> castRef, ControlPoint control, RemoteService service) {
            this.castRef = castRef;
            this.control = control;
            this.service = service;
        }

        @Override
        public void run() {
            DLNACast cast = castRef.get();
            if (cast == null) return;
            if (!cast.monitoring || cast.released) return;
            cast.pollTransportState(control, service);
            if (cast.monitoring && !cast.released) {
                cast.handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    }

    private static String formatMsStatic(long ms) {
        if (ms <= 0) return "00:00:00";
        long cappedMs = Math.min(ms, MAX_DURATION_MS);
        long s = cappedMs / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }
}
