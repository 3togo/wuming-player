package com.wuming.android.tv.server.process;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.wuming.android.tv.App;
import com.wuming.android.tv.Constant;
import com.wuming.android.tv.api.SiteApi;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.bean.BeanUtils;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Device;
import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.bean.Keep;
import com.wuming.android.tv.bean.Vod;
import com.wuming.android.tv.event.CastEvent;
import com.wuming.android.tv.event.RefreshEvent;
import com.wuming.android.tv.event.ServerEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.library.UserLibrary;
import com.wuming.android.tv.server.Nano;
import com.wuming.android.tv.server.Server;
import com.wuming.android.tv.server.impl.Process;
import com.wuming.android.tv.player.PlayerManager;
import com.wuming.android.tv.service.PlaybackService;
import com.wuming.android.tv.utils.FileUtil;
import com.wuming.android.tv.utils.Notify;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import okhttp3.FormBody;

public class Action implements Process {

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/action");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        Map<String, String> params = Nano.params(session);
        String param = params.get("do");
        if ("file".equals(param)) onFile(params);
        else if ("push".equals(param)) onPush(params);
        else if ("cast".equals(param)) onCast(params);
        else if ("sync".equals(param)) onSync(params);
        else if ("search".equals(param)) onSearch(params);
        else if ("setting".equals(param)) onSetting(params);
        else if ("refresh".equals(param)) onRefresh(params);
        else if ("control".equals(param)) onControl(params);
        return Nano.ok();
    }

    private void onFile(Map<String, String> params) {
        String path = params.get("path");
        if (BeanUtils.isEmpty(path)) return;
        if (path.endsWith(".apk")) FileUtil.openFile(Path.local(path));
        else if (path.endsWith(".srt") || path.endsWith(".ssa") || path.endsWith(".ass")) RefreshEvent.subtitle(path);
        else ServerEvent.setting(path);
    }

    private void onPush(Map<String, String> params) {
        String url = params.get("url");
        if (BeanUtils.isEmpty(url)) return;
        boolean cast = Objects.equals(params.get("cast"), "true");
        String castSession = params.get("castSession");
        if (cast && !RemoteCastCommandTracker.accept(
                castSession, System.currentTimeMillis())) return;
        String name = Objects.requireNonNullElse(params.get("name"), url);
        String audioTrackFormat = params.get("audioTrackFormat");
        long castPosition = parsePosition(params.get("position"));
        App.post(() -> {
            Intent intent = cast
                    ? new Intent().setClassName(App.get().getPackageName(), "com.wuming.android.tv.ui.activity.VideoActivity")
                    .putExtra("key", SiteApi.PUSH)
                    .putExtra("id", url)
                    .putExtra("name", name)
                    .putExtra("castPosition", castPosition)
                    .putExtra("cast", true)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .setClassName(App.get().getPackageName(), "com.wuming.android.tv.ui.activity.HomeActivity");
            if (audioTrackFormat != null) intent.putExtra("audioTrackFormat", audioTrackFormat);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Activity activity = App.activity();
            if (activity != null) activity.startActivity(intent);
            else App.get().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
    }

    private static long parsePosition(String value) {
        if (value == null) return 0L;
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void onSearch(Map<String, String> params) {
        String word = params.get("word");
        if (BeanUtils.isEmpty(word)) return;
        ServerEvent.search(word);
    }

    private void onSetting(Map<String, String> params) {
        String text = params.get("text");
        String name = params.get("name");
        if (BeanUtils.isEmpty(text)) return;
        ServerEvent.setting(text, BeanUtils.emptyIfNull(name));
    }

    private void onRefresh(Map<String, String> params) {
        String type = params.get("type");
        String path = params.get("path");
        String json = params.get("json");
        if ("live".equals(type)) RefreshEvent.live();
        else if ("detail".equals(type)) RefreshEvent.detail();
        else if ("player".equals(type)) RefreshEvent.player();
        else if ("category".equals(type)) RefreshEvent.category();
        else if ("danmaku".equals(type)) RefreshEvent.danmaku(path);
        else if ("subtitle".equals(type)) RefreshEvent.subtitle(path);
        else if ("vod".equals(type)) RefreshEvent.vod(Vod.objectFrom(json));
    }

    private void onControl(Map<String, String> params) {
        PlaybackService service = Server.get().getService();
        String type = params.get("type");
        String castSession = params.get("castSession");
        if (service == null) return;
        App.post(() -> {
            if ("play".equals(type) || "pause".equals(type)) {
                // The service can finish tearing down before this posted work runs, and a null
                // player here kills the main thread (the app restarts itself).
                PlayerManager player = service.player();
                if (player == null) return;
                if ("play".equals(type)) player.play();
                else player.pause();
                return;
            }
            if ("stop".equals(type) && RemoteCastCommandTracker.canStop(castSession)) {
                RemoteCastCommandTracker.stopped(castSession);
                service.dispatchStop();
            } else if ("prev".equals(type)) service.dispatchPrev();
            else if ("next".equals(type)) service.dispatchNext();
            else if ("loop".equals(type)) service.dispatchLoop();
            else if ("replay".equals(type)) service.dispatchReplay();
        });
    }

    private void onCast(Map<String, String> params) {
        String configJson = params.get("config");
        String deviceJson = params.get("device");
        String historyJson = params.get("history");
        if (configJson == null || deviceJson == null || historyJson == null) return;
        if (!RemoteCastCommandTracker.accept(
                params.get("castSession"), System.currentTimeMillis())) return;
        Config config = Config.objectFrom(configJson);
        Device device = Device.objectFrom(deviceJson);
        History history = History.objectFrom(historyJson);
        String audioTrackFormat = params.get("audioTrackFormat");
        CastEvent.post(Config.find(config), device, history, audioTrackFormat);
    }

    private void onSync(Map<String, String> params) {
        String type = params.get("type");
        boolean force = Objects.equals(params.get("force"), "true");
        String mode = Objects.requireNonNullElse(params.get("mode"), "0");
        if (params.get("device") != null && (mode.equals("0") || mode.equals("2"))) {
            Device device = Device.objectFrom(params.get("device"));
            if (device == null) return;
            if ("history".equals(type)) sendHistory(device, params);
            else if ("keep".equals(type)) sendKeep(device);
        }
        if (mode.equals("0") || mode.equals("1")) {
            if ("history".equals(type)) syncHistory(params, force);
            else if ("keep".equals(type)) syncKeep(params, force);
        }
    }

    private void post(Device device, String type, FormBody.Builder body) {
        try (okhttp3.Response response = OkHttp.newCall(
                OkHttp.client(Constant.TIMEOUT_SYNC),
                device.getIp().concat("/action?do=sync&mode=0&type=" + type),
                body.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new java.io.IOException("Sync request failed (HTTP "
                        + response.code() + ")");
            }
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
        }
    }

    private void sendHistory(Device device, Map<String, String> params) {
        try {
            Config config = Config.find(Config.objectFrom(params.get("config")));
            if (config.getUrl() == null) config = Config.vod();
            FormBody.Builder body = new FormBody.Builder();
            body.add("config", config.toString());
            body.add("targets", App.gson().toJson(UserLibrary.get().histories(config.getId())));
            post(device, "history", body);
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
        }
    }

    private void sendKeep(Device device) {
        try {
            FormBody.Builder body = new FormBody.Builder();
            body.add("targets", App.gson().toJson(UserLibrary.get().videoFavorites()));
            body.add("configs", App.gson().toJson(Config.findUrls()));
            post(device, "keep", body);
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
        }
    }

    public void syncHistory(Map<String, String> params, boolean force) {
        Config config = Config.find(Config.objectFrom(params.get("config")));
        List<History> targets = History.arrayFrom(params.get("targets"));
        if (config.getUrl() == null) return;
        if (config.getUrl().equals(VodConfig.getUrl())) {
            if (force) UserLibrary.get().deleteHistoryCatalog(config.getId());
            UserLibrary.get().syncHistories(targets);
            RefreshEvent.history();
        } else {
            VodConfig.load(config, getCallback(targets, force, config.getId()));
        }
    }

    private Callback getCallback(List<History> targets, boolean force, int cid) {
        return new HistorySyncCallback(targets, force, cid);
    }

    private void syncKeep(Map<String, String> params, boolean force) {
        List<Keep> targets = Keep.arrayFrom(params.get("targets"));
        List<Config> configs = Config.arrayFrom(params.get("configs"));
        if (BeanUtils.isEmpty(VodConfig.getUrl()) && !configs.isEmpty()) {
            VodConfig.load(Config.find(configs.get(0)), getCallback(configs, targets, force));
        } else {
            if (force) UserLibrary.get().deleteAllVideoFavorites();
            UserLibrary.get().syncFavorites(configs, targets);
            RefreshEvent.keep();
        }
    }

    private Callback getCallback(List<Config> configs, List<Keep> targets, boolean force) {
        return new KeepSyncCallback(configs, targets, force);
    }

    private static class HistorySyncCallback extends Callback {
        private final List<History> targets;
        private final boolean force;
        private final int cid;

        HistorySyncCallback(List<History> targets, boolean force, int cid) {
            this.targets = targets;
            this.force = force;
            this.cid = cid;
        }

        @Override
        public void success() {
            if (force) UserLibrary.get().deleteHistoryCatalog(cid);
            UserLibrary.get().syncHistories(targets);
            RefreshEvent.history();
        }

        @Override
        public void error(String msg) {
            Notify.show(msg);
        }
    }

    private static class KeepSyncCallback extends Callback {
        private final List<Config> configs;
        private final List<Keep> targets;
        private final boolean force;

        KeepSyncCallback(List<Config> configs, List<Keep> targets, boolean force) {
            this.configs = configs;
            this.targets = targets;
            this.force = force;
        }

        @Override
        public void success() {
            if (force) UserLibrary.get().deleteAllVideoFavorites();
            UserLibrary.get().syncFavorites(configs, targets);
            RefreshEvent.keep();
        }

        @Override
        public void error(String msg) {
            Notify.show(msg);
        }
    }
}
